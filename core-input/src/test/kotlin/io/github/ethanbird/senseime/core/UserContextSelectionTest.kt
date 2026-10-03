package io.github.ethanbird.senseime.core

import org.junit.Assert.*
import org.junit.Test

class UserContextSelectionTest {
    @Test fun unicodeSuffixStopsAtBoundariesAndNeverKeepsMoreThanTwoCharacters() {
        assertEquals("人民", UserContextSelection.suffix("保护人民"))
        assertEquals("", UserContextSelection.suffix("人民。"))
        assertEquals("民", UserContextSelection.suffix("人 民"))
        assertEquals("𠀀民", UserContextSelection.suffix("人𠀀民"))
        assertEquals("", UserContextSelection.suffix(""))
        assertEquals("", UserContextSelection.suffix("人民A"))
    }

    @Test fun boundedImmutableSerializationRejectsMalformedPayloads() {
        val source = (0..20).associate { (0x4e00 + it).toChar().toString() to it.toLong() }.toMutableMap()
        source["bad"] = 200
        source["甲乙丙"] = 300
        source["人民"] = -1
        val snapshot = UserContextSelection.sanitize(source)
        assertEquals(8, snapshot.size)
        source.clear()
        assertEquals(8, snapshot.size)
        assertThrows(UnsupportedOperationException::class.java) { (snapshot as MutableMap).clear() }
        assertEquals(snapshot, UserContextSelection.decode(UserContextSelection.encode(snapshot)))
        assertEquals(mapOf("人民" to 12L), UserContextSelection.decode("bad\t20\n人民\t12\n人民\t3\n甲\tNaN\n乙\t-1"))
        assertEquals(emptyMap<String, Long>(), UserContextSelection.decode("民".repeat(2_049)))
    }

    @Test fun onlyExplicitEvidenceUpdatesContextAndClockRollbackDoesNotExpireIt() {
        val original = mapOf("人民" to 10L)
        assertSame(original, UserContextSelection.updated(original, UserLearningEvidence(UserSelectionKind.DEFAULT_ACCEPT, 0, "人民"), 20))
        for (kind in UserSelectionKind.entries.filter { it != UserSelectionKind.DEFAULT_ACCEPT }) {
            assertEquals(mapOf("人民" to 20L), UserContextSelection.updated(original, UserLearningEvidence(kind, 0, "保护人民"), 20))
        }
        assertTrue(UserContextSelection.isFresh(100, 90))
        assertFalse(UserContextSelection.isFresh(100, 101 + UserContextSelection.LIFETIME_MILLIS))
    }
}
