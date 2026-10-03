package io.github.ethanbird.senseime.core

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Assert.*
import org.junit.Test

class FourgramPinyinIntegrationTest {
    private val model=object:FourgramLanguageModel {
        override fun unigramLogProbability(next:Int)=-6f
        override fun logProbability(previous2:Int,previous1:Int,next:Int)=-6f
        override fun logProbabilityWithThirdContext(previous3:Int,previous2:Int,previous1:Int,next:Int):Float = when {
            previous3=='甲'.code && previous2=='乙'.code && previous1=='丙'.code && next=='是'.code -> -.1f
            previous3=='戊'.code && previous2=='乙'.code && previous1=='丙'.code && next=='诗'.code -> -.1f
            else -> -8f
        }
    }
    private fun base():PinyinDecoder {
        val records=mapOf("shi" to listOf("是","诗"),"wo" to listOf("我"),"ren" to listOf("人","仁"))
        val bytes=ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeBytes("SPLX");out.writeShort(3);out.writeInt(records.size)
            for((code,words) in records.toSortedMap()) {
                out.writeByte(code.length);out.writeBytes(code);out.writeByte(words.size)
                words.forEachIndexed { i,w ->
                    val text=w.toByteArray(Charsets.UTF_8);out.writeByte(text.size);out.write(text);out.writeInt(1000-i)
                    out.writeByte(1);out.writeBytes(code.take(1));out.writeByte(0)
                }
            }
        }
        return PinyinDecoder.fromBytes(bytes.toByteArray())
    }
    @Test fun thirdContextWorksBeforeSmallLimitAndPrefixCacheDoesNotCollapseHistories() {
        val decoder=base().withLanguageModel(model)
        for(prefix in listOf(false,true)) {
            assertEquals("是",decoder.decodeWithContext("甲乙丙","shi",1,prefix).first().text)
            assertEquals("诗",decoder.decodeWithContext("戊乙丙","shi",1,prefix).first().text)
            assertEquals("是",decoder.decodeWithContext("甲乙丙","shi",1,prefix).first().text)
            assertEquals("是",decoder.decodeWithContext("戊乙丙。","shi",1,prefix).first().text)
        }
    }
    @Test fun acceptedCompositionJoinsTheBoundedEditorSnapshotInOrder() {
        val d=AdaptivePinyinDecoder(base().withLanguageModel(model),MemoryUserLexicon(),PinyinSyllableSegmenter(setOf("shi","bing")))
        val c=PinyinComposition(listOf(AcceptedPinyinSegment("丙","bing")),"shi")
        assertEquals("诗",d.decodeProgressively(c,"戊乙",1).wholeCandidates.first().text)
        assertEquals("是",d.decodeProgressively(c,"甲乙",1).wholeCandidates.first().text)
    }
    @Test fun newlyLearnedWordStillUsesPersonalBackoffAndZeroWeightIsLegacy() {
        val base=base();val store=MemoryUserLexicon(clock={1000L});val segmenter=PinyinSyllableSegmenter(setOf("wo","shi","ren"))
        val a=AdaptivePinyinDecoder(base,store,segmenter);val b=AdaptivePinyinDecoder(base.withLanguageModel(model),store,segmenter)
        requireNotNull(b.learn("shiren",Candidate("诗仁",canonicalPinyin="shiren",canonicalInitials="sr")))
        assertEquals(a.decode("woshiren",12),b.decode("woshiren",12))
        for(q in listOf("shi","woshiren","woshiern"))assertEquals(base.decode(q,12),base.withLanguageModel(model,0f).decode(q,12))
    }
}
