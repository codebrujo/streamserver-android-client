package info.ogarkov.streamclient

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class AuthException(msg: String) : IOException(msg)

/**
 * Which server address is in use.
 * On Wi-Fi the local (http) address is tried first; when it stops answering requests move to the
 * global (https) address at once, and the local one is re-checked every [CHECK_MS] while on Wi-Fi.
 */
object Conn {
    enum class Mode { CHECKING, LOCAL, GLOBAL, OFFLINE }
    data class State(val mode: Mode, val base: String?)

    private const val CHECK_MS = 15_000L

    private val _state = MutableStateFlow(State(Mode.CHECKING, null))
    val state: StateFlow<State> = _state
    val base: String? get() = _state.value.base
    val isLocal: Boolean get() = _state.value.mode == Mode.LOCAL

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private lateinit var cm: ConnectivityManager
    @Volatile private var lastWifi: Boolean? = null

    fun init(ctx: Context) {
        cm = ctx.getSystemService(ConnectivityManager::class.java)
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = changed()
            override fun onLost(network: Network) = changed()
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                // fires often (signal strength etc.): react only when Wi-Fi appears or disappears
                if (onWifi() != lastWifi) changed()
            }
        })
        scope.launch {
            while (true) {
                delay(CHECK_MS)
                try { periodic() } catch (e: Exception) { }
            }
        }
        recheck()
    }

    private fun changed() {
        lastWifi = onWifi()
        recheck()
    }

    fun onWifi(): Boolean {
        val n = cm.activeNetwork ?: return false
        val c = cm.getNetworkCapabilities(n) ?: return false
        return c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private fun clean(u: String) = u.trim().trimEnd('/').takeIf { it.startsWith("http://") || it.startsWith("https://") }
    fun localUrl(): String? = clean(Prefs.localUrl)
    fun globalUrl(): String? = clean(Prefs.globalUrl)

    fun recheck() {
        scope.launch { select() }
    }

    suspend fun select() = mutex.withLock {
        val l = localUrl()
        val g = globalUrl()
        _state.value = when {
            l != null && onWifi() && Api.probe(l) -> State(Mode.LOCAL, l)
            g != null && Api.probe(g) -> State(Mode.GLOBAL, g)
            else -> State(Mode.OFFLINE, null)
        }
    }

    private suspend fun periodic() {
        val s = _state.value
        when (s.mode) {
            Mode.LOCAL -> if (!onWifi() || !Api.probe(s.base!!)) select()
            Mode.GLOBAL -> if (onWifi() && localUrl() != null) select()
            Mode.OFFLINE, Mode.CHECKING -> select()
        }
    }

    /** The HTTP layer saw the local address fail: switch to the global one right away. */
    fun localFailed(): String? {
        val g = globalUrl()
        _state.value = if (g != null) State(Mode.GLOBAL, g) else State(Mode.OFFLINE, null)
        return g
    }
}

/**
 * HTTP access to streamserver. All app URLs use the virtual host [BASE]; an interceptor rewrites them
 * to the address currently chosen by [Conn], retries on the global address when the local one fails,
 * and logs in again when the session cookie is missing or expired (each address has its own cookie).
 * The player uses the same client, so streams follow the address switches too.
 */
object Api {
    const val HOST = "streamserver.app"
    const val BASE = "http://$HOST"

    private val cookieStore = HashMap<String, MutableList<Cookie>>()
    private val jar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            synchronized(cookieStore) {
                val list = cookieStore.getOrPut(url.host) { mutableListOf() }
                for (c in cookies) {
                    list.removeAll { it.name == c.name }
                    list.add(c)
                }
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(cookieStore) {
            cookieStore[url.host]?.filter { it.matches(url) && it.expiresAt > System.currentTimeMillis() } ?: emptyList()
        }
    }

    private val raw: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(jar)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val quick: OkHttpClient = raw.newBuilder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .build()

    val http: OkHttpClient = raw.newBuilder()
        .addInterceptor(Interceptor { chain -> intercept(chain) })
        .build()

    /** Is the server at [base] answering? Logs in on the way if needed. */
    fun probe(base: String): Boolean = try {
        val code = quick.newCall(Request.Builder().url("$base/api/me").build()).execute().use { it.code }
        if (code == 401) {
            try { login(base) } catch (e: AuthException) { }
        }
        code == 200 || code == 401
    } catch (e: Exception) {
        false
    }

    private val loginLock = Any()

    fun login(base: String) = synchronized(loginLock) {
        val pw = Prefs.password
        if (pw.isEmpty()) throw AuthException("Password is not set")
        val body = JSONObject().put("password", pw).toString().toRequestBody("application/json".toMediaType())
        raw.newCall(Request.Builder().url("$base/api/login").post(body).build()).execute().use { r ->
            when {
                r.code == 401 -> throw AuthException("Wrong password")
                r.code == 429 -> throw IOException("Too many attempts, try again in 15 minutes")
                !r.isSuccessful -> throw IOException("Login failed: HTTP ${r.code}")
            }
        }
    }

    private fun rebase(req: Request, base: String): Request {
        val b = base.toHttpUrl()
        val url = req.url.newBuilder().scheme(b.scheme).host(b.host).port(b.port).build()
        return req.newBuilder().url(url).build()
    }

    private fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        if (req.url.host != HOST) return chain.proceed(req)
        var base = Conn.base ?: throw IOException("No connection to the server")
        var resp = try {
            chain.proceed(rebase(req, base))
        } catch (e: IOException) {
            if (chain.call().isCanceled() || base != Conn.localUrl()) throw e
            base = Conn.localFailed() ?: throw e
            chain.proceed(rebase(req, base))
        }
        if (resp.code == 401) {
            resp.close()
            login(base)
            resp = chain.proceed(rebase(req, base))
        }
        return resp
    }

    fun url(path: String, vararg query: Pair<String, String>): String {
        val b = (BASE + path).toHttpUrl().newBuilder()
        for ((k, v) in query) b.addQueryParameter(k, v)
        return b.build().toString()
    }

    suspend fun text(url: String): String = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).build()).execute().use {
            val t = it.body?.string() ?: ""
            if (!it.isSuccessful) {
                val msg = try { JSONObject(t).optString("error") } catch (e: Exception) { "" }
                throw IOException(msg.ifBlank { "HTTP ${it.code}" })
            }
            t
        }
    }

    suspend fun bytes(url: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            http.newCall(Request.Builder().url(url).build()).execute().use { if (it.isSuccessful) it.body?.bytes() else null }
        } catch (e: Exception) {
            null
        }
    }

    /** Fire-and-forget GET (warming up the next track on the server). */
    fun ping(url: String) {
        http.newCall(Request.Builder().url(url).build()).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {}
            override fun onResponse(call: okhttp3.Call, response: Response) = response.close()
        })
    }
}
