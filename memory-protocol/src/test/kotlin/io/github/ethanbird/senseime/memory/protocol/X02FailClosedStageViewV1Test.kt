package io.github.ethanbird.senseime.memory.protocol

import java.lang.reflect.Modifier
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class X02FailClosedStageViewV1Test {
    @Test
    fun everyRoleStartsAtAnIndependentSchemaOnlyCeiling() {
        val holders = holders()

        assertEquals(
            X02StageConsumerRoleV1.entries.toSet(),
            holders.map { it.role }.toSet(),
        )
        holders.forEach { holder ->
            assertEquals(
                X02SafeStageCauseV1.PROCESS_START,
                holder.currentView().cause,
            )
            assertEquals(
                FeatureStageV1.SCHEMA_ONLY,
                holder.currentView().normalStageCeiling,
            )
        }
        assertNotSame(holders[0], holders[1])
        assertNotSame(holders[1], holders[2])
    }

    @Test
    fun fullSafeStateByRejectedInputTableNeverChangesTheCeiling() {
        val expectedCause = mapOf(
            X02RejectedStageInputV1.ABSENT to X02SafeStageCauseV1.SOURCE_ABSENT,
            X02RejectedStageInputV1.CORRUPT to X02SafeStageCauseV1.SOURCE_CORRUPT,
            X02RejectedStageInputV1.UNKNOWN to X02SafeStageCauseV1.SOURCE_UNKNOWN,
            X02RejectedStageInputV1.UNAUTHENTICATED to
                X02SafeStageCauseV1.SOURCE_UNAUTHENTICATED,
        )

        X02SafeStageCauseV1.entries.forEach { startingCause ->
            X02RejectedStageInputV1.entries.forEach { input ->
                val holder = X02ProcessStageHolderV1.newMainHolder()
                inputForCause(startingCause)?.let(holder::failClosedOn)

                holder.failClosedOn(input)

                assertEquals(expectedCause.getValue(input), holder.currentView().cause)
                assertEquals(
                    FeatureStageV1.SCHEMA_ONLY,
                    holder.currentView().normalStageCeiling,
                )
            }
        }
    }

    @Test
    fun sameRejectedInputUsesStableImmutableView() {
        X02RejectedStageInputV1.entries.forEach { input ->
            assertSame(
                X02FailClosedStageReducerV1.reduce(input),
                X02FailClosedStageReducerV1.reduce(input),
            )
        }
    }

    @Test
    fun rolesAreIsolatedAndRestartDoesNotInheritPreviousState() {
        val main = X02ProcessStageHolderV1.newMainHolder()
        val ime = X02ProcessStageHolderV1.newImeHolder()
        val brain = X02ProcessStageHolderV1.newBrainHolder()

        ime.failClosedOn(X02RejectedStageInputV1.CORRUPT)
        brain.failClosedOn(X02RejectedStageInputV1.UNKNOWN)

        assertEquals(X02SafeStageCauseV1.PROCESS_START, main.currentView().cause)
        assertEquals(X02SafeStageCauseV1.SOURCE_CORRUPT, ime.currentView().cause)
        assertEquals(X02SafeStageCauseV1.SOURCE_UNKNOWN, brain.currentView().cause)
        assertEquals(
            X02SafeStageCauseV1.PROCESS_START,
            X02ProcessStageHolderV1.newImeHolder().currentView().cause,
        )
    }

    @Test
    fun everyRoleRestartReturnsToProcessStart() {
        holders().forEach { holder ->
            holder.failClosedOn(X02RejectedStageInputV1.UNAUTHENTICATED)

            val restarted =
                when (holder.role) {
                    X02StageConsumerRoleV1.MAIN ->
                        X02ProcessStageHolderV1.newMainHolder()
                    X02StageConsumerRoleV1.IME ->
                        X02ProcessStageHolderV1.newImeHolder()
                    X02StageConsumerRoleV1.BRAIN ->
                        X02ProcessStageHolderV1.newBrainHolder()
                }

            assertEquals(
                X02SafeStageCauseV1.SOURCE_UNAUTHENTICATED,
                holder.currentView().cause,
            )
            assertEquals(
                X02SafeStageCauseV1.PROCESS_START,
                restarted.currentView().cause,
            )
            assertEquals(holder.role, restarted.role)
        }
    }

    @Test
    fun concurrentReadersOnlyObserveCompleteSafeViews() {
        val holder = X02ProcessStageHolderV1.newBrainHolder()
        val start = CountDownLatch(1)
        val done = CountDownLatch(8)
        val failed = AtomicBoolean(false)
        val causes = X02SafeStageCauseV1.entries.toSet()

        repeat(4) { writerIndex ->
            Thread {
                try {
                    start.await()
                    repeat(5_000) { iteration ->
                        val input =
                            X02RejectedStageInputV1.entries[
                                (writerIndex + iteration) %
                                    X02RejectedStageInputV1.entries.size
                            ]
                        holder.failClosedOn(input)
                    }
                } catch (_: Throwable) {
                    failed.set(true)
                } finally {
                    done.countDown()
                }
            }.start()
        }
        repeat(4) {
            Thread {
                try {
                    start.await()
                    repeat(20_000) {
                        val current = holder.currentView()
                        if (
                            current.normalStageCeiling != FeatureStageV1.SCHEMA_ONLY ||
                            current.cause !in causes
                        ) {
                            failed.set(true)
                        }
                    }
                } catch (_: Throwable) {
                    failed.set(true)
                } finally {
                    done.countDown()
                }
            }.start()
        }

        start.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))

        assertFalse(failed.get())
    }

    @Test
    fun safeCeilingCannotRaiseAnOffRequest() {
        X02RejectedStageInputV1.entries.forEach { input ->
            val view = X02FailClosedStageReducerV1.reduce(input)
            val decision = X02FeatureStagePolicyV1.reduce(
                X02NormalStageRequestV1(
                    capability = NormalProfileCapabilityIdV1.CAPTURE,
                    requestedStage = FeatureStageV1.OFF,
                    executionClass = ProfileExecutionClassV1.REAL_DATA,
                    buildProfileMax = FeatureStageV1.DEFAULT,
                    localRequestedStage = FeatureStageV1.OFF,
                    dependencyStage = view.normalStageCeiling,
                ),
            )

            assertEquals(FeatureStageV1.OFF, decision.effectiveStage)
        }
    }

    @Test
    fun publicApiCannotConstructCopyOrInstallAStage() {
        assertTrue(
            X02SafeStageViewV1::class.java.declaredConstructors.all {
                Modifier.isPrivate(it.modifiers)
            },
        )
        assertTrue(
            X02StageDecisionV1::class.java.declaredConstructors
                .filterNot { it.isSynthetic }
                .all { Modifier.isPrivate(it.modifiers) },
        )
        val forbiddenMethodNames = setOf(
            "copy",
            "set",
            "setStage",
            "install",
            "accept",
            "upgrade",
            "recover",
            "reset",
            "compareAndSet",
            "updateAndGet",
            "atomicReference",
        )
        assertTrue(
            X02SafeStageViewV1::class.java.methods.none {
                it.name in forbiddenMethodNames
            },
        )
        assertTrue(
            X02ProcessStageHolderV1::class.java.methods.none {
                it.name in forbiddenMethodNames ||
                    it.parameterTypes.any { type ->
                        type == FeatureStageV1::class.java ||
                            type == ByteArray::class.java
                    }
            },
        )
    }

    @Test
    fun decisionReasonsAreDefensiveAndCannotChangeTheDecision() {
        val decision = X02FeatureStagePolicyV1.reduce(
            X02NormalStageRequestV1(
                capability = NormalProfileCapabilityIdV1.CAPTURE,
                requestedStage = FeatureStageV1.DEFAULT,
                executionClass = ProfileExecutionClassV1.REAL_DATA,
                buildProfileMax = FeatureStageV1.DEFAULT,
                localRequestedStage = FeatureStageV1.DEFAULT,
                dependencyStage = FeatureStageV1.DEFAULT,
            ),
        )
        val callerCopy = decision.reasons as MutableList<X02StageDecisionReasonV1>

        callerCopy.clear()

        assertEquals(FeatureStageV1.SCHEMA_ONLY, decision.effectiveStage)
        assertTrue(decision.reasons.isNotEmpty())
    }

    @Test
    fun internalDecisionIssuerRejectsForgedStageAndDisposition() {
        assertThrows(IllegalArgumentException::class.java) {
            X02StageDecisionV1.issue(
                effectiveStage = FeatureStageV1.DEFAULT,
                disposition = X02StageDecisionDispositionV1.BLOCKED_FAIL_CLOSED,
                reasons = listOf(X02StageDecisionReasonV1.X02_HARD_MAXIMUM),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            X02StageDecisionV1.issue(
                effectiveStage = FeatureStageV1.SCHEMA_ONLY,
                disposition = X02StageDecisionDispositionV1.VALID_OFF_REQUEST,
                reasons = listOf(X02StageDecisionReasonV1.REQUESTED_OFF),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            X02StageDecisionV1.issue(
                effectiveStage = FeatureStageV1.SCHEMA_ONLY,
                disposition = X02StageDecisionDispositionV1.VALID_SCHEMA_REQUEST,
                reasons = listOf(X02StageDecisionReasonV1.CONFIGURED_CEILING_CONTRACTED),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            X02StageDecisionV1.issue(
                effectiveStage = FeatureStageV1.SCHEMA_ONLY,
                disposition = X02StageDecisionDispositionV1.BLOCKED_FAIL_CLOSED,
                reasons = listOf(X02StageDecisionReasonV1.CONFIGURED_CEILING_CONTRACTED),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            X02StageDecisionV1.issue(
                effectiveStage = FeatureStageV1.OFF,
                disposition = X02StageDecisionDispositionV1.VALID_OFF_REQUEST,
                reasons = listOf(
                    X02StageDecisionReasonV1.REQUESTED_OFF,
                    X02StageDecisionReasonV1.GATE_SET_MUST_BE_EMPTY,
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            X02StageDecisionV1.issue(
                effectiveStage = FeatureStageV1.SCHEMA_ONLY,
                disposition = X02StageDecisionDispositionV1.VALID_SCHEMA_REQUEST,
                reasons = listOf(
                    X02StageDecisionReasonV1.SCHEMA_CODEC_ONLY,
                    X02StageDecisionReasonV1.PERSISTENT_CAPABILITY_BLOCKED,
                ),
            )
        }
    }

    @Test
    fun privateConstructorAlsoEnforcesTheStageInvariant() {
        val constructors =
            X02StageDecisionV1::class.java.declaredConstructors.filter { constructor ->
                constructor.parameterTypes.contains(FeatureStageV1::class.java)
            }
        assertTrue(constructors.isNotEmpty())

        constructors.forEach { constructor ->
            constructor.isAccessible = true
            val arguments = constructor.parameterTypes.map { parameterType ->
                when (parameterType) {
                    FeatureStageV1::class.java -> FeatureStageV1.DEFAULT
                    X02StageDecisionDispositionV1::class.java ->
                        X02StageDecisionDispositionV1.BLOCKED_FAIL_CLOSED
                    List::class.java ->
                        listOf(X02StageDecisionReasonV1.X02_HARD_MAXIMUM)
                    else -> null
                }
            }.toTypedArray()

            val failure = assertThrows(InvocationTargetException::class.java) {
                constructor.newInstance(*arguments)
            }
            assertTrue(failure.cause is IllegalArgumentException)
        }
    }

    private fun holders(): List<X02ProcessStageHolderV1> =
        listOf(
            X02ProcessStageHolderV1.newMainHolder(),
            X02ProcessStageHolderV1.newImeHolder(),
            X02ProcessStageHolderV1.newBrainHolder(),
        )

    private fun inputForCause(
        cause: X02SafeStageCauseV1,
    ): X02RejectedStageInputV1? =
        when (cause) {
            X02SafeStageCauseV1.PROCESS_START -> null
            X02SafeStageCauseV1.SOURCE_ABSENT -> X02RejectedStageInputV1.ABSENT
            X02SafeStageCauseV1.SOURCE_CORRUPT -> X02RejectedStageInputV1.CORRUPT
            X02SafeStageCauseV1.SOURCE_UNKNOWN -> X02RejectedStageInputV1.UNKNOWN
            X02SafeStageCauseV1.SOURCE_UNAUTHENTICATED ->
                X02RejectedStageInputV1.UNAUTHENTICATED
        }
}
