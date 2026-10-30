package com.andrerinas.openheadunit.decoder.audio

/** Retain the unwritten tail across partial writes and temporary backpressure. Units are the
 * caller's (bytes for PCM wrappers, shorts for the mixer). No allocation on the audio thread. */
internal object AudioWriteLoop {
    inline fun writeFully(
        size: Int,
        isRunning: () -> Boolean,
        write: (offset: Int, remaining: Int) -> Int,
        onProgress: (written: Int) -> Unit,
        awaitWritable: () -> Unit
    ): Int {
        var offset = 0
        while (offset < size && isRunning()) {
            val written = write(offset, size - offset)
            if (written < 0) return written
            if (written == 0) {
                awaitWritable()
            } else {
                offset += written
                onProgress(written)
            }
        }
        return offset
    }

    /**
     * How long to wait for a full output to take more, growing while it stays full.
     *
     * Writes are non-blocking, so a full track is retried. A fixed 1ms retry is fine where the
     * HAL takes small bursts, but a unit whose mixer pulls ~62ms at a time (measured: TopWay T3,
     * 2720 frames at 44.1 kHz) stays full for most of each period, and 1ms retries woke the mixer
     * ~1000 times a second at urgent-audio priority. That cost over half a core and starved the
     * video and transport threads the audio depends on. Doubling to [MAX_BACKOFF_MS] keeps the
     * first retry prompt; a full output holds at least one HAL period, so 8ms never starves it.
     */
    const val MAX_BACKOFF_MS = 8L

    fun nextBackoffMs(currentMs: Long): Long = (currentMs * 2).coerceIn(1L, MAX_BACKOFF_MS)
}
