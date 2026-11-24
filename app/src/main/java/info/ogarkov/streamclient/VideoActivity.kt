package info.ogarkov.streamclient

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Video player. First the phone plays the original file (byte ranges, seekable; its decoders handle
 * HEVC / 10-bit / MKV and the embedded tracks). If it can't decode a track, playback switches to the
 * server's ffmpeg stream (/api/transcode), which can't seek by itself: seeking restarts the stream
 * at a new position, so this mode has its own controls with timeline = stream offset + position.
 * At the end the next video of the tree starts (folder after folder).
 */
@OptIn(UnstableApi::class)
class VideoActivity : AppCompatActivity() {
    private enum class Mode { DIRECT, STREAM }

    private val player: ExoPlayer get() = Engine.player
    private lateinit var playerView: PlayerView
    private lateinit var tap: View
    private lateinit var controls: View
    private lateinit var streamTitle: TextView
    private lateinit var infoBar: View
    private lateinit var infoText: TextView
    private lateinit var playPause: ImageButton
    private lateinit var seek: SeekBar
    private lateinit var posText: TextView
    private lateinit var durText: TextView
    private lateinit var badge: TextView
    private lateinit var message: TextView

    private var video: Video? = null
    private var info: ProbeInfo? = null
    private var mode = Mode.DIRECT
    private var loadJob: Job? = null

    // direct mode
    private var tracksChecked = false
    private var directRetries = 0

    // is a picture really shown? (some decoders accept a format and then render nothing)
    private var firstFrame = false
    private var decoderName: String? = null
    private val noPictureCheck = Runnable { checkPicture() }

    // stream mode
    private var offsetSec = 0.0
    private var audioIdx = 0
    private var subKey: String? = null // "ext:<id>" | "emb:<n>" | null
    private var nonce = 0
    private var streamJob: Job? = null
    private var streamRetries = 0
    private var lastRecoverAt = 0L
    private var dragging = false

    private val handler = Handler(Looper.getMainLooper())
    private var lastSave = 0L
    private val tick = object : Runnable {
        override fun run() {
            updateControls()
            val now = System.currentTimeMillis()
            if (player.isPlaying && now - lastSave > 5000) {
                lastSave = now
                savePosition()
            }
            handler.postDelayed(this, 500)
        }
    }
    private val hideControls = Runnable { if (player.isPlaying) controls.visibility = View.GONE }
    private val hideBadge = Runnable { badge.visibility = View.GONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()

        playerView = findViewById(R.id.playerView)
        tap = findViewById(R.id.tap)
        controls = findViewById(R.id.streamControls)
        streamTitle = findViewById(R.id.streamTitle)
        infoBar = findViewById(R.id.infoBar)
        infoText = findViewById(R.id.info)
        findViewById<ImageButton>(R.id.back).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.streamBack).setOnClickListener { finish() }
        playPause = findViewById(R.id.playPause)
        seek = findViewById(R.id.seek)
        posText = findViewById(R.id.pos)
        durText = findViewById(R.id.dur)
        badge = findViewById(R.id.badge)
        message = findViewById(R.id.message)

