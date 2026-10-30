package com.andrerinas.openheadunit.main.bento

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper

/**
 * What Android Auto is playing, for the home screen's music card.
 *
 * `AapService` already turns the phone's media messages into its media session; it also writes them
 * here, so the home screen can show the song without binding to the session. Process-wide and
 * main-thread delivered; cleared when the projection ends.
 */
object AaNowPlaying {

    @Volatile var title: String = ""
        private set
    @Volatile var artist: String = ""
        private set
    @Volatile var albumArt: Bitmap? = null
        private set
    @Volatile var isPlaying: Boolean = false
        private set
    /** The song's length in ms, 0 when the phone did not say. */
    @Volatile var durationMs: Long = 0L
        private set
    @Volatile private var positionMs: Long = 0L
    @Volatile private var positionAtElapsedMs: Long = 0L

    /**
     * Where the song is now: the phone's last report, moved on by the time since while it plays.
     * The phone reports about once a second; this keeps a progress bar smooth between reports.
     */
    fun currentPositionMs(nowElapsedMs: Long = android.os.SystemClock.elapsedRealtime()): Long {
        val moved = if (isPlaying && positionAtElapsedMs > 0) nowElapsedMs - positionAtElapsedMs else 0L
        val at = positionMs + moved.coerceAtLeast(0L)
        return if (durationMs > 0) at.coerceAtMost(durationMs) else at
    }

    /** Told on the main thread after any change. One listener: the home screen showing it. */
    @Volatile var onChanged: (() -> Unit)? = null

    // Lazy: a plain JVM test has no main looper, and clear() runs from AapService paths under test.
    private val main by lazy { Handler(Looper.getMainLooper()) }

    fun setTrack(title: String, artist: String) {
        if (title == this.title && artist == this.artist) return
        this.title = title
        this.artist = artist
        this.albumArt = null
        this.durationMs = 0L
        this.positionMs = 0L
        this.positionAtElapsedMs = 0L
        notifyChanged()
    }

    fun setDuration(ms: Long) {
        if (ms == durationMs) return
        durationMs = ms.coerceAtLeast(0L)
        notifyChanged()
    }

    fun setPosition(ms: Long) {
        positionMs = ms.coerceAtLeast(0L)
        positionAtElapsedMs = android.os.SystemClock.elapsedRealtime()
        notifyChanged()
    }

    fun setAlbumArt(bitmap: Bitmap?) {
        albumArt = bitmap
        notifyChanged()
    }

    fun setPlaying(playing: Boolean) {
        if (playing == isPlaying) return
        // Freeze or restart the extrapolation at the moment the state flips.
        if (positionAtElapsedMs > 0) {
            positionMs = currentPositionMs()
            positionAtElapsedMs = android.os.SystemClock.elapsedRealtime()
        }
        isPlaying = playing
        notifyChanged()
    }

    fun clear() {
        title = ""
        artist = ""
        albumArt = null
        isPlaying = false
        durationMs = 0L
        positionMs = 0L
        positionAtElapsedMs = 0L
        notifyChanged()
    }

    private fun notifyChanged() {
        // No home screen listening, nothing to tell.
        if (onChanged == null) return
        main.post { onChanged?.invoke() }
    }
}
