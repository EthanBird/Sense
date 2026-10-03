package io.github.ethanbird.senseime.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Optional third-context capability; the original two-context API keeps its exact lower model. */
interface FourgramLanguageModel : CharacterLanguageModel {
    fun logProbabilityWithThirdContext(previous3: Int, previous2: Int, previous1: Int, next: Int): Float
}

/** SCQ4/1 sparse extension, tied by SHA-256 to an immutable SCNG/1 lower model. */
class BinaryFourgramLanguageModel private constructor(
    private val lower: BinaryCharacterLanguageModel,
    private val vocabulary: IntArray,
    private val contexts: LongArray,
    private val nextTokens: IntArray,
    private val probabilities: FloatArray,
    private val backoffContexts: LongArray,
    private val backoffs: FloatArray,
) : FourgramLanguageModel {
    val estimatedRetainedBytes: Long get() = lower.estimatedRetainedBytes + vocabulary.size*4L +
        contexts.size*16L + backoffContexts.size*12L
    override fun containsCodePoint(codePoint: Int) = lower.containsCodePoint(codePoint)
    override fun unigramLogProbability(next: Int) = lower.unigramLogProbability(next)
    override fun logProbability(previous2: Int, previous1: Int, next: Int) = lower.logProbability(previous2,previous1,next)

    private fun token(cp: Int) = if(cp==CharacterLanguageModel.BOS || vocabulary.binarySearch(cp)>=0)cp else CharacterLanguageModel.UNK
    override fun logProbabilityWithThirdContext(previous3: Int, previous2: Int, previous1: Int, next: Int): Float {
        require(next!=CharacterLanguageModel.BOS) { "BOS is context only" }
        val a=token(previous3);val b=token(previous2);val c=token(previous1);val w=token(next)
        val key=pack(a,b,c)
        var low=0;var high=contexts.lastIndex
        while(low<=high) {
            val middle=(low+high).ushr(1)
            val order=contexts[middle].compareTo(key).let { if(it==0)nextTokens[middle].compareTo(w) else it }
            if(order<0)low=middle+1 else if(order>0)high=middle-1 else return probabilities[middle]
        }
        val index=backoffContexts.binarySearch(key)
        val lowerScore=lower.logProbability(b,c,w)
        // Exact return, not even a +0, when there is no observed retained third context.
        return if(index<0)lowerScore else backoffs[index]+lowerScore
    }

    companion object {
        private const val HEADER=46
        private const val MAX_BYTES=16*1024*1024
        private const val MASK=0x1FFFFF
        private fun pack(a:Int,b:Int,c:Int)=(a.toLong() shl 42) or (b.toLong() shl 21) or c.toLong()
        fun fromBytes(lowerBytes: ByteArray, extension: ByteArray): BinaryFourgramLanguageModel {
            require(extension.size>=HEADER && extension.size.toLong()+lowerBytes.size<=MAX_BYTES) { "Fourgram model size outside budget" }
            val bytes=ByteBuffer.wrap(extension).order(ByteOrder.BIG_ENDIAN)
            require(bytes.int==0x53435134 && bytes.short.toInt()==1) { "Invalid SCQ4 version" }
            val expected=ByteArray(32);bytes.get(expected)
            require(expected.contentEquals(MessageDigest.getInstance("SHA-256").digest(lowerBytes))) { "Changed lower model" }
            val n=bytes.int;val m=bytes.int
            require(n>=0 && m>=0 && HEADER+n*16L+m*12L==extension.size.toLong()) { "Invalid fourgram counts" }
            val lower=BinaryCharacterLanguageModel.fromBytes(lowerBytes)
            val base=ByteBuffer.wrap(lowerBytes).order(ByteOrder.BIG_ENDIAN)
            val vocabulary=IntArray(base.getInt(6)){base.getInt(26+it*8)}
            fun context(key:Long):Boolean = key>=0 && listOf((key ushr 42).toInt(),((key ushr 21) and MASK.toLong()).toInt(),
                (key and MASK.toLong()).toInt()).all { it in 0..0x10FFFF && vocabulary.binarySearch(it)>=0 }
            fun score()=bytes.float.also { require(it.isFinite() && it in -80f..0f) { "Invalid fourgram score" } }
            val contexts=LongArray(n);val next=IntArray(n);val scores=FloatArray(n)
            repeat(n) { i ->
                contexts[i]=bytes.long;next[i]=bytes.int;scores[i]=score()
                require(context(contexts[i]) && vocabulary.binarySearch(next[i])>=0) { "Invalid fourgram reference" }
                require(i==0 || contexts[i]>contexts[i-1] || (contexts[i]==contexts[i-1] && next[i]>next[i-1])) { "Unsorted fourgram keys" }
            }
            val keys=LongArray(m);val backoffs=FloatArray(m)
            repeat(m) { i ->
                keys[i]=bytes.long;backoffs[i]=score()
                require(context(keys[i]) && (i==0 || keys[i]>keys[i-1])) { "Invalid fourgram backoff key" }
            }
            require(contexts.all { keys.binarySearch(it)>=0 }) { "Missing fourgram backoff" }
            return BinaryFourgramLanguageModel(lower,vocabulary,contexts,next,scores,keys,backoffs)
        }
    }
}
