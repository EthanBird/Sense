package io.github.ethanbird.senseime.service

import io.github.ethanbird.senseime.config.ChineseInputScheme
import io.github.ethanbird.senseime.core.Candidate
import io.github.ethanbird.senseime.core.CandidateMatchKind
import io.github.ethanbird.senseime.core.ChineseOnlyInputDecoder
import io.github.ethanbird.senseime.core.ContextualInputDecoder
import io.github.ethanbird.senseime.core.InputDecoder
import io.github.ethanbird.senseime.core.T9Composition
import io.github.ethanbird.senseime.core.T9AlternativeInputDecoder
import io.github.ethanbird.senseime.core.T9SyllableIndex
import io.github.ethanbird.senseime.core.Wubi86Decoder
import io.github.ethanbird.senseime.core.WubiComposition

internal data class AlternativeCompositionKey(
    val scheme: ChineseInputScheme,
    val schemeEpoch: Long,
    val localRevision: Long,
    val presentationRevision: Long,
    val rawCode: String,
)

internal data class AlternativeDecodeRequest(
    val key: AlternativeCompositionKey,
    val t9Composition: T9Composition?,
    val wubiComposition: WubiComposition?,
    val t9Index: T9SyllableIndex,
    val pinyinDecoder: InputDecoder,
    val pinyinDecoderGeneration: Long,
    val wubiDecoder: Wubi86Decoder?,
    val wubiCandidateDecoder: InputDecoder?,
    val wubiDecoderGeneration: Long,
    val leftContext: String,
    val limit: Int,
) {
    val dependsOnPinyinDecoder: Boolean
        get() = key.scheme == ChineseInputScheme.PINYIN_T9 ||
            (key.scheme == ChineseInputScheme.WUBI_86 && wubiComposition?.isReverseLookup == true)
    val dependsOnWubiDecoder: Boolean
        get() = key.scheme == ChineseInputScheme.WUBI_86
    val effectivePinyinGeneration: Long
        get() = if (dependsOnPinyinDecoder) pinyinDecoderGeneration else 0L
    val effectiveWubiGeneration: Long
        get() = if (dependsOnWubiDecoder) wubiDecoderGeneration else 0L
}

internal data class AlternativeDecoding(
    val key: AlternativeCompositionKey,
    val composingLabel: String,
    val candidates: List<Candidate>,
    val candidateLabels: List<String>,
) {
    init {
        require(candidates.size == candidateLabels.size)
    }
}

internal data class AlternativePresentation(
    val key: AlternativeCompositionKey?,
    val pinyinDecoderGeneration: Long,
    val wubiDecoderGeneration: Long,
    val decoding: AlternativeDecoding?,
    val retainedLabels: List<String>,
    val pending: Boolean,
)

internal data class AlternativeDecodeLaunch(
    val presentation: AlternativePresentation,
    val shouldDecode: Boolean,
    val stateChanged: Boolean,
)

/** Main-thread session that binds every result to scheme, epoch, revision and decoder generation. */
internal class AlternativeCandidateSession {
    var current = AlternativePresentation(null, 0, 0, null, emptyList(), pending = false)
        private set

    fun begin(request: AlternativeDecodeRequest): AlternativeDecodeLaunch {
        if (
            current.key == request.key &&
            current.pinyinDecoderGeneration == request.effectivePinyinGeneration &&
            current.wubiDecoderGeneration == request.effectiveWubiGeneration
        ) {
            return AlternativeDecodeLaunch(current, shouldDecode = false, stateChanged = false)
        }
        current = AlternativePresentation(
            key = request.key,
            pinyinDecoderGeneration = request.effectivePinyinGeneration,
            wubiDecoderGeneration = request.effectiveWubiGeneration,
            decoding = null,
            retainedLabels = if (request.key.rawCode.isEmpty()) emptyList() else current.retainedLabels,
            pending = request.key.rawCode.isNotEmpty(),
        )
        return AlternativeDecodeLaunch(
            current,
            shouldDecode = request.key.rawCode.isNotEmpty(),
            stateChanged = true,
        )
    }

    fun complete(
        request: AlternativeDecodeRequest,
        decoding: AlternativeDecoding,
        activePinyinGeneration: Long,
        activeWubiGeneration: Long,
    ): AlternativePresentation? {
        if (
            current.key != request.key ||
            current.pinyinDecoderGeneration != request.effectivePinyinGeneration ||
            current.wubiDecoderGeneration != request.effectiveWubiGeneration ||
            decoding.key != request.key ||
            (request.dependsOnPinyinDecoder &&
                request.pinyinDecoderGeneration != activePinyinGeneration) ||
            (request.dependsOnWubiDecoder &&
                request.wubiDecoderGeneration != activeWubiGeneration)
        ) {
            return null
        }
        current = AlternativePresentation(
            key = request.key,
            pinyinDecoderGeneration = request.effectivePinyinGeneration,
            wubiDecoderGeneration = request.effectiveWubiGeneration,
            decoding = decoding,
            retainedLabels = decoding.candidateLabels,
            pending = false,
        )
        return current
    }

