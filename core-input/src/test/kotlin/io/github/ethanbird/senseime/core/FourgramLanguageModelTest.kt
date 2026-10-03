package io.github.ethanbird.senseime.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import org.junit.Assert.*
import org.junit.Test

class FourgramLanguageModelTest {
    private fun resource(name:String)=javaClass.getResourceAsStream("/fourgram-lm/$name")!!.use { it.readBytes() }
    private fun model()=BinaryFourgramLanguageModel.fromBytes(resource("base.scng"),resource("extension.scq4"))

    @Test fun binaryScoresMatchIndependentPythonReferenceAndPreserveLowerApi() {
        val model=model();val lower=BinaryCharacterLanguageModel.fromBytes(resource("base.scng"))
        for(row in resource("scores.tsv").toString(Charsets.UTF_8).lineSequence().filter { !it.startsWith('#') && it.isNotBlank() }) {
            val f=row.split('\t');val a=f[0].toInt();val b=f[1].toInt();val c=f[2].toInt();val w=f[3].toInt()
            assertEquals(row,f[5].toFloat(),model.logProbabilityWithThirdContext(a,b,c,w),1e-5f)
            assertEquals(row,lower.logProbability(b,c,w),model.logProbability(b,c,w),0f)
            assertEquals(lower.unigramLogProbability(w),model.unigramLogProbability(w),0f)
        }
    }

    @Test fun allObservedAndBackoffDistributionsConserveProbabilityMass() {
        val model=model();val base=ByteBuffer.wrap(resource("base.scng")).order(ByteOrder.BIG_ENDIAN)
        val vocabulary=IntArray(base.getInt(6)){base.getInt(26+it*8)}
        for(ctx in listOf("甲乙丙","戊乙丙","𠀀乙丙","陌乙丙","丁己甲")) {
            val cp=ctx.codePoints().toArray()
            assertEquals(1.0,vocabulary.sumOf { exp(model.logProbabilityWithThirdContext(cp[0],cp[1],cp[2],it).toDouble()) },1e-6)
        }
        assertTrue(model.estimatedRetainedBytes<4096)
    }

    @Test fun sourceHashCountsScoresAndContextReferencesAreValidatedBeforeUse() {
        val base=resource("base.scng");val ext=resource("extension.scq4")
        for(bytes in listOf(ext.copyOf(20),ext.copyOf(ext.size-1),ext+0.toByte(),ext.copyOf().also{it[6]=0}))
            assertThrows(IllegalArgumentException::class.java) { BinaryFourgramLanguageModel.fromBytes(base,bytes) }
        fun invalid(edit:(ByteBuffer)->Unit) {
            val changed=ext.copyOf();edit(ByteBuffer.wrap(changed).order(ByteOrder.BIG_ENDIAN))
            assertThrows(IllegalArgumentException::class.java) { BinaryFourgramLanguageModel.fromBytes(base,changed) }
        }
        invalid{it.putInt(38,Int.MAX_VALUE)};invalid{it.putLong(46,-1L)};invalid{it.putFloat(58,Float.NaN)}
        invalid{it.putInt(54,CharacterLanguageModel.BOS)}
        assertThrows(IllegalArgumentException::class.java) { model().logProbabilityWithThirdContext(0,0,0,CharacterLanguageModel.BOS) }
    }

    @Test fun wordSplitsAndUnicodeContextPackPreserveThreeCharacterHistory() {
        val model=model();val initial=PinyinLanguageScorer.context3("")
        val scorer=PinyinLanguageScorer(PinyinLanguageBinding(model,.5f,oovFeature=-4f),"jiayibingding",initial)
        val whole=scorer.extend(initial,"甲乙丙丁")
        val first=scorer.extend(initial,"甲乙");val second=scorer.extend(first.state,"丙丁")
        assertEquals(whole.state,second.state);assertEquals(whole.score,first.score+second.score,1e-6f)
        assertEquals(PinyinLanguageScorer.context3("乙丙丁"),whole.state)
        assertEquals(PinyinLanguageScorer.context3("𠀀乙丙"),PinyinLanguageScorer.context3("前文。𠀀乙丙"))
        assertEquals(PinyinLanguageScorer.context3("乙丙"),PinyinLanguageScorer.context3("甲。乙丙"))
        assertEquals(PinyinLanguageScorer.context3(""),scorer.extend(initial,"甲。") .state)
        assertEquals(PinyinLanguageScorer.context3('甲'.code),PinyinLanguageScorer.context3("甲"))
    }

    @Test fun thirdContextSeparatesOtherwiseIdenticalSuffixScoresAndCacheEntries() {
        val model=model()
        val a=PinyinLanguageScorer(PinyinLanguageBinding(model,1f),"ding",PinyinLanguageScorer.context3("甲乙丙"))
        val b=PinyinLanguageScorer(PinyinLanguageBinding(model,1f),"ding",PinyinLanguageScorer.context3("戊乙丙"))
        assertTrue(a.feature("丁")>b.feature("丁"))
        assertTrue(a.extend(PinyinLanguageScorer.context3("甲乙丙"),"丁").score >
            a.extend(PinyinLanguageScorer.context3("戊乙丙"),"丁").score)
    }
}
