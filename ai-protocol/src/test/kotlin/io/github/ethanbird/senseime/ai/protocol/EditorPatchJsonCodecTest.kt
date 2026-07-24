package io.github.ethanbird.senseime.ai.protocol

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorPatchJsonCodecTest {
    @Test
    fun validDocumentRoundTripsDeterministically() {
        val patch = patch(text = "你好，\"Sense\"！\n第二行 😀")

        val encoded = EditorPatchJsonCodec.encode(patch)
        val decoded = EditorPatchJsonCodec.decode(encoded)

        assertEquals(patch, decoded.getOrThrow())
        assertEquals(encoded, EditorPatchJsonCodec.encode(decoded.getOrThrow()))
    }

    @Test
    fun fragmentedUtf8DocumentWaitsForFinishAndHandlesSplitCodePoint() {
        val encoded = EditorPatchJsonCodec.encode(patch(text = "中文😀"))
            .toByteArray(StandardCharsets.UTF_8)
        val chineseByte = encoded.indexOfFirst { (it.toInt() and 0x80) != 0 }
        val accumulator = EditorPatchJsonAccumulator()

        accumulator.append(encoded.copyOfRange(0, chineseByte + 1))
        accumulator.append(encoded.copyOfRange(chineseByte + 1, chineseByte + 2))
        accumulator.append(encoded.copyOfRange(chineseByte + 2, encoded.size))

        assertEquals("中文😀", accumulator.finish().getOrThrow().operation.text)
    }

    @Test
    fun duplicateRootPropertyIsRejected() {
        val document = validJson().replace(
            "\"request_id\":\"request-1\",",
            "\"request_id\":\"request-1\",\"request_id\":\"request-2\",",
        )

        assertFailure(document, ProtocolErrorCode.DUPLICATE_PROPERTY)
    }

    @Test
    fun duplicateNestedPropertyIsRejected() {
        val document = validJson().replace(
            "\"type\":\"replace\",",
            "\"type\":\"replace\",\"type\":\"no_change\",",
        )

        assertFailure(document, ProtocolErrorCode.DUPLICATE_PROPERTY)
    }

    @Test
    fun unknownRootAndOperationPropertiesAreRejected() {
        val rootUnknown = validJson().replace(
            "\"protocol\":",
            "\"surprise\":true,\"protocol\":",
        )
        val operationUnknown = validJson().replace(
            "\"type\":\"replace\",",
            "\"extra\":1,\"type\":\"replace\",",
        )

        assertFailure(rootUnknown, ProtocolErrorCode.UNKNOWN_PROPERTY)
        assertFailure(operationUnknown, ProtocolErrorCode.UNKNOWN_PROPERTY)
    }

    @Test
    fun incorrectTypesAndUnknownEnumsAreRejected() {
        val wrongType = validJson().replace(
            "\"request_id\":\"request-1\"",
            "\"request_id\":42",
        )
        val wrongEnum = validJson().replace(
            "\"target\":\"whole_field\"",
            "\"target\":\"cursor_magic\"",
        )

        assertFailure(wrongType, ProtocolErrorCode.TYPE_MISMATCH)
        assertFailure(wrongEnum, ProtocolErrorCode.UNKNOWN_ENUM)
    }

    @Test
    fun protocolConstantIsValidatedAfterStrictParsing() {
        val wrongProtocol = validJson().replace(
            SenseAiProtocol.PATCH_V1,
            "sense.editor.patch.v2",
        )

        assertFailure(wrongProtocol, ProtocolErrorCode.PROTOCOL_MISMATCH)
    }

    @Test
    fun nulAndUnpairedEscapedSurrogateAreRejected() {
        val nul = validJson().replace("最终文本", "before\\u0000after")
        val surrogate = validJson().replace("最终文本", "before\\uD800after")

        assertFailure(nul, ProtocolErrorCode.INVALID_TEXT)
        assertFailure(surrogate, ProtocolErrorCode.INVALID_TEXT)
    }

    @Test
    fun literalUnpairedSurrogateIsRejected() {
        val surrogate = validJson().replace("最终文本", "before\uD800after")

        assertFailure(surrogate, ProtocolErrorCode.INVALID_TEXT)
    }

    @Test
    fun trailingSecondJsonValueIsRejected() {
        assertFailure("${validJson()} {}", ProtocolErrorCode.TRAILING_CONTENT)
        assertFailure("${validJson()} true", ProtocolErrorCode.TRAILING_CONTENT)
    }

    @Test
    fun deeplyNestedInputStopsAtBoundedDepth() {
        val deeplyNested = "[".repeat(StrictLimits.MAX_DEPTH_FOR_TEST + 2) +
            "null" +
            "]".repeat(StrictLimits.MAX_DEPTH_FOR_TEST + 2)

        assertFailure(deeplyNested, ProtocolErrorCode.MALFORMED_JSON)
    }

    @Test
    fun flatMaliciousInputStopsAtBoundedNodeCount() {
        val tooManyNodes = buildString {
            append("""{"junk":[""")
            repeat(StrictLimits.MAX_NODES_FOR_TEST + 1) { index ->
                if (index > 0) append(',')
                append("null")
            }
            append("]}")
        }

        assertFailure(tooManyNodes, ProtocolErrorCode.DOCUMENT_TOO_LARGE)
    }

    @Test
    fun oversizedDirectDocumentIsRejectedBeforeParsing() {
        val oversized = " ".repeat(EditorPatchJsonCodec.MAX_DOCUMENT_CHARS + 1)

        assertFailure(oversized, ProtocolErrorCode.DOCUMENT_TOO_LARGE)
    }

    @Test
    fun absoluteReplacementLimitIsInclusive() {
        val atLimit = patch(text = "a".repeat(SenseAiProtocol.ABSOLUTE_MAX_OUTPUT_CHARS))
        val overLimitDocument = rawJson(
            text = "a".repeat(SenseAiProtocol.ABSOLUTE_MAX_OUTPUT_CHARS + 1),
        )

        assertEquals(atLimit, EditorPatchJsonCodec.decode(EditorPatchJsonCodec.encode(atLimit)).getOrThrow())
        assertFailure(overLimitDocument, ProtocolErrorCode.TEXT_TOO_LONG)
    }

    @Test
    fun oversizedFragmentedDocumentCannotGrowAccumulator() {
        val accumulator = EditorPatchJsonAccumulator(maxDocumentBytes = 8)

        assertThrows(DocumentTooLargeException::class.java) {
            accumulator.append(ByteArray(9))
        }
    }

    @Test
    fun invalidUtf8IsRejectedAtAccumulatorBoundary() {
        val invalidUtf8 = byteArrayOf(0x7b, 0x22, 0xC3.toByte(), 0x28, 0x22, 0x7d)

        val result = EditorPatchJsonAccumulator()
            .append(invalidUtf8)
            .finish()

        assertFailure(result, ProtocolErrorCode.INVALID_TEXT)
    }

    @Test
    fun accumulatorIsSingleUse() {
        val accumulator = EditorPatchJsonAccumulator().appendUtf8(validJson())
        accumulator.finish()

        assertThrows(IllegalStateException::class.java) {
            accumulator.finish()
        }
        assertThrows(IllegalStateException::class.java) {
            accumulator.appendUtf8("{}")
        }
    }

    @Test
    fun noChangeOmitsAllReplacementProperties() {
        val noChange = patch().copy(
            intent = EditorIntent.NO_CHANGE,
            operation = PatchOperationV1(PatchOperationType.NO_CHANGE),
        )

        val encoded = EditorPatchJsonCodec.encode(noChange)

        assertTrue(!encoded.contains("\"target\""))
        assertTrue(!encoded.contains("\"text\""))
        assertTrue(!encoded.contains("\"selection_after\""))
        assertEquals(noChange, EditorPatchJsonCodec.decode(encoded).getOrThrow())
    }

    private fun patch(text: String = "最终文本") = EditorPatchV1(
        requestId = "request-1",
        snapshotId = "snapshot-1",
        baseSha256 = "a".repeat(64),
        intent = EditorIntent.REWRITE,
        operation = PatchOperationV1(
            type = PatchOperationType.REPLACE,
            target = PatchTarget.WHOLE_FIELD,
            text = text,
            selectionAfter = SelectionAfter.END,
        ),
    )

    private fun validJson(): String = EditorPatchJsonCodec.encode(patch())

    private fun rawJson(text: String): String =
        """{"protocol":"${SenseAiProtocol.PATCH_V1}","request_id":"request-1","snapshot_id":"snapshot-1","base_sha256":"${"a".repeat(64)}","intent":"rewrite","operation":{"type":"replace","target":"whole_field","text":"$text","selection_after":"end"}}"""

    private fun assertFailure(document: String, expected: ProtocolErrorCode) {
        assertFailure(EditorPatchJsonCodec.decode(document), expected)
    }

    private fun assertFailure(result: PatchDecodeResult, expected: ProtocolErrorCode) {
        assertTrue(
            "Expected $expected but got $result",
            result is PatchDecodeResult.Failure &&
                result.errors.any { it.code == expected },
        )
    }

    /**
     * Kept in the test so changing the parser's intentionally small depth budget is reviewed.
     */
    private object StrictLimits {
        const val MAX_DEPTH_FOR_TEST = 32
        const val MAX_NODES_FOR_TEST = 4_096
    }
}
