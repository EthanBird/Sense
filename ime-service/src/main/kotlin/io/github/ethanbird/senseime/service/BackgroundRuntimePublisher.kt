package io.github.ethanbird.senseime.service

import java.util.concurrent.Executor

/**
 * Builds one complete immutable runtime on [backgroundExecutor], then transfers only the finished
 * value to [publicationExecutor]. Keeping assembly outside the publication callback prevents an
 * expensive index constructor from accidentally moving onto Android's main thread.
 */
internal class BackgroundRuntimePublisher(
    private val backgroundExecutor: Executor,
    private val publicationExecutor: Executor,
) {
    fun <Runtime> load(
        build: () -> Runtime,
        publish: (Runtime) -> Unit,
    ) {
        backgroundExecutor.execute {
            val runtime = build()
            publicationExecutor.execute { publish(runtime) }
        }
    }
}
