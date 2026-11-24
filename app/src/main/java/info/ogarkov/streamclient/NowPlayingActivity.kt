package info.ogarkov.streamclient

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.Format
import kotlinx.coroutines.launch

/** Music player screen: album cover, track, file name, format and bitrate. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class NowPlayingActivity : AppCompatActivity() {
    private lateinit var cover: ImageView
    private lateinit var title: TextView
    private lateinit var artist: TextView
    private lateinit var file: TextView
    private lateinit var tech: TextView
    private lateinit var folder: TextView
    private lateinit var seek: SeekBar
    private lateinit var pos: TextView
    private lateinit var dur: TextView
    private lateinit var play: ImageButton

    private var coverFolder: String? = null
    private var dragging = false
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            updatePosition()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_now_playing)
        applySystemBarPadding(findViewById(R.id.root))
        Engine.connectService(this)

        cover = findViewById(R.id.cover)
        title = findViewById(R.id.title)
        artist = findViewById(R.id.artist)
        file = findViewById(R.id.file)
        tech = findViewById(R.id.tech)
        folder = findViewById(R.id.folder)
        seek = findViewById(R.id.seek)
        pos = findViewById(R.id.pos)
        dur = findViewById(R.id.dur)
        play = findViewById(R.id.play)

        val p = Engine.player
        play.setOnClickListener { if (p.playWhenReady) p.pause() else p.play() }
        findViewById<ImageButton>(R.id.next).setOnClickListener { if (p.hasNextMediaItem()) p.seekToNextMediaItem() }
        findViewById<ImageButton>(R.id.prev).setOnClickListener {
            // like most players: restart the track unless it has just begun
            if (p.currentPosition > 3000 || !p.hasPreviousMediaItem()) p.seekTo(0) else p.seekToPreviousMediaItem()
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) pos.text = fmtTime(progress.toDouble())
            }
            override fun onStartTrackingTouch(s: SeekBar) {
                dragging = true
            }
            override fun onStopTrackingTouch(s: SeekBar) {
                dragging = false
                p.seekTo(s.progress * 1000L)
            }
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Engine.changes.collect { update() }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        handler.post(tick)
    }

    override fun onStop() {
        handler.removeCallbacks(tick)
        super.onStop()
    }

    private fun update() {
        val t = Engine.currentTrack()
        if (t == null) {
            title.text = "Nothing is playing"
            artist.text = ""
            file.text = ""
            tech.text = ""
            folder.text = ""
            cover.setImageResource(R.drawable.ic_album)
            coverFolder = null
            return
        }
        val p = Engine.player
        title.text = t.title
        artist.text = listOf(t.artist, t.album).filter { it.isNotBlank() }.joinToString(" — ")
        file.text = t.file
        folder.text = t.folder
        play.setImageResource(if (p.playWhenReady) R.drawable.ic_pause else R.drawable.ic_play)
        tech.text = techLine(t, p.audioFormat)

        if (coverFolder != t.folder) {
            coverFolder = t.folder
            cover.setImageResource(R.drawable.ic_album)
            val f = t.folder
            lifecycleScope.launch {
                val bmp = Covers.get(f, 800)
                if (bmp != null && coverFolder == f) cover.setImageBitmap(bmp)
            }
        }
        updatePosition()
    }

    /** "FLAC audio · FLAC 16/44.1 · 912 kbps · original" */
    private fun techLine(t: Track, f: Format?): String {
        val parts = mutableListOf("${t.format.uppercase()} audio")
        if (t.quality.isNotBlank()) parts += t.quality
        val br = f?.let { if (it.averageBitrate > 0) it.averageBitrate else it.bitrate } ?: Format.NO_VALUE
        if (br > 0) parts += fmtBitrate(br.toDouble())
        val delivered = if (Engine.quality(t.id) == "eco") t.deliverEco else t.deliverOrig
        parts += if (delivered == null) "original file" else "streamed as $delivered"
        return parts.joinToString(" · ")
    }

    private fun updatePosition() {
        val p = Engine.player
        val t = Engine.currentTrack() ?: return
        val d = if (p.duration > 0) p.duration / 1000.0 else t.duration
        seek.max = d.toInt().coerceAtLeast(1)
        dur.text = fmtTime(d)
        if (!dragging) {
            val x = p.currentPosition / 1000.0
            seek.progress = x.toInt()
            pos.text = fmtTime(x)
        }
        // the decoder's format is known only once playback started
        tech.text = techLine(t, p.audioFormat)
    }
}
