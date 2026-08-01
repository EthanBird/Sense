package io.github.ethanbird.senseime.core

import java.io.InputStream
import kotlin.math.ln

enum class WubiMatchKind {
    EXACT,
    COMPLETION,
}

data class WubiCandidate(
    val text: String,
    val code: String,
    val weight: Long,
    val matchKind: WubiMatchKind,
) {
    val score: Float
        get() = ln(weight.toDouble() + 1.0).toFloat()
}

data class WubiLookup(
    val inputCode: String,
    val exact: List<WubiCandidate>,
    val completions: List<WubiCandidate>,
) {
    val candidates: List<WubiCandidate>
        get() = exact + completions
}

data class WubiLexiconMetrics(
    val assetBytes: Int,
    val exactGroups: Int,
    val prefixGroups: Int,
    val reverseEntries: Int,
    /** Byte arrays plus primitive indexes; excludes small JVM object headers. */
    val estimatedRetainedBytes: Long,
)

/** Read-only SWBX/1 table with binary-search exact, bounded completion and reverse indexes. */
class Wubi86Lexicon private constructor(
    private val data: ByteArray,
    private val exactCodes: IntArray,
    private val exactOffsets: IntArray,
    private val prefixCodes: IntArray,
    private val prefixOffsets: IntArray,
    private val reverseCodePoints: IntArray,
    private val reverseOffsets: IntArray,
) {
    val metrics: WubiLexiconMetrics
        get() = WubiLexiconMetrics(
            assetBytes = data.size,
            exactGroups = exactCodes.size,
            prefixGroups = prefixCodes.size,
            reverseEntries = reverseCodePoints.size,
            estimatedRetainedBytes = data.size.toLong() +
                (exactCodes.size.toLong() + exactOffsets.size +
                    prefixCodes.size + prefixOffsets.size +
                    reverseCodePoints.size + reverseOffsets.size) * Int.SIZE_BYTES,
        )

    fun lookup(rawCode: String, limit: Int = 32): WubiLookup {
        if (limit <= 0) return WubiLookup(rawCode, emptyList(), emptyList())
        val code = normalizeCode(rawCode) ?: return WubiLookup(rawCode, emptyList(), emptyList())
        val packed = pack(code)
        val exact = find(exactCodes, packed)
            .takeIf { it >= 0 }
            ?.let { readCandidates(exactOffsets[it], WubiMatchKind.EXACT, limit, null) }
            .orEmpty()
        if (exact.size >= limit || code.length == WubiComposition.MAX_CODE_LENGTH) {
            return WubiLookup(code, exact, emptyList())
        }
        val seen = HashSet<String>(exact.size * 2 + 1)
        exact.forEach { seen += it.text }
        val completions = find(prefixCodes, packed)
            .takeIf { it >= 0 }
            ?.let {
                readCandidates(
                    prefixOffsets[it],
                    WubiMatchKind.COMPLETION,
                    limit - exact.size,
                    seen,
                )
            }
            .orEmpty()
        return WubiLookup(code, exact, completions)
    }

    fun exact(rawCode: String, limit: Int = 128): List<WubiCandidate> {
        if (limit <= 0) return emptyList()
        val code = normalizeCode(rawCode) ?: return emptyList()
        val index = find(exactCodes, pack(code))
        return if (index < 0) emptyList() else {
            readCandidates(exactOffsets[index], WubiMatchKind.EXACT, limit, null)
        }
    }

    fun codesFor(codePoint: Int): List<String> {
        val index = reverseCodePoints.binarySearch(codePoint)
        if (index < 0) return emptyList()
        var offset = reverseOffsets[index]
        check(readU32(offset).toInt() == codePoint)
        offset += 4
        val count = readU8(offset++)
        return ArrayList<String>(count).apply {
            repeat(count) {
                val length = readU8(offset++)
                add(readAscii(offset, length))
                offset += length
            }
        }
    }

    private fun readCandidates(
        recordOffset: Int,
        matchKind: WubiMatchKind,
        limit: Int,
        seen: MutableSet<String>?,
    ): List<WubiCandidate> {
        var offset = recordOffset
        val groupCodeLength = readU8(offset++)
        offset += groupCodeLength
        val count = readU16(offset)
        offset += 2
        val result = ArrayList<WubiCandidate>(minOf(count, limit))
        repeat(count) {
            val codeLength = readU8(offset++)
            val code = readAscii(offset, codeLength)
            offset += codeLength
            val textLength = readU16(offset)
            offset += 2
            val text = data.decodeToString(offset, offset + textLength)
            offset += textLength
            val weight = readU32(offset)
            offset += 4
            if (result.size < limit && (seen == null || seen.add(text))) {
                result += WubiCandidate(text, code, weight, matchKind)
            }
        }
        return result
    }

    private fun readAscii(offset: Int, length: Int): String =
        data.decodeToString(offset, offset + length)

    private fun readU8(offset: Int): Int = data[offset].toInt() and 0xFF

    private fun readU16(offset: Int): Int =
        (readU8(offset) shl 8) or readU8(offset + 1)

    private fun readU32(offset: Int): Long =
        (readU8(offset).toLong() shl 24) or
            (readU8(offset + 1).toLong() shl 16) or
            (readU8(offset + 2).toLong() shl 8) or
            readU8(offset + 3).toLong()

    companion object {
        private const val HEADER_SIZE = 18
        private const val VERSION = 1
        private const val MIN_GROUP_RECORD_BYTES = 13
        private const val MIN_REVERSE_RECORD_BYTES = 7

        fun load(stream: InputStream): Wubi86Lexicon = fromBytes(stream.readBytes())

        fun fromBytes(data: ByteArray): Wubi86Lexicon {
            val cursor = Cursor(data)
            require(cursor.remaining >= HEADER_SIZE) { "Wubi lexicon header is truncated" }
            require(cursor.bytes(4).contentEquals(byteArrayOf('S'.code.toByte(), 'W'.code.toByte(), 'B'.code.toByte(), 'X'.code.toByte()))) {
                "Wubi lexicon magic is invalid"
            }
            require(cursor.u16() == VERSION) { "Unsupported Wubi lexicon version" }
            val exactCount = cursor.count("exact")
            val prefixCount = cursor.count("prefix")
            val reverseCount = cursor.count("reverse")
            require(exactCount <= cursor.remaining / MIN_GROUP_RECORD_BYTES) {
                "Wubi exact record count exceeds the asset boundary"
            }
            require(prefixCount <= cursor.remaining / MIN_GROUP_RECORD_BYTES) {
                "Wubi prefix record count exceeds the asset boundary"
            }
            require(reverseCount <= cursor.remaining / MIN_REVERSE_RECORD_BYTES) {
                "Wubi reverse record count exceeds the asset boundary"
            }
            require(
                exactCount.toLong() + prefixCount.toLong() + reverseCount.toLong() <=
                    cursor.remaining.toLong() / MIN_REVERSE_RECORD_BYTES,
            ) { "Wubi record counts exceed the asset boundary" }
            val exactCodes = IntArray(exactCount)
            val exactOffsets = IntArray(exactCount)
            repeat(exactCount) { index ->
                exactOffsets[index] = cursor.offset
                exactCodes[index] = cursor.group(previous = exactCodes.getOrNull(index - 1), prefix = false)
            }
            val prefixCodes = IntArray(prefixCount)
            val prefixOffsets = IntArray(prefixCount)
            repeat(prefixCount) { index ->
                prefixOffsets[index] = cursor.offset
                prefixCodes[index] = cursor.group(previous = prefixCodes.getOrNull(index - 1), prefix = true)
            }
            val reverseCodePoints = IntArray(reverseCount)
            val reverseOffsets = IntArray(reverseCount)
            repeat(reverseCount) { index ->
                reverseOffsets[index] = cursor.offset
                val codePoint = cursor.u32().toInt()
                require(index == 0 || codePoint > reverseCodePoints[index - 1]) {
                    "Wubi reverse index is not strictly sorted"
                }
                reverseCodePoints[index] = codePoint
                val codeCount = cursor.u8()
                require(codeCount in 1..8) { "Wubi reverse code count is invalid" }
                repeat(codeCount) {
                    val codeLength = cursor.u8()
                    require(codeLength in 1..WubiComposition.MAX_CODE_LENGTH) {
                        "Wubi reverse code length is invalid"
                    }
                    cursor.requireCode(codeLength)
                }
            }
            require(cursor.remaining == 0) { "Wubi lexicon has trailing bytes" }
            return Wubi86Lexicon(
                data,
                exactCodes,
                exactOffsets,
                prefixCodes,
                prefixOffsets,
                reverseCodePoints,
                reverseOffsets,
            )
        }

        private fun normalizeCode(value: String): String? {
            if (value.isEmpty() || value.length > WubiComposition.MAX_CODE_LENGTH) return null
            val normalized = value.lowercase()
            return normalized.takeIf { code -> code.all(WubiComposition::isCodeCharacter) }
        }

        private fun pack(code: String): Int {
            var packed = 0
            code.forEachIndexed { index, character ->
                packed = packed or (character.code shl (24 - index * 8))
            }
            return packed
        }

        private fun find(values: IntArray, target: Int): Int = values.binarySearch(target)

        private class Cursor(private val data: ByteArray) {
            var offset = 0
                private set
            val remaining: Int
                get() = data.size - offset

            fun bytes(count: Int): ByteArray {
                requireAvailable(count)
                return data.copyOfRange(offset, offset + count).also { offset += count }
            }

            fun u8(): Int {
                requireAvailable(1)
                return data[offset++].toInt() and 0xFF
            }

            fun u16(): Int = (u8() shl 8) or u8()

            fun u32(): Long =
                (u8().toLong() shl 24) or
                    (u8().toLong() shl 16) or
                    (u8().toLong() shl 8) or
                    u8().toLong()

            fun count(label: String): Int {
                val value = u32()
                require(value in 1..Int.MAX_VALUE.toLong()) { "Wubi $label record count is invalid" }
                return value.toInt()
            }

            fun group(previous: Int?, prefix: Boolean): Int {
                val codeLength = u8()
                require(codeLength in 1..WubiComposition.MAX_CODE_LENGTH) {
                    "Wubi group code length is invalid"
                }
                val codeStart = offset
                requireCode(codeLength)
                val packed = packAscii(data, codeStart, codeLength)
                require(previous == null || packed > previous) { "Wubi group index is not strictly sorted" }
                val candidateCount = u16()
                require(candidateCount in 1..MAX_GROUP_CANDIDATES) {
                    "Wubi candidate count is invalid"
                }
                repeat(candidateCount) {
                    val candidateCodeLength = u8()
                    require(candidateCodeLength in 1..WubiComposition.MAX_CODE_LENGTH) {
                        "Wubi candidate code length is invalid"
                    }
                    val candidateCodeStart = offset
                    requireCode(candidateCodeLength)
                    if (prefix) {
                        require(startsWith(data, candidateCodeStart, candidateCodeLength, codeStart, codeLength)) {
                            "Wubi completion does not match its prefix"
                        }
                    } else {
                        require(candidateCodeLength == codeLength && startsWith(data, candidateCodeStart, candidateCodeLength, codeStart, codeLength)) {
                            "Wubi exact candidate has a different code"
                        }
                    }
                    val textLength = u16()
                    require(textLength > 0) { "Wubi candidate text is empty" }
                    requireAvailable(textLength)
                    offset += textLength
                    u32()
                }
                return packed
            }

            fun requireCode(length: Int) {
                requireAvailable(length)
                repeat(length) { index ->
                    require((data[offset + index].toInt() and 0xFF) in 'a'.code..'y'.code) {
                        "Wubi code contains an invalid character"
                    }
                }
                offset += length
            }

            private fun requireAvailable(count: Int) {
                require(count >= 0 && offset <= data.size - count) { "Wubi lexicon record is truncated" }
            }

            private companion object {
                const val MAX_GROUP_CANDIDATES = 128

                fun packAscii(data: ByteArray, offset: Int, length: Int): Int {
                    var packed = 0
                    repeat(length) { index ->
                        packed = packed or ((data[offset + index].toInt() and 0xFF) shl (24 - index * 8))
                    }
                    return packed
                }

                fun startsWith(
                    data: ByteArray,
                    valueOffset: Int,
                    valueLength: Int,
                    prefixOffset: Int,
                    prefixLength: Int,
                ): Boolean {
                    if (valueLength < prefixLength) return false
                    repeat(prefixLength) { index ->
                        if (data[valueOffset + index] != data[prefixOffset + index]) return false
                    }
                    return true
                }
            }
        }
    }
}

class Wubi86Decoder(private val lexicon: Wubi86Lexicon) : RankedCandidateDecoder {
    override fun decode(composing: String, limit: Int): List<Candidate> =
        lexicon.lookup(composing, limit).candidates.map { value ->
            Candidate(
                text = value.text,
                score = value.score,
                matchKind = when (value.matchKind) {
                    WubiMatchKind.EXACT -> CandidateMatchKind.WUBI_EXACT
                    WubiMatchKind.COMPLETION -> CandidateMatchKind.WUBI_COMPLETION
                },
                canonicalCode = value.code,
            )
        }

    fun lookup(code: String, limit: Int = 32): WubiLookup = lexicon.lookup(code, limit)

    fun exact(code: String, limit: Int = 128): List<WubiCandidate> = lexicon.exact(code, limit)

    fun codesFor(codePoint: Int): List<String> = lexicon.codesFor(codePoint)
}
