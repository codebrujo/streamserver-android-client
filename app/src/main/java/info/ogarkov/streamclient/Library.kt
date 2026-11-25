package info.ogarkov.streamclient

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

// ---------- settings and local state ----------

object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("streamclient", Context.MODE_PRIVATE)
    }

    private fun str(k: String, d: String) = sp.getString(k, d) ?: d
    private fun put(k: String, v: String) = sp.edit().putString(k, v).apply()

    var localUrl: String
        get() = str("local", "http://192.168.50.5:8090")
        set(v) = put("local", v)
    var globalUrl: String
        get() = str("global", "https://streamserver.ogarkov.info")
        set(v) = put("global", v)
    var password: String
        get() = str("password", "")
        set(v) = put("password", v)

    // last chosen audio / subtitle language for videos ("off" = subtitles disabled)
    var audioLang: String
        get() = str("audioLang", "")
        set(v) = put("audioLang", v)
    var subLang: String
        get() = str("subLang", "")
        set(v) = put("subLang", v)

    // folders the user opened in the tree
    var expanded: Set<String>
        get() = sp.getStringSet("expanded", emptySet())!!.toSet()
        set(v) = sp.edit().putStringSet("expanded", v).apply()

    // local media folders (tree uris from the system folder picker), per category a / m / v
    fun localFolder(cat: String): String = str("localFolder_$cat", "")
    fun setLocalFolder(cat: String, uri: String) = put("localFolder_$cat", uri)

    var recentJson: String
        get() = str("recent", "[]")
        set(v) = put("recent", v)
}

// ---------- models (JSON of streamserver) ----------

data class SubFile(val id: String, val label: String, val lang: String)

data class Video(val id: String, val title: String, val file: String, val folder: String, val size: Long, val subs: List<SubFile>)

data class MusicFolder(val path: String, val count: Int, val files: List<String>)

data class Track(
    val id: String,
    val n: Int,
    val title: String,
    val artist: String,
    val album: String,
    val duration: Double,
    val format: String,
    val quality: String,
    val deliverOrig: String?, // null = the original file is sent as-is
    val deliverEco: String?,
    val folder: String,
    val missing: Boolean = false, // in a playlist, but the file is gone from the drive
    val localUri: String? = null, // a file in the phone's local folders (content:// uri)
    val localName: String? = null,
) {
    /** Track ids are base64url JSON {f: "relative/path.flac", s, e} (s/e = CUE track bounds). */
    val file: String by lazy {
        if (localName != null) return@lazy localName
        try {
            val json = String(Base64.decode(id, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP), Charsets.UTF_8)
            JSONObject(json).optString("f").substringAfterLast('/')
        } catch (e: Exception) {
            ""
        }
    }
}

data class AudioTrack(val index: Int, val codec: String, val channels: Int, val lang: String, val title: String, val isDefault: Boolean) {
    fun label(): String {
        val parts = mutableListOf<String>()
        if (title.isNotBlank()) parts += title
        if (lang.isNotBlank()) parts += lang
        parts += codec.uppercase() + if (channels > 0) " ${channelsLabel(channels)}" else ""
        return parts.joinToString(" · ")
    }
}

fun channelsLabel(ch: Int) = when (ch) {
    1 -> "mono"
    2 -> "stereo"
    6 -> "5.1"
    8 -> "7.1"
    else -> "${ch}ch"
}

data class EmbeddedSub(val index: Int, val codec: String, val lang: String, val title: String)

data class VideoStream(val codec: String, val width: Int, val height: Int)

data class ProbeInfo(val duration: Double, val video: VideoStream?, val audio: List<AudioTrack>, val subs: List<EmbeddedSub>, val modes: List<String>) {
    fun mode(audioIdx: Int): String = modes.getOrNull(audioIdx) ?: modes.firstOrNull() ?: "video"
}

/** One of the last started videos / tracks with where playback stopped. */
data class RecentItem(
    val kind: String, // "video" | "audio"
    val id: String,
    val title: String,
    val subtitle: String,
    val folder: String,
    val pos: Double,
    val dur: Double,
    val at: Long,
)

/** A playlist: from GET /api/playlists, or kept on the phone ([local], see LocalPlaylists). */
data class PlaylistInfo(
    val id: String,
    val name: String,
    val count: Int,
    val duration: Double,
    val cover: String?,
    val local: Boolean = false,
    val loop: Boolean = false,
)

/** A folder of the browse tree. Keys: "v:<path>" for video, "m:<path>" for music; roots are "v:" and "m:". */
class Node(val key: String, val name: String, val path: String, val music: Boolean) {
    val folders = ArrayList<Node>()
    val videos = ArrayList<Video>()
    val localFiles = ArrayList<LocalItem>() // "Local media" tree only
    var hasTracks = false // music: this folder itself contains audio
    var count = 0 // files below, for display
}

