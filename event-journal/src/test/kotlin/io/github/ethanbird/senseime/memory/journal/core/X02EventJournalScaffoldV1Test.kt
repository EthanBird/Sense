package io.github.ethanbird.senseime.memory.journal.core

import io.github.ethanbird.senseime.memory.protocol.FeatureStageV1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class X02EventJournalScaffoldV1Test {
    @Test
    fun scaffoldIsSchemaOnlyAndHasNoStorage() {
        assertEquals(
            X02EventJournalAvailabilityV1.SCHEMA_ONLY_NO_STORAGE,
            X02EventJournalScaffoldV1.availability(),
        )
        assertEquals(
            FeatureStageV1.SCHEMA_ONLY,
            X02EventJournalScaffoldV1.normalStageCeiling(),
        )
    }

    @Test
    fun scaffoldPublicApiHasNoPayloadOrOperationInput() {
        val declaredPublicMethods =
            X02EventJournalScaffoldV1::class.java.declaredMethods
                .filter { method -> java.lang.reflect.Modifier.isPublic(method.modifiers) }

        assertEquals(
            setOf("availability", "normalStageCeiling"),
            declaredPublicMethods.map { it.name }.toSet(),
        )
        assertTrue(declaredPublicMethods.all { it.parameterCount == 0 })
    }
}
