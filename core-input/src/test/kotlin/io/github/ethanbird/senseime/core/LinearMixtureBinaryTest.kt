package io.github.ethanbird.senseime.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.exp
import kotlin.math.ln
import org.junit.Assert.*
import org.junit.Test

class LinearMixtureBinaryTest {
    private fun tiny() = javaClass.getResourceAsStream("/character-lm/tiny.scng")!!.use { it.readBytes() }
    private fun support(bytes: ByteArray): IntArray {
        val b=ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        return IntArray(b.getInt(6)) { b.getInt(26+it*8) }
    }
    private fun different(): ByteArray {
        val entries=sortedMapOf('茶'.code to .3,0x20000 to .3,CharacterLanguageModel.EOS to .1,CharacterLanguageModel.UNK to .3)
        val b=ByteBuffer.allocate(26+entries.size*8).order(ByteOrder.BIG_ENDIAN)
        b.putInt(0x53434E47);b.putShort(1);b.putInt(entries.size);repeat(4){b.putInt(0)}
        entries.forEach { (cp,p) -> b.putInt(cp);b.putFloat(ln(p).toFloat()) };return b.array()
    }
    @Test fun binaryBackoffsRemainNormalizedForSeenUnseenAndUnknownContexts() {
        val a=tiny();val b=different();val m=LinearMixtureCharacterModel.fromBytes(a,b,.25)
        val tokens=(support(a).toList()+support(b).toList()).distinct()
        for ((x,y) in listOf(CharacterLanguageModel.BOS to CharacterLanguageModel.BOS,'喜'.code to '欢'.code,
                '陌'.code to '生'.code,0x20000 to '茶'.code)) {
            assertEquals(1.0,tokens.sumOf { exp(m.logProbability(x,y,it).toDouble()) },1e-6)
        }
    }
    @Test fun binaryEndpointsAvoidParsingUnusedModelsAndIntermediateRejectsCorruption() {
        val a=tiny();val invalid=byteArrayOf(0)
        val reference=BinaryCharacterLanguageModel.fromBytes(a)
        for (m in listOf(LinearMixtureCharacterModel.fromBytes(a,invalid,0.0),LinearMixtureCharacterModel.fromBytes(invalid,a,1.0))) {
            for (cp in support(a)) assertEquals(reference.logProbability('喜'.code,'欢'.code,cp),m.logProbability('喜'.code,'欢'.code,cp),0f)
        }
        assertThrows(IllegalArgumentException::class.java) { LinearMixtureCharacterModel.fromBytes(a,invalid,.25) }
        assertThrows(IllegalArgumentException::class.java) { LinearMixtureCharacterModel.fromBytes(invalid,a,.25) }
    }
    @Test fun concurrentReadsShareNoAdaptiveOrEditorState() {
        val m=LinearMixtureCharacterModel.fromBytes(tiny(),different(),.25)
        val expected=m.logProbability('喜'.code,'欢'.code,'茶'.code)
        val executor=Executors.newFixedThreadPool(4)
        try { executor.invokeAll((1..200).map { Callable { assertEquals(expected,m.logProbability('喜'.code,'欢'.code,'茶'.code),0f) } }).forEach { it.get() } }
        finally { executor.shutdownNow() }
    }
}
