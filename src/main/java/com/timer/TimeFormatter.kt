package com.timer

import java.util.Locale

/**
 * Formats remaining time as `mm:ss`, or `h:mm:ss` from one hour on.
 * Seconds are rounded **up**, so a timer never shows `00:00` while it is still running.
 */
public fun formatRemainingTime(millis: Long): String {
    val totalSeconds = (millis.coerceAtLeast(0L) + 999L) / 1000L
    val hours = totalSeconds / 3600
    val minutes = totalSeconds % 3600 / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
    }
}
