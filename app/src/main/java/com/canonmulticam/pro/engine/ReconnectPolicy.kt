package com.canonmulticam.pro.engine

/**
 * Pure, Android-free timing rules for the camera session supervisor.
 * Kept separate so it can be unit tested on the JVM.
 */
object ReconnectPolicy {
    const val STALE_AFTER_MS = 1_500L
    const val RECONNECT_AFTER_MS = 4_000L
    const val MIN_ERRORS_BEFORE_RECONNECT = 2
    const val MAX_FIRST_CONNECT_ATTEMPTS = 5
    const val MAX_USB_ATTEMPTS = 2
    const val RECONNECT_WINDOW_MS = 120_000L

    private const val BACKOFF_BASE_MS = 500L
    private const val BACKOFF_CAP_MS = 5_000L
    private const val ERROR_DELAY_STEP_MS = 100L
    private const val ERROR_DELAY_CAP_MS = 500L
    private const val MAX_JITTER_FRACTION = 0.25
    private const val MAX_BACKOFF_SHIFT = 10

    /** Exponential backoff: 0.5s, 1s, 2s, 4s, then capped at 5s, plus up to 25% jitter. */
    fun backoffMs(attempt: Int, jitterFraction: Double = 0.0): Long {
        val shift = (attempt.coerceAtLeast(1) - 1).coerceAtMost(MAX_BACKOFF_SHIFT)
        val capped = minOf(BACKOFF_BASE_MS shl shift, BACKOFF_CAP_MS)
        val jitter = (capped * jitterFraction.coerceIn(0.0, MAX_JITTER_FRACTION)).toLong()
        return capped + jitter
    }

    /** Pause between failed frame pulls so a dead link is not hammered every 33 ms. */
    fun errorDelayMs(consecutiveErrors: Int): Long =
        (ERROR_DELAY_STEP_MS * consecutiveErrors.coerceAtLeast(1)).coerceAtMost(ERROR_DELAY_CAP_MS)

    fun isStale(downMs: Long): Boolean = downMs >= STALE_AFTER_MS

    fun shouldReconnect(downMs: Long, consecutiveErrors: Int): Boolean =
        downMs >= RECONNECT_AFTER_MS && consecutiveErrors >= MIN_ERRORS_BEFORE_RECONNECT

    fun shouldGiveUp(
        isUsbTarget: Boolean,
        everLive: Boolean,
        failedAttempts: Int,
        downForMs: Long
    ): Boolean = when {
        isUsbTarget -> failedAttempts >= MAX_USB_ATTEMPTS
        !everLive -> failedAttempts >= MAX_FIRST_CONNECT_ATTEMPTS
        else -> downForMs >= RECONNECT_WINDOW_MS
    }
}
