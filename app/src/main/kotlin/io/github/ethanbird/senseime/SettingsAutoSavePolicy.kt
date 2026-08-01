package io.github.ethanbird.senseime

/** Pure latest-request gate used to suppress lifecycle/focus duplicates without dropping reverts. */
internal object SettingsAutoSavePolicy {
    fun <T : Any> shouldEnqueue(
        lastRequestedValue: T?,
        requestedValue: T,
        lastCredentialSignature: Long?,
        requestedCredentialSignature: Long?,
        forceBarrier: Boolean = false,
    ): Boolean = forceBarrier ||
        requestedValue != lastRequestedValue ||
        requestedCredentialSignature != lastCredentialSignature

    /** Retains no credential text; this marker exists only for the short settings-screen lifetime. */
    fun credentialSignature(value: String): Long =
        (value.hashCode().toLong() shl 32) xor value.length.toLong()

    /**
     * A completed save may clear the secret editor only while it still contains the exact value
     * owned by that request. The user can resume typing during the I/O window, before the next
     * debounced request has entered the serial lane; clearing by generation alone would erase that
     * newer text.
     */
    fun shouldClearCredentialEditor(
        submittedValue: String,
        currentEditorValue: String,
    ): Boolean = submittedValue.isNotEmpty() && currentEditorValue == submittedValue
}