// ---------- library ----------

object Library {
    @Volatile var videos: List<Video> = emptyList()
        private set
    @Volatile var musicFolders: List<MusicFolder> = emptyList()
        private set
    @Volatile var musicEnabled = false
        private set
    @Volatile var loaded = false
        private set

    var videoRoot = Node("v:", "Video", "", false)
        private set
    var musicRoot = Node("m:", "Music", "", true)
        private set

    private val tracks = HashMap<String, List<Track>>()

    // playlists (/api/playlists, shared with the web UI)
    @Volatile var playlistsEnabled = false
        private set
    @Volatile private var serverPlaylists: List<PlaylistInfo> = emptyList()

    /** server playlists and the phone's own ones, by name */
    val playlists: List<PlaylistInfo>
        get() = (serverPlaylists + LocalPlaylists.infos()).sortedWith { a, b -> natural.compare(a.name, b.name) }
    private val playlistTracks = HashMap<String, List<Track>>()
    private var videoOrder: List<Video> = emptyList()
    private var musicOrder: List<String> = emptyList()

    /** bumped whenever something the lists show has changed */
    val version = MutableStateFlow(0)
    fun bump() {
        version.value = version.value + 1
    }

    suspend fun load(refresh: Boolean) {
        val r = if (refresh) "1" else "0"
        val me = JSONObject(Api.text(Api.url("/api/me")))
        val music = me.optBoolean("music")
        val vids = parseVideos(JSONArray(Api.text(Api.url("/api/videos", "refresh" to r))))
        val folders = if (music) parseFolders(JSONArray(Api.text(Api.url("/api/music/tree", "refresh" to r)))) else emptyList()
        synchronized(tracks) { if (refresh) tracks.clear() }
        videos = vids
        musicFolders = folders
        musicEnabled = music
        videoRoot = buildVideoTree(vids)
        musicRoot = buildMusicTree(folders)
        videoOrder = ArrayList<Video>().also { collectVideos(videoRoot, it) }
        musicOrder = ArrayList<String>().also { collectMusic(musicRoot, it) }
        if (music) loadPlaylistList() else playlistsEnabled = false
        loaded = true
        bump()
    }

    fun cachedTracks(path: String): List<Track>? = synchronized(tracks) { tracks[path] }

    suspend fun tracks(path: String): List<Track> {
        cachedTracks(path)?.let { return it }
        val o = JSONObject(Api.text(Api.url("/api/music/folder", "path" to path)))
        val arr = o.optJSONArray("tracks") ?: JSONArray()
        val list = (0 until arr.length()).map { parseTrack(arr.getJSONObject(it), path) }
        synchronized(tracks) { tracks[path] = list }
        return list
    }

    fun parseTrack(t: JSONObject, folder: String): Track {
        val d = t.optJSONObject("deliver")
        return Track(
            id = t.getString("id"),
            n = t.optInt("n"),
            title = englishTitle(t.optString("title")),
            artist = t.optString("artist"),
            album = t.optString("album"),
            duration = t.optDouble("duration", 0.0),
            format = t.optString("format"),
            quality = t.optString("quality").replace("кГц", "kHz"),
            deliverOrig = optLabel(d, "orig"),
            deliverEco = optLabel(d, "eco"),
            folder = folder,
            missing = t.optBoolean("missing"),
            localUri = t.optString("localUri").ifEmpty { null },
            localName = t.optString("localName").ifEmpty { null },
        )
    }

    /** The fields the server keeps for a playlist track (see playlists.js). */
    fun trackJson(t: Track): JSONObject = JSONObject()
        .put("id", t.id)
        .put("n", t.n)
        .put("title", t.title)
        .put("artist", t.artist)
        .put("album", t.album)
        .put("duration", t.duration)
        .put("folder", t.folder)
        .put("format", t.format)
        .put("quality", t.quality)
        .put("deliver", JSONObject().put("orig", t.deliverOrig ?: JSONObject.NULL).put("eco", t.deliverEco ?: JSONObject.NULL))
        .also { o ->
            // only in playlists kept on the phone (the server drops unknown fields anyway)
            t.localUri?.let { o.put("localUri", it) }
            t.localName?.let { o.put("localName", it) }
        }

    // ---------- playlists ----------

