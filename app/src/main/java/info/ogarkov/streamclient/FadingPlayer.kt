package info.ogarkov.streamclient

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

/**
 * The player as the UI and the media session see it: a pause fades the sound out first.
 * Covers every way to pause: app buttons, the video controller, the notification, the lock screen,
 * headset buttons. Unplugging headphones still pauses at once (ExoPlayer does that internally).
 */
@OptIn(UnstableApi::class)
class FadingPlayer(private val exo: ExoPlayer) : ForwardingPlayer(exo) {
    private val handler = Handler(Looper.getMainLooper())
    private var fade: Runnable? = null

    override fun pause() = fadeOutAndPause()

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (playWhenReady) play() else fadeOutAndPause()
    }

    override fun play() {
        cancelFade()
        exo.volume = 1f
        super.play()
    }

    // the play/pause button flips as soon as the fade starts
    override fun getPlayWhenReady(): Boolean = if (fade != null) false else super.getPlayWhenReady()

    override fun stop() {
        cancelFade()
        exo.volume = 1f
        super.stop()
    }

    private fun fadeOutAndPause() {
        if (fade != null) return
        if (!exo.isPlaying) {
            // buffering / already paused: nothing to fade
            exo.pause()
            exo.volume = 1f
            return
        }
        val start = SystemClock.uptimeMillis()
        val from = exo.volume
        val r = object : Runnable {
            override fun run() {
                val t = (SystemClock.uptimeMillis() - start) / FADE_MS.toFloat()
                if (t >= 1f) {
                    fade = null
                    exo.pause()
                    exo.volume = 1f
                    return
                }
                val k = 1f - t
                exo.volume = from * k * k // quadratic curve sounds smoother than linear
                handler.postDelayed(this, 16)
            }
        }
        fade = r
        handler.post(r)
    }

    private fun cancelFade() {
        fade?.let { handler.removeCallbacks(it) }
        fade = null
    }

    companion object {
        const val FADE_MS = 700L
    }
}