    fun select(presentationRevision: Long, sourceIndex: Int): Candidate? {
        val decoding = current.decoding ?: return null
        if (current.pending || decoding.key.presentationRevision != presentationRevision) return null
        return decoding.candidates.getOrNull(sourceIndex)
    }

    fun clear() {
        current = AlternativePresentation(null, 0, 0, null, emptyList(), pending = false)
    }
}

/** Bounded worker-side decoder for T9 and Wubi; Android/editor state stays outside this module. */
internal object AlternativeInputDecoder {
    fun decode(
        request: AlternativeDecodeRequest,
        shouldContinue: () -> Boolean = { true },
    ): AlternativeDecoding = when (request.key.scheme) {
        ChineseInputScheme.PINYIN_T9 -> decodeT9(request, shouldContinue)
        ChineseInputScheme.WUBI_86 -> decodeWubi(request)
        ChineseInputScheme.PINYIN_QWERTY -> error("QWERTY Pinyin uses the progressive decoder")
    }

    fun decodeWhileCurrent(
        request: AlternativeDecodeRequest,
        shouldContinue: () -> Boolean,
    ): AlternativeDecoding = decode(request, shouldContinue)

    private fun decodeT9(
        request: AlternativeDecodeRequest,
        shouldContinue: () -> Boolean = { true },
    ): AlternativeDecoding {
        val composition = checkNotNull(request.t9Composition)
        val decoded = T9AlternativeInputDecoder.decode(
            composition = composition,
            pathSource = request.t9Index,
            pinyinDecoder = request.pinyinDecoder,
            leftContext = request.leftContext,
            limit = request.limit,
            shouldContinue = shouldContinue,
        )
        return AlternativeDecoding(
            key = request.key,
            composingLabel = decoded.composingLabel,
            candidates = decoded.candidates,
            candidateLabels = decoded.candidates.map(Candidate::text),
        )
    }

    private fun decodeWubi(request: AlternativeDecodeRequest): AlternativeDecoding {
        val composition = checkNotNull(request.wubiComposition)
        val reverseQuery = composition.reversePinyin
        if (reverseQuery != null) return decodeWubiReverse(request, composition, reverseQuery)
        val values = request.wubiCandidateDecoder?.decode(composition.code, request.limit).orEmpty()
        return AlternativeDecoding(
            key = request.key,
            composingLabel = composition.visibleCode,
            candidates = values,
            candidateLabels = values.map { value ->
                if (value.matchKind == CandidateMatchKind.WUBI_EXACT) {
                    value.text
                } else {
                    "${value.text} · ${value.canonicalCode.orEmpty()}"
                }
            },
        )
    }

    private fun decodeWubiReverse(
        request: AlternativeDecodeRequest,
        composition: WubiComposition,
        query: String,
    ): AlternativeDecoding {
        if (query.isEmpty()) {
            return AlternativeDecoding(request.key, composition.visibleCode, emptyList(), emptyList())
        }
        val previousCodePoint = request.leftContext
            .takeIf(String::isNotEmpty)
            ?.codePointBefore(request.leftContext.length)
        val values = decodeChinese(
            decoder = request.pinyinDecoder,
            query = query,
            previousCodePoint = previousCodePoint,
            limit = request.limit,
        ).map { candidate ->
            val code = request.wubiDecoder?.codesForText(candidate.text).orEmpty()
            candidate.copy(canonicalCode = code.takeIf(String::isNotEmpty)) to code
        }
        return AlternativeDecoding(
            key = request.key,
            composingLabel = composition.visibleCode,
            candidates = values.map { it.first },
            candidateLabels = values.map { (candidate, code) ->
                if (code.isEmpty()) candidate.text else "${candidate.text}〔$code〕"
            },
        )
    }

    private fun decodeChinese(
        decoder: InputDecoder,
        query: String,
        previousCodePoint: Int?,
        limit: Int,
    ): List<Candidate> = when {
        decoder is ChineseOnlyInputDecoder && previousCodePoint != null ->
            decoder.decodeChineseOnlyAfter(previousCodePoint, query, limit)
        decoder is ChineseOnlyInputDecoder -> decoder.decodeChineseOnly(query, limit)
        decoder is ContextualInputDecoder && previousCodePoint != null ->
            decoder.decodeAfter(previousCodePoint, query, limit)
        else -> decoder.decode(query, limit)
    }

    private fun Wubi86Decoder.codesForText(text: String): String = buildString {
        var offset = 0
        var count = 0
        while (offset < text.length && count < MAX_REVERSE_ANNOTATION_CHARACTERS) {
            val codePoint = text.codePointAt(offset)
            val code = codesFor(codePoint).firstOrNull()
            if (code != null) {
                if (isNotEmpty()) append(' ')
                append(code)
            }
            offset += Character.charCount(codePoint)
            count += 1
        }
    }

    private const val MAX_REVERSE_ANNOTATION_CHARACTERS = 4
}
