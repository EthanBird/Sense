package io.github.ethanbird.senseime.ui

import android.os.SystemClock
import android.view.View

/** Time seam used by gesture, repeat, and settle state machines. */
internal fun interface KeyboardInteractionClock {
    fun uptimeMillis(): Long
}

internal object SystemKeyboardInteractionClock : KeyboardInteractionClock {
    override fun uptimeMillis(): Long = SystemClock.uptimeMillis()
}

/**
 * Scheduling seam for the interaction controller.
 *
 * The controller owns stable Runnable instances. Implementations therefore do
 * not need to allocate wrapper lambdas while MOVE/repeat/animation streams are
 * active.
 */
internal interface KeyboardFrameScheduler {
    fun post(task: Runnable)
    fun postDelayed(task: Runnable, delayMillis: Long)
    fun postOnAnimation(task: Runnable)
    fun postOnAnimationDelayed(task: Runnable, delayMillis: Long)
    fun remove(task: Runnable)
    fun invalidate()
    fun postInvalidateDelayed(delayMillis: Long)
    fun postInvalidateOnAnimation()
    fun postInvalidateOnAnimation(left: Int, top: Int, right: Int, bottom: Int)
}

internal class ViewKeyboardFrameScheduler(
    private val view: View,
) : KeyboardFrameScheduler {
    override fun post(task: Runnable) {
        view.post(task)
    }

    override fun postDelayed(task: Runnable, delayMillis: Long) {
        view.postDelayed(task, delayMillis)
    }

    override fun postOnAnimation(task: Runnable) {
        view.postOnAnimation(task)
    }

    override fun postOnAnimationDelayed(task: Runnable, delayMillis: Long) {
        view.postOnAnimationDelayed(task, delayMillis)
    }

    override fun remove(task: Runnable) {
        view.removeCallbacks(task)
    }

    override fun invalidate() {
        view.postInvalidate()
    }

    override fun postInvalidateDelayed(delayMillis: Long) {
        view.postInvalidateDelayed(delayMillis)
    }

    override fun postInvalidateOnAnimation() {
        view.postInvalidateOnAnimation()
    }

    override fun postInvalidateOnAnimation(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ) {
        view.postInvalidateOnAnimation(left, top, right, bottom)
    }
}

internal fun interface KeyboardHaptics {
    fun perform(feedbackConstant: Int)
}

internal class ViewKeyboardHaptics(
    private val view: View,
) : KeyboardHaptics {
    override fun perform(feedbackConstant: Int) {
        view.performHapticFeedback(feedbackConstant)
    }
}
