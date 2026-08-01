package io.github.ethanbird.senseime.service

import io.github.ethanbird.senseime.core.T9Composition
import io.github.ethanbird.senseime.core.T9SyllableIndex
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundRuntimePublisherTest {
    @Test
    fun completeT9RuntimeIsBuiltOffPublicationThreadAndPublishedAsOneValue() {
        val background = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "decoder-loader-fixture")
        }
        val publication = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "main-publication-fixture")
        }
        val delivered = CountDownLatch(1)
        var builtThread = ""
        var publishedThread = ""
        var publishedRuntime: FixtureRuntime? = null

        try {
            BackgroundRuntimePublisher(background, publication).load(
                build = {
                    builtThread = Thread.currentThread().name
                    FixtureRuntime(
                        decoderIdentity = Any(),
                        t9Index = T9SyllableIndex(setOf("hun", "shen")),
                    )
                },
                publish = { runtime ->
                    publishedThread = Thread.currentThread().name
                    publishedRuntime = runtime
                    delivered.countDown()
                },
            )

            assertTrue(delivered.await(5, TimeUnit.SECONDS))
            assertEquals("decoder-loader-fixture", builtThread)
            assertEquals("main-publication-fixture", publishedThread)
            assertTrue(publishedRuntime?.decoderIdentity != null)
            assertTrue(
                publishedRuntime?.t9Index
                    ?.paths(compositionOf("486"), 8)
                    ?.any { it.canonical == "hun" } == true,
            )
        } finally {
            background.shutdownNow()
            publication.shutdownNow()
        }
    }

    private data class FixtureRuntime(
        val decoderIdentity: Any,
        val t9Index: T9SyllableIndex,
    )

    private fun compositionOf(digits: String): T9Composition =
        digits.fold(T9Composition()) { state, digit -> state.typeDigit(digit) }
}