        Engine.startVideo()
        player.addListener(listener)
        player.addAnalyticsListener(analytics)
        playerView.player = player
        playerView.setShowSubtitleButton(true)
        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
            if (mode == Mode.DIRECT) infoBar.visibility = visibility
        })

        tap.setOnClickListener { showControls() }
        controls.setOnClickListener { controls.visibility = View.GONE }
        playPause.setOnClickListener {
            if (player.isPlaying) player.pause() else player.play()
            showControls()
        }
        findViewById<Button>(R.id.audioBtn).setOnClickListener { chooseAudio() }
        findViewById<Button>(R.id.subsBtn).setOnClickListener { chooseSubs() }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) posText.text = fmtTime(p.toDouble())
            }
            override fun onStartTrackingTouch(s: SeekBar) {
                dragging = true
                handler.removeCallbacks(hideControls)
            }
            override fun onStopTrackingTouch(s: SeekBar) {
                dragging = false
                playStream(s.progress.toDouble())
                showControls()
            }
        })

        val id = intent.getStringExtra("id") ?: return finish()
        open(id)
        handler.post(tick)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onStop() {
        super.onStop()
        savePosition()
        player.pause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        savePosition()
        player.removeListener(listener)
        player.removeAnalyticsListener(analytics)
        playerView.player = null
        if (!Engine.audioMode) {
            player.stop()
            player.clearMediaItems()
        }
        super.onDestroy()
    }

    private fun hideSystemBars() {
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
    }

    /** Load a video (also used for "next video" without leaving the screen). */
    private fun open(id: String) {
        loadJob?.cancel()
        streamJob?.cancel()
        player.stop()
        showMessage("Loading…")
        loadJob = lifecycleScope.launch {
            try {
                if (!Library.loaded) Library.load(false)
                val v = Library.video(id) ?: throw Exception("Video not found — rescan the library")
                video = v
                info = null
                info = try { probe(id) } catch (e: Exception) { null }
                Recent.started(RecentItem("video", v.id, v.title, v.file, v.folder, 0.0, info?.duration ?: 0.0, System.currentTimeMillis()))
                playDirect(Recent.resumeAt(v.id))
            } catch (e: Exception) {
                showMessage(e.message ?: e.toString())
            }
        }
    }

    private suspend fun probe(id: String): ProbeInfo {
        val o = JSONObject(Api.text(Api.url("/api/probe/$id")))
        val a = o.optJSONArray("audio") ?: JSONArray()
        val s = o.optJSONArray("subs") ?: JSONArray()
        val m = o.optJSONArray("modes") ?: JSONArray()
        val vo = o.optJSONObject("video")
        return ProbeInfo(
            duration = o.optDouble("duration", 0.0),
            video = vo?.let { VideoStream(it.optString("codec"), it.optInt("width"), it.optInt("height")) },
            audio = (0 until a.length()).map {
                val t = a.getJSONObject(it)
                AudioTrack(t.optInt("index", it), t.optString("codec"), t.optInt("channels"), t.optString("lang"), t.optString("title"), t.optBoolean("default"))
            },
            subs = (0 until s.length()).map {
                val t = s.getJSONObject(it)
                EmbeddedSub(t.optInt("index", it), t.optString("codec"), t.optString("lang"), t.optString("title"))
            },
            modes = (0 until m.length()).map { m.getString(it) },
        )
    }

    // ---------- position ----------

    private fun positionSec(): Double =
        (if (mode == Mode.STREAM) offsetSec else 0.0) + player.currentPosition.coerceAtLeast(0) / 1000.0

    private fun durationSec(): Double {
        val d = info?.duration ?: 0.0
        if (d > 0) return d
        val p = player.duration
        return if (p == C.TIME_UNSET || p <= 0) 0.0 else p / 1000.0
    }

    private fun savePosition() {
        val v = video ?: return
        if (player.currentMediaItem == null) return
        Recent.position(v.id, positionSec(), durationSec())
    }

    // ---------- "file name, type, bitrate" ----------

    private fun infoText(): String {
        val v = video ?: return ""
        val parts = mutableListOf(v.file.substringAfterLast('.', "").uppercase() + " video")
        info?.video?.let { parts += "${it.codec.uppercase()} ${it.width}×${it.height}" }
        info?.audio?.getOrNull(if (mode == Mode.STREAM) audioIdx else 0)?.let {
            parts += it.codec.uppercase() + if (it.channels > 0) " ${channelsLabel(it.channels)}" else ""
        }
        val d = durationSec()
        if (d > 0 && v.size > 0) parts += fmtBitrate(v.size * 8 / d)
        decoderName?.let { parts += "decoder ${it.removePrefix("c2.").removePrefix("OMX.")}" + if (firstFrame) "" else " (no picture yet)" }
        parts += when {
            mode == Mode.DIRECT -> "original file"
            info?.mode(audioIdx) == "audio" -> "server: audio transcoding"
            else -> "server: video transcoding"
        }
        return "${v.file}\n${parts.joinToString(" · ")}"
    }

    private fun refreshInfo() {
        val text = infoText()
        infoText.text = text
        streamTitle.text = text
    }

    // ---------- direct playback ----------

    private fun lang(code: String?): String? =
        code?.takeIf { it.isNotBlank() && it != "und" }?.let { Util.normalizeLanguageCode(it) ?: it }

    private fun playDirect(atSec: Double) {
        val v = video ?: return
        mode = Mode.DIRECT
        tracksChecked = false
        firstFrame = false
        decoderName = null
        handler.removeCallbacks(noPictureCheck)
        tap.visibility = View.GONE
        controls.visibility = View.GONE
        playerView.useController = true
        hideMessage()
        refreshInfo()

        val subs = v.subs.map {
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(Api.url("/api/subtitle/${it.id}")))
                .setMimeType(MimeTypes.TEXT_VTT)
                .setLanguage(lang(it.lang))
                .setLabel(it.label)
                .build()
        }
        val params = player.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguage(lang(Prefs.audioLang))
        when (Prefs.subLang) {
            "", "off" -> params.setPreferredTextLanguage(null).setIgnoredTextSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            else -> params.setPreferredTextLanguage(lang(Prefs.subLang)).setIgnoredTextSelectionFlags(0)
        }
        player.trackSelectionParameters = params.build()

        val item = MediaItem.Builder()
            .setUri(Api.url("/api/stream/${v.id}"))
            .setMediaMetadata(MediaMetadata.Builder().setTitle(v.title).setMediaType(MediaMetadata.MEDIA_TYPE_VIDEO).build())
            .setSubtitleConfigurations(subs)
            .build()
        player.setMediaItem(item, (atSec * 1000).toLong())
        player.prepare()
        player.play()
    }

    /** Can the phone decode at least one audio and one video track of the file? */
    private fun checkTracks(tracks: Tracks) {
        if (mode != Mode.DIRECT || tracksChecked || tracks.groups.isEmpty()) return
        tracksChecked = true
        fun supported(type: Int): Boolean {
            val groups = tracks.groups.filter { it.type == type }
            return groups.isEmpty() || groups.any { g -> (0 until g.length).any { g.isTrackSupported(it) } }
        }
        val videoOk = supported(C.TRACK_TYPE_VIDEO)
        val audioOk = supported(C.TRACK_TYPE_AUDIO)
        if (!videoOk || !audioOk) {
            val type = if (!videoOk) C.TRACK_TYPE_VIDEO else C.TRACK_TYPE_AUDIO
            val codec = tracks.groups.firstOrNull { it.type == type }?.getTrackFormat(0)?.sampleMimeType?.substringAfter('/') ?: ""
            showBadge("This phone can't decode ${if (!videoOk) "video" else "audio"} $codec — the server will convert it")
            switchToStream(positionSec())
        }
    }

    /**
     * Sound but a black screen: the phone's decoder took the video track but renders nothing
     * (typical for some Xvid/DivX and exotic HEVC files). Hand the file to the server instead.
     */
    private fun checkPicture() {
        if (firstFrame || !player.isPlaying || info?.video == null) return
        val hasVideo = player.currentTracks.groups.any { it.type == C.TRACK_TYPE_VIDEO }
        if (!hasVideo) return
        if (mode == Mode.DIRECT) {
            showBadge("No picture from the phone's decoder — the server will convert the video")
            switchToStream(positionSec())
        } else {
            refreshInfo()
            showBadge("No picture yet: " + (decoderName ?: "no video decoder"))
        }
    }

    /** Remember the audio / subtitle choice for the next videos. */
    private fun rememberSelection(tracks: Tracks) {
        if (mode != Mode.DIRECT || !tracksChecked) return
        var textSelected = false
        var hasText = false
        for (g in tracks.groups) {
            if (g.type == C.TRACK_TYPE_TEXT) hasText = true
            if (!g.isSelected) continue
            for (i in 0 until g.length) {
                if (!g.isTrackSelected(i)) continue
                val l: String? = g.getTrackFormat(i).language
                when (g.type) {
                    C.TRACK_TYPE_AUDIO -> if (!l.isNullOrBlank()) Prefs.audioLang = l
                    C.TRACK_TYPE_TEXT -> {
                        textSelected = true
                        Prefs.subLang = if (l.isNullOrBlank()) "und" else l
                    }
                }
            }
        }
        if (hasText && !textSelected && Prefs.subLang.isNotEmpty()) Prefs.subLang = "off"
    }

    // ---------- server stream ----------

    private fun switchToStream(atSec: Double) {
        val inf = info
        if (inf == null) {
            showMessage("Can't play this file")
            return
        }
        mode = Mode.STREAM
        player.stop()
        playerView.useController = false
        infoBar.visibility = View.GONE
        tap.visibility = View.VISIBLE

        val prefAudio = lang(Prefs.audioLang)
        audioIdx = inf.audio.indexOfFirst { prefAudio != null && lang(it.lang) == prefAudio }
            .takeIf { it >= 0 } ?: inf.audio.indexOfFirst { it.isDefault }.coerceAtLeast(0)

        val prefSub = Prefs.subLang
        subKey = null
        if (prefSub.isNotEmpty() && prefSub != "off") {
            val l = lang(prefSub)
            val ext = video?.subs?.firstOrNull { lang(it.lang) == l }
            val emb = inf.subs.firstOrNull { lang(it.lang) == l }
            subKey = when {
                ext != null -> "ext:${ext.id}"
                emb != null -> "emb:${emb.index}"
                else -> null
            }
        }
        playStream(atSec)
        showControls()
    }

    private fun playStream(atSec: Double) {
        val v = video ?: return
        val inf = info ?: return
        streamJob?.cancel()
        streamJob = lifecycleScope.launch {
            showMessage("Preparing the stream…")
            val at = atSec.coerceIn(0.0, (inf.duration - 1).coerceAtLeast(0.0))
            val (start, ss) = if (at > 0) seekPoint(v.id, at) else 0.0 to 0.0
            offsetSec = start
            nonce++
            firstFrame = false
            handler.removeCallbacks(noPictureCheck)
            val url = Api.url("/api/transcode/${v.id}", "start" to f3(start), "ss" to f3(ss), "audio" to audioIdx.toString(), "n" to nonce.toString())
            val builder = MediaItem.Builder()
                .setUri(url)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(v.title).setMediaType(MediaMetadata.MEDIA_TYPE_VIDEO).build())
            subtitleFor(v, start)?.let { builder.setSubtitleConfigurations(listOf(it)) }
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setPreferredTextLanguage(null)
                .setIgnoredTextSelectionFlags(0)
                .build()
            refreshInfo()
            seek.max = inf.duration.toInt().coerceAtLeast(1)
            durText.text = fmtTime(inf.duration)
            player.setMediaItem(builder.build())
            player.prepare()
            player.play()
        }
    }

    /** Where a stream will really start (keyframe), see /api/seekpoint. */
    private suspend fun seekPoint(id: String, t: Double): Pair<Double, Double> = try {
        val o = JSONObject(Api.text(Api.url("/api/seekpoint/$id", "t" to f3(t), "audio" to audioIdx.toString())))
        o.optDouble("start", t) to o.optDouble("ss", t)
    } catch (e: Exception) {
        t to t
    }

    private fun f3(x: Double) = String.format(Locale.US, "%.3f", x)

    private fun subtitleFor(v: Video, offset: Double): MediaItem.SubtitleConfiguration? {
        val key = subKey ?: return null
        val off = String.format(Locale.US, "%.1f", offset)
        val uri: String
        val l: String?
        if (key.startsWith("ext:")) {
            val id = key.removePrefix("ext:")
            uri = Api.url("/api/subtitle/$id", "offset" to off)
            l = v.subs.firstOrNull { it.id == id }?.lang
        } else {
            val n = key.removePrefix("emb:")
            uri = Api.url("/api/subtitle-embedded/${v.id}/$n", "offset" to off)
            l = info?.subs?.firstOrNull { it.index.toString() == n }?.lang
        }
        return MediaItem.SubtitleConfiguration.Builder(Uri.parse(uri))
            .setMimeType(MimeTypes.TEXT_VTT)
            .setLanguage(lang(l))
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT or C.SELECTION_FLAG_FORCED)
            .build()
    }

    /** Dropped connection, server restart or address switch: continue from where we are. */
    private fun recoverStream() {
        val now = System.currentTimeMillis()
        if (now - lastRecoverAt > 60_000) streamRetries = 0
        lastRecoverAt = now
        if (++streamRetries > 5) {
            showMessage("The stream keeps breaking. Try again later or seek manually.")
            return
        }
        Conn.recheck()
        val at = positionSec()
        handler.postDelayed({ playStream(at) }, 2000)
    }

    private fun chooseAudio() {
        val inf = info ?: return
        if (inf.audio.size < 2) {
            Toast.makeText(this, "This file has one audio track", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Audio")
            .setSingleChoiceItems(inf.audio.map { it.label() }.toTypedArray(), audioIdx) { d, i ->
                d.dismiss()
                if (i == audioIdx) return@setSingleChoiceItems
                val pos = positionSec()
                audioIdx = i
                inf.audio[i].lang.takeIf { it.isNotBlank() }?.let { Prefs.audioLang = it }
                playStream(pos)
            }
            .show()
    }

    private fun chooseSubs() {
        val v = video ?: return
        val inf = info ?: return
        val keys = mutableListOf<String?>(null)
        val labels = mutableListOf("Off")
        val langs = mutableListOf("off")
        v.subs.forEach {
            keys += "ext:${it.id}"
            labels += "${it.label} (file)"
            langs += it.lang
        }
        inf.subs.forEach {
            keys += "emb:${it.index}"
            labels += listOf(it.title, it.lang).filter { s -> s.isNotBlank() }.joinToString(" · ").ifBlank { "Track ${it.index + 1}" }
            langs += it.lang.ifBlank { "und" }
        }
        if (keys.size == 1) {
            Toast.makeText(this, "No subtitles", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Subtitles")
            .setSingleChoiceItems(labels.toTypedArray(), keys.indexOf(subKey).coerceAtLeast(0)) { d, i ->
                d.dismiss()
                if (keys[i] == subKey) return@setSingleChoiceItems
                subKey = keys[i]
                Prefs.subLang = langs[i]
                if (subKey?.startsWith("emb:") == true) showBadge("Extracting embedded subtitles — up to a minute the first time")
                playStream(positionSec())
            }
            .show()
    }

    // ---------- UI ----------

    private fun showControls() {
        if (mode != Mode.STREAM) return
        controls.visibility = View.VISIBLE
        updateControls()
        handler.removeCallbacks(hideControls)
        handler.postDelayed(hideControls, 4000)
    }

    private fun updateControls() {
        if (mode != Mode.STREAM || controls.visibility != View.VISIBLE) return
        playPause.setImageResource(if (player.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        if (!dragging) {
            val p = positionSec()
            seek.progress = p.toInt()
            posText.text = fmtTime(p)
        }
    }

    private fun showMessage(text: String) {
        message.text = text
        message.visibility = View.VISIBLE
    }

    private fun hideMessage() {
        message.visibility = View.GONE
    }

    private fun showBadge(text: String) {
        badge.text = text
        badge.visibility = View.VISIBLE
        handler.removeCallbacks(hideBadge)
        handler.postDelayed(hideBadge, 5000)
    }

    /** Continuous playback: the next video of the tree, folder after folder. */
    private fun playNext() {
        val v = video ?: return finish()
        Recent.position(v.id, durationSec(), durationSec())
        val next = Library.nextVideo(v.id)
        if (next == null) {
            finish()
            return
        }
        Toast.makeText(this, "Next: ${next.file}", Toast.LENGTH_SHORT).show()
        directRetries = 0
        streamRetries = 0
        open(next.id)
    }

    private val analytics = object : AnalyticsListener {
        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            this@VideoActivity.decoderName = decoderName
            refreshInfo()
        }
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (Engine.audioMode) return
            when (state) {
                Player.STATE_READY -> {
                    hideMessage()
                    if (mode == Mode.DIRECT) directRetries = 0
                    if (!firstFrame) {
                        handler.removeCallbacks(noPictureCheck)
                        handler.postDelayed(noPictureCheck, 6000)
                    }
                }
                Player.STATE_ENDED -> {
                    // a cut-off server stream also "ends": only a real end moves on
                    val d = durationSec()
                    if (mode == Mode.STREAM && d > 0 && positionSec() < d - 15) recoverStream() else playNext()
                }
                else -> {}
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updateControls()
            if (!isPlaying) savePosition()
        }

        override fun onRenderedFirstFrame() {
            firstFrame = true
            handler.removeCallbacks(noPictureCheck)
            refreshInfo()
        }

        override fun onTracksChanged(tracks: Tracks) {
            checkTracks(tracks)
            rememberSelection(tracks)
        }

        override fun onPlayerError(error: PlaybackException) {
            if (Engine.audioMode) return
            val network = error.errorCode in 2000..2999
            when {
                mode == Mode.STREAM -> recoverStream()
                network && directRetries < 5 -> {
                    // e.g. left the Wi-Fi: the next request goes to the other address
                    directRetries++
                    Conn.recheck()
                    val at = positionSec()
                    showMessage("Reconnecting…")
                    handler.postDelayed({ playDirect(at) }, 2000)
                }
                !network -> {
                    showBadge("The phone can't open this file — the server will convert it")
                    switchToStream(positionSec())
                }
                else -> showMessage("No connection to the server (${error.errorCodeName})")
            }
        }
    }
}
