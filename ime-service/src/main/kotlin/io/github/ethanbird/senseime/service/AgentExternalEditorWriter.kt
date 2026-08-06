package io.github.ethanbird.senseime.service

import android.view.inputmethod.InputConnection

/** Explicit bridge from an Agent answer back to the editor that owns the IME session. */
internal object AgentExternalEditorWriter {
    fun insert(connection: InputConnection?, text: String): Boolean {
        if (connection == null || text.isBlank()) return false
        return runCatching {
            connection.beginBatchEdit()
            try {
                connection.finishComposingText()
                connection.commitText(text, 1)
            } finally {
                connection.endBatchEdit()
            }
        }.getOrDefault(false)
    }
}
