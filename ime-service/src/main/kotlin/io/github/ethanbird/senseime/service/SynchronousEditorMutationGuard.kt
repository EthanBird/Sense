package io.github.ethanbird.senseime.service

/** Identifies callbacks made synchronously by an InputConnection mutation we initiated. */
internal class SynchronousEditorMutationGuard {
    private var depth = 0

    val isActive: Boolean
        get() = depth > 0

    fun <T> duringMutation(block: () -> T): T {
        depth += 1
        return try {
            block()
        } finally {
            depth -= 1
        }
    }
}