    /** Reload the list of playlists (names, counts); drops cached playlist contents. */
    suspend fun loadPlaylistList() {
        try {
            val arr = JSONArray(Api.text(Api.url("/api/playlists")))
            serverPlaylists = (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                PlaylistInfo(o.getString("id"), o.optString("name"), o.optInt("count"), o.optDouble("duration", 0.0),
                    if (o.isNull("cover")) null else o.optString("cover"))
            }
            playlistsEnabled = true
        } catch (e: AuthException) {
            throw e
        } catch (e: Exception) {
            // an older server without playlists, or no connection
            serverPlaylists = emptyList()
            playlistsEnabled = false
        }
        synchronized(playlistTracks) { playlistTracks.clear() }
        bump()
    }

    fun cachedPlaylist(id: String): List<Track>? =
        if (LocalPlaylists.isLocal(id)) LocalPlaylists.tracks(id) else synchronized(playlistTracks) { playlistTracks[id] }

    /** Name and tracks of a playlist (tracks whose files are gone are marked [Track.missing]). */
    suspend fun playlist(id: String): Pair<String, List<Track>> {
        if (LocalPlaylists.isLocal(id)) {
            val p = LocalPlaylists.get(id) ?: throw java.io.IOException("Playlist not found")
            return p.name to (LocalPlaylists.tracks(id) ?: emptyList())
        }
        val o = JSONObject(Api.text(Api.url("/api/playlists/$id")))
        val arr = o.optJSONArray("tracks") ?: JSONArray()
        val list = (0 until arr.length()).map {
            val t = arr.getJSONObject(it)
            parseTrack(t, t.optString("folder"))
        }
        synchronized(playlistTracks) { playlistTracks[id] = list }
        return o.optString("name") to list
    }

    suspend fun createPlaylist(name: String, local: Boolean): String {
        if (local) return LocalPlaylists.create(name).also { bump() }
        val o = JSONObject(Api.send("POST", Api.url("/api/playlists"), JSONObject().put("name", name)))
        loadPlaylistList()
        return o.getString("id")
    }

    suspend fun savePlaylist(id: String, name: String, list: List<Track>, loop: Boolean) {
        if (LocalPlaylists.isLocal(id)) {
            LocalPlaylists.update(id, name, loop, list)
            bump()
            return
        }
        val arr = JSONArray()
        list.forEach { arr.put(trackJson(it)) }
        Api.send("PUT", Api.url("/api/playlists/$id"), JSONObject().put("name", name).put("tracks", arr))
        loadPlaylistList()
    }

    suspend fun deletePlaylist(id: String) {
        if (LocalPlaylists.isLocal(id)) {
            LocalPlaylists.delete(id)
            bump()
            return
        }
        Api.send("DELETE", Api.url("/api/playlists/$id"), null)
        loadPlaylistList()
    }

    private fun optLabel(o: JSONObject?, key: String): String? =
        if (o == null || !o.has(key) || o.isNull(key)) null else o.optString(key).ifEmpty { null }

    // the server names untitled CUE tracks in Russian
    private fun englishTitle(t: String) = Regex("^Трек (\\d+)$").find(t)?.let { "Track ${it.groupValues[1]}" } ?: t

    fun video(id: String) = videos.firstOrNull { it.id == id }

    /** Continuous playback order = the fully expanded tree, top to bottom. */
    fun nextVideo(id: String): Video? {
        val i = videoOrder.indexOfFirst { it.id == id }
        return if (i >= 0) videoOrder.getOrNull(i + 1) else null
    }

    fun nextMusicFolder(path: String): String? {
        val i = musicOrder.indexOf(path)
        return if (i >= 0) musicOrder.getOrNull(i + 1) else null
    }

    fun prevMusicFolder(path: String): String? {
        val i = musicOrder.indexOf(path)
        return if (i > 0) musicOrder[i - 1] else null
    }

    private fun parseVideos(arr: JSONArray): List<Video> = (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        val s = o.optJSONArray("subs") ?: JSONArray()
        Video(
            o.getString("id"), o.optString("title"), o.optString("file"), o.optString("folder").replace('\\', '/'), o.optLong("size"),
            (0 until s.length()).map {
                val x = s.getJSONObject(it)
                SubFile(x.getString("id"), x.optString("label", "Subtitles"), x.optString("lang", "und"))
            },
        )
    }

    private fun parseFolders(arr: JSONArray): List<MusicFolder> = (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        val f = o.optJSONArray("files") ?: JSONArray()
        MusicFolder(o.optString("path"), o.optInt("count"), (0 until f.length()).map { f.getString(it) })
    }

    private fun child(parent: Node, name: String, music: Boolean): Node {
        parent.folders.firstOrNull { it.name == name }?.let { return it }
        val path = if (parent.path.isEmpty()) name else "${parent.path}/$name"
        val n = Node((if (music) "m:" else "v:") + path, name, path, music)
        parent.folders += n
        return n
    }

