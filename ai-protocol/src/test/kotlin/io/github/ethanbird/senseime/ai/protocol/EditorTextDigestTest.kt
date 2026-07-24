package io.github.ethanbird.senseime.ai.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorTextDigestTest {
    @Test
    fun emptyTextUsesCanonicalSha256() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            EditorTextDigest.sha256Utf8(""),
        )
    }

    @Test
    fun chineseAndEmojiAreHashedAsUtf8() {
        assertEquals(
            "e973a1c1b41c5c9f4fbac31fcc311536dfffd003bfb914580455170a599953fa",
            EditorTextDigest.sha256Utf8("中文😀"),
        )
    }

    @Test
    fun snapshotValidatorRejectsDigestFromDifferentText() {
        val snapshot = EditorSnapshotV1(
            requestId = "request-1",
            snapshotId = "snapshot-1",
            editorGeneration = 1,
            fieldIdentity = "field-1",
            capability = SnapshotCapability.FULL_DOCUMENT,
            text = "changed",
            selection = TextSelectionV1(7, 7),
            target = PatchTarget.WHOLE_FIELD,
            baseSha256 = EditorTextDigest.sha256Utf8("original"),
            capturedAtMonotonicMs = 1,
            truncated = false,
        )

        val result = ProtocolValidator.validate(snapshot)

        assertTrue(
            result.errors.any {
                it.code == ProtocolErrorCode.INVALID_HASH && it.path == "$.base_sha256"
            },
        )
    }
}
