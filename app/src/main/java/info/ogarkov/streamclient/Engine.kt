package info.ogarkov.streamclient

import android.app.Application
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Conn.init(this)
        Engine.init(this)
    }
}

/**
 * The one player of the app, shared by the video screen, the music screens and the media session
 * (notification, lock screen, headset buttons). Music is a playlist: when it reaches the last tracks
 * of a folder, the next folder of the tree is appended, so playback goes on folder after folder.
 */
@OptIn(UnstableApi::class)
object Engine {
    lateinit var player: ExoPlayer
        private set
    lateinit var dataSourceFactory: OkHttpDataSource.Factory
        private set

    /** [player] with fade-out on pause: give this to the UI and the media session */
    lateinit var control: FadingPlayer
        private set

    /** true while the playlist holds music tracks (false: a video or nothing) */
    @Volatile var audioMode = false
        private set

    private val trackById = HashMap<String, Track>()
    private val qById = HashMap<String, String>()
    private var lastFolder = "" // the folder whose tracks were appended last
    private var appending = false
    private var errorRetries = 0

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var controller: ListenableFuture<MediaController>? = null

    /** bumped when the current track or play/pause state changes */
    val changes = MutableStateFlow(0)
    private fun bump() {
        changes.value = changes.value + 1
    }

    fun init(ctx: Context) {
        dataSourceFactory = OkHttpDataSource.Factory(Api.http)
        player = ExoPlayer.Builder(ctx)
            .setRenderersFactory(DefaultRenderersFactory(ctx).setEnableDecoderFallback(true))
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(dataSourceFactory)
                    // ride out a switch between the local and the global address
                    .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(12)),
            )
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.addListener(listener)
        control = FadingPlayer(player)
        main.postDelayed(tick, 5000)
    }

    /** Bind the media session service (notification / lock screen controls). */
    fun connectService(ctx: Context) {
        if (controller != null) return
        val token = SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
        controller = MediaController.Builder(ctx.applicationContext, token).buildAsync()
    }

    fun track(id: String?): Track? = id?.let { trackById[it] }
    fun currentTrack(): Track? = if (audioMode) track(player.currentMediaItem?.mediaId) else null
    fun quality(id: String?): String = qById[id] ?: "orig"

    // ---------- music ----------

    private fun item(t: Track): MediaItem {
        val q = if (Conn.isLocal) "orig" else "eco"
        trackById[t.id] = t
        qById[t.id] = q
        val meta = MediaMetadata.Builder()
            .setTitle(t.title)
            .setArtist(t.artist.ifBlank { null })
            .setAlbumTitle(t.album.ifBlank { null })
            .setArtworkUri(Uri.parse(Covers.url(t.folder, 600)))
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .build()
        return MediaItem.Builder()
            .setMediaId(t.id)
            .setUri(Api.url("/api/music/stream/${t.id}", "q" to q))
            .setMediaMetadata(meta)
            .build()
    }

    fun playMusic(folder: String, tracks: List<Track>, index: Int, startSec: Double) {
        if (tracks.isEmpty()) return
        audioMode = true
        errorRetries = 0
        lastFolder = folder
        appending = false
        player.setMediaItems(tracks.map { item(it) }, index.coerceIn(0, tracks.size - 1), (startSec * 1000).toLong())
        player.prepare()
        control.play()
    }

    /** Start playing at the track with [trackId] in [folder] (or the first track of [file]). */
    suspend fun playFromFolder(folder: String, trackId: String?, file: String?, startSec: Double): Boolean {
        val list = Library.tracks(folder)
        var i = if (trackId != null) list.indexOfFirst { it.id == trackId } else -1
        if (i < 0 && file != null) i = list.indexOfFirst { it.file == file }
        if (i < 0) {
            if (trackId != null || file != null) return false
            i = 0
        }
        playMusic(folder, list, i, startSec)
        return true
    }

    fun stopAll() {
        control.stop()
        player.clearMediaItems()
        audioMode = false
        bump()
    }

    /** a video takes over the player */
    fun startVideo() {
        if (audioMode) saveAudioPosition()
        audioMode = false
        control.stop()
        player.clearMediaItems()
        bump()
    }

    private fun saveAudioPosition() {
        val t = currentTrack() ?: return
        Recent.position(t.id, player.currentPosition / 1000.0, t.duration)
    }

    private fun onTrackStarted() {
        val t = currentTrack() ?: return
        Recent.started(RecentItem("audio", t.id, t.title, listOf(t.artist, t.album).filter { it.isNotBlank() }.joinToString(" — "),
            t.folder, player.currentPosition / 1000.0, t.duration, System.currentTimeMillis()))
        // let the server convert / cut the next track in advance
        val next = player.currentMediaItemIndex + 1
        if (next < player.mediaItemCount) {
            val id = player.getMediaItemAt(next).mediaId
            Api.ping(Api.url("/api/music/prepare/$id", "q" to quality(id)))
        }
        appendIfNeeded()
    }

    /** Near the end of the playlist: append the next folder of the tree. */
    private fun appendIfNeeded() {
        if (!audioMode || appending) return
        if (player.currentMediaItemIndex < player.mediaItemCount - 2) return
        appending = true
        scope.launch {
            try {
                var folder = lastFolder
                // skip folders that turn out to be empty / unreadable
                for (attempt in 0 until 20) {
                    folder = Library.nextMusicFolder(folder) ?: break
                    val list = try { Library.tracks(folder) } catch (e: Exception) { emptyList() }
                    if (list.isEmpty()) continue
                    if (!audioMode) return@launch
                    val wasEnded = player.playbackState == Player.STATE_ENDED
                    val first = player.mediaItemCount
                    player.addMediaItems(list.map { item(it) })
                    lastFolder = folder
                    if (wasEnded) {
                        player.seekTo(first, 0)
                        player.prepare()
                        control.play()
                    }
                    break
                }
            } finally {
                appending = false
            }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (audioMode && player.isPlaying) saveAudioPosition()
            main.postDelayed(this, 5000)
        }
    }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (audioMode) {
                errorRetries = 0
                onTrackStarted()
            }
            bump()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (audioMode && !isPlaying) saveAudioPosition()
            bump()
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (audioMode && state == Player.STATE_ENDED) appendIfNeeded()
            bump()
        }

        override fun onPlayerError(error: PlaybackException) {
            if (!audioMode) return // the video screen handles its own errors
            val network = error.errorCode in 2000..2999
            if (network && errorRetries < 6) {
                errorRetries++
                Conn.recheck()
                main.postDelayed({
                    player.prepare()
                    control.play()
                }, 3000)
            } else if (player.hasNextMediaItem()) {
                // a broken file: go on with the next one
                errorRetries = 0
                player.seekToNextMediaItem()
                player.prepare()
                control.play()
            }
            bump()
        }
    }
}

/** Media session: notification, lock screen and headset controls; keeps music going in the background. */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, NowPlayingActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, Engine.control)
            .setSessionActivity(open)
            // album covers come from the server: load them with the app's own HTTP client
            .setBitmapLoader(DataSourceBitmapLoader(MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor()), Engine.dataSourceFactory))
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = Engine.player
        if (!p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.release()
        session = null
        super.onDestroy()
    }
}

/** Album covers (/api/music/cover), kept in memory. */
object Covers {
    private val cache = LruCache<String, Bitmap>(40)
    private val missing = HashSet<String>()

    fun url(folder: String, size: Int) = Api.url("/api/music/cover", "path" to folder, "s" to size.toString())

    fun cached(folder: String, size: Int): Bitmap? = cache.get("$size:$folder")

    suspend fun get(folder: String, size: Int): Bitmap? {
        val key = "$size:$folder"
        cache.get(key)?.let { return it }
        if (synchronized(missing) { key in missing }) return null
        val bytes = Api.bytes(url(folder, size))
        val bmp = bytes?.let { withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(it, 0, it.size) } }
        if (bmp != null) cache.put(key, bmp) else synchronized(missing) { missing += key }
        return bmp
    }
}