    private fun buildVideoTree(list: List<Video>): Node {
        val root = Node("v:", "Video", "", false)
        for (v in list) {
            var n = root
            root.count++
            if (v.folder.isNotEmpty()) for (part in v.folder.split('/')) {
                n = child(n, part, false)
                n.count++
            }
            n.videos += v
        }
        sortTree(root)
        return root
    }

    private fun buildMusicTree(list: List<MusicFolder>): Node {
        val root = Node("m:", "Music", "", true)
        for (f in list) {
            var n = root
            root.count += f.count
            if (f.path.isNotEmpty()) for (part in f.path.split('/')) {
                n = child(n, part, true)
                n.count += f.count
            }
            n.hasTracks = true
        }
        sortTree(root)
        return root
    }

    private fun sortTree(n: Node) {
        n.folders.sortWith { a, b -> natural.compare(a.name, b.name) }
        n.folders.forEach { sortTree(it) }
    }

    private fun collectVideos(n: Node, out: MutableList<Video>) {
        n.folders.forEach { collectVideos(it, out) }
        out += n.videos
    }

    private fun collectMusic(n: Node, out: MutableList<String>) {
        n.folders.forEach { collectMusic(it, out) }
        if (n.hasTracks) out += n.path
    }

    /** "Episode 2" before "Episode 10" */
    val natural = Comparator<String> { a, b ->
        val re = Regex("\\d+|\\D+")
        val pa = re.findAll(a.lowercase(Locale.ROOT)).map { it.value }.toList()
        val pb = re.findAll(b.lowercase(Locale.ROOT)).map { it.value }.toList()
        for (i in 0 until minOf(pa.size, pb.size)) {
            val x = pa[i]
            val y = pb[i]
            val c = if (x[0].isDigit() && y[0].isDigit()) {
                val xs = x.trimStart('0')
                val ys = y.trimStart('0')
                if (xs.length != ys.length) xs.length.compareTo(ys.length) else xs.compareTo(ys)
            } else x.compareTo(y)
            if (c != 0) return@Comparator c
        }
        pa.size.compareTo(pb.size)
    }
}

// ---------- recently played (last 5) ----------

object Recent {
    private const val MAX = 5

    fun all(): List<RecentItem> = try {
        val arr = JSONArray(Prefs.recentJson)
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            RecentItem(o.getString("kind"), o.getString("id"), o.optString("title"), o.optString("subtitle"), o.optString("folder"),
                o.optDouble("pos", 0.0), o.optDouble("dur", 0.0), o.optLong("at"))
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun save(list: List<RecentItem>) {
        val arr = JSONArray()
        list.take(MAX).forEach {
            arr.put(JSONObject().put("kind", it.kind).put("id", it.id).put("title", it.title).put("subtitle", it.subtitle)
                .put("folder", it.folder).put("pos", it.pos).put("dur", it.dur).put("at", it.at))
        }
        Prefs.recentJson = arr.toString()
        Library.bump()
    }

    fun get(id: String) = all().firstOrNull { it.id == id }

    /** Playback of [item] started: move it to the top. */
    fun started(item: RecentItem) {
        val old = get(item.id)
        val keep = if (old != null && item.pos == 0.0) item.copy(pos = old.pos) else item
        save(listOf(keep.copy(at = System.currentTimeMillis())) + all().filter { it.id != item.id })
    }

    fun position(id: String, pos: Double, dur: Double) {
        val list = all()
        if (list.none { it.id == id }) return
        save(list.map { if (it.id == id) it.copy(pos = pos, dur = if (dur > 0) dur else it.dur) else it })
    }

    fun clear() = save(emptyList())

    /** where to continue: a few seconds back, nothing if almost finished */
    fun resumeAt(id: String): Double {
        val r = get(id) ?: return 0.0
        if (r.pos < 5 || (r.dur > 0 && r.pos > r.dur - 10)) return 0.0
        return r.pos - 3
    }
}

fun fmtTime(sec: Double): String {
    val s = sec.toLong().coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val x = s % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, x) else String.format(Locale.US, "%d:%02d", m, x)
}

fun fmtSize(b: Long): String =
    if (b >= (1L shl 30)) String.format(Locale.US, "%.1f GB", b / (1L shl 30).toDouble()) else "${b / (1L shl 20)} MB"

fun fmtBitrate(bps: Double): String =
    if (bps >= 1_000_000) String.format(Locale.US, "%.1f Mbps", bps / 1_000_000) else "${(bps / 1000).toInt()} kbps"
