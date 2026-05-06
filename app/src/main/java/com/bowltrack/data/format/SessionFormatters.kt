package com.bowltrack.data.format

import java.util.concurrent.TimeUnit

/**
 * Formats a wall-clock instant as a short relative label
 * ("2 h ago", "3 d ago", "just now"). Pure function so the Compose
 * recomposition stays predictable.
 *
 * English-only, matching the brief's scope for the capstone.
 */
fun formatRelative(timestampMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
    val deltaMillis = (nowMillis - timestampMillis).coerceAtLeast(0L)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(deltaMillis)
    val hours = TimeUnit.MILLISECONDS.toHours(deltaMillis)
    val days = TimeUnit.MILLISECONDS.toDays(deltaMillis)
    return when {
        minutes < 1L -> "just now"
        minutes < 60L -> "${minutes}m ago"
        hours < 24L -> "${hours}h ago"
        else -> "${days}d ago"
    }
}

/** Formats a length in seconds as `m:ss`. */
fun formatDuration(durationSeconds: Float): String {
    val totalSeconds = durationSeconds.toLong().coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "%d:%02d".format(minutes, seconds)
}
