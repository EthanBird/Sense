package io.github.ethanbird.senseime.service

import android.view.accessibility.AccessibilityManager

/** Read current system preference at each new idle interval, including after a held touch. */
internal object AssociationTimeoutPolicy {
    const val DEFAULT_MILLIS = 4_500
    const val CONTENT_FLAGS = AccessibilityManager.FLAG_CONTENT_TEXT or
        AccessibilityManager.FLAG_CONTENT_CONTROLS or AccessibilityManager.FLAG_CONTENT_ICONS

    fun delayMillis(recommend: (Int, Int) -> Int): Long =
        runCatching { recommend(DEFAULT_MILLIS, CONTENT_FLAGS).toLong() }
            .getOrDefault(DEFAULT_MILLIS.toLong())
            .coerceAtLeast(DEFAULT_MILLIS.toLong())
}
