package io.github.ethanbird.senseime.core

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class DecodeWorkScopeTest {
    @Test fun cancellationStopsInsideTheWordLatticeBeforeAllModelWorkAndAllowsRetry() {
        var active = true
        var calls = 0
        val model = object : CharacterLanguageModel {
            override fun unigramLogProbability(next: Int) = -6f
            override fun logProbability(previous2: Int, previous1: Int, next: Int): Float {
                calls++
                if (calls == 12) active = false
                return -6f
            }
        }
        val decoder = fixture().withLanguageModel(model, .5f)
        // Warm-up outside a scope establishes the full work/output reference.
        val expected = decoder.decode("woshirenwoshiren", 64)
        val fullCalls = calls
        calls = 0; active = true
        assertThrows(DecodeSupersededException::class.java) {
            DecodeWorkScope.whileCurrent({ active }) { decoder.decode("woshirenwoshiren", 64) }
        }
        assertTrue("Cancellation waited until all $fullCalls model transitions completed", calls < fullCalls / 2)
        assertEquals(expected, decoder.decode("woshirenwoshiren", 64))
    }

    @Test fun supersessionAfterFirstPrefixDoesNotCalculateTheRemainingPrefixes() {
        var active = true
        var prefixCalls = 0
        val base = object : TextContextualInputDecoder {
            override fun decode(composing: String, limit: Int) = listOf(Candidate("我", canonicalPinyin = composing))
            override fun decodeAfter(previousCodePoint: Int, composing: String, limit: Int) = decode(composing, limit)
            override fun decodeWithContext(leftContext: CharSequence, composing: String, limit: Int, prefixProbe: Boolean): List<Candidate> {
                if (prefixProbe) { prefixCalls++; active = false }
                return decode(composing, limit)
            }
        }
        val decoder = AdaptivePinyinDecoder(base, MemoryUserLexicon(), PinyinSyllableSegmenter(setOf("wo", "shi", "ren")))
        assertThrows(DecodeSupersededException::class.java) {
            DecodeWorkScope.whileCurrent({ active }) { decoder.decodeProgressively(PinyinComposition(remainingPinyin = "woshirenx"), "", 8) }
        }
        assertEquals(1, prefixCalls)
    }

    @Test fun nestedScopesRestoreTheirParentAfterCancellationOrFailure() {
        var outer = true
        DecodeWorkScope.whileCurrent({ outer }) {
            assertThrows(DecodeSupersededException::class.java) { DecodeWorkScope.whileCurrent({ false }) { fail("entered canceled work") } }
            DecodeWorkScope.checkpoint()
            assertThrows(IllegalStateException::class.java) { DecodeWorkScope.whileCurrent({ true }) { error("fixture") } }
            DecodeWorkScope.checkpoint()
        }
        assertThrows(DecodeSupersededException::class.java) {
            DecodeWorkScope.whileCurrent({ outer }) {
                outer = false
                DecodeWorkScope.whileCurrent({ true }) { fail("inner escaped canceled parent") }
            }
        }
        DecodeWorkScope.checkpoint() // no leaked cancellation after finally
    }

    @Test fun anotherThreadAndTheReusedWorkerDoNotInheritAScope() {
        val worker = Executors.newSingleThreadExecutor()
        try {
            var active = true
            DecodeWorkScope.whileCurrent({ active }) {
                assertThrows(DecodeSupersededException::class.java) {
                    DecodeWorkScope.whileCurrent({ false }) { error("unreachable") }
                }
                active = false
                worker.submit { DecodeWorkScope.checkpoint() }.get(2, TimeUnit.SECONDS)
                assertThrows(DecodeSupersededException::class.java) { DecodeWorkScope.checkpoint() }
                active = true
            }
            worker.submit {
                assertThrows(DecodeSupersededException::class.java) { DecodeWorkScope.whileCurrent({ false }) {} }
            }.get(2, TimeUnit.SECONDS)
            worker.submit { DecodeWorkScope.checkpoint() }.get(2, TimeUnit.SECONDS)
        } finally { worker.shutdownNow() }
    }

    @Test fun interruptedPrefixComputationDoesNotPublishAPartialCacheEntry() {
        val cache = PrefixCandidateCache()
        val key = PrefixCandidateCache.Key("wo", 8, -1)
        var active = true
        assertThrows(DecodeSupersededException::class.java) {
            DecodeWorkScope.whileCurrent({ active }) {
                cache.getOrCompute(key) {
                    active = false
                    DecodeWorkScope.checkpoint()
                    listOf(Candidate("partial"))
                }
            }
        }
        assertEquals(listOf(Candidate("我")), cache.getOrCompute(key) { listOf(Candidate("我")) })
    }

    private fun fixture(): PinyinDecoder {
        val rows = listOf("wo" to listOf("我", "握", "卧"), "shi" to listOf("是", "诗", "时"), "ren" to listOf("人", "仁", "认"))
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeBytes("SPLX"); out.writeShort(3); out.writeInt(rows.size)
            for ((code, words) in rows.sortedBy { it.first }) {
                out.writeByte(code.length); out.writeBytes(code); out.writeByte(words.size)
                for ((index, word) in words.withIndex()) {
                    val utf8 = word.toByteArray()
                    out.writeByte(utf8.size); out.write(utf8); out.writeInt(1000 - index)
                    out.writeByte(1); out.writeBytes(code.take(1)); out.writeByte(0)
                }
            }
        }
        return PinyinDecoder.fromBytes(bytes.toByteArray())
    }
}
