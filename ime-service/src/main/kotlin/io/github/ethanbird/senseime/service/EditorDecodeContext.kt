package io.github.ethanbird.senseime.service

/** A trigram needs two Unicode scalars; InputConnection's length is in UTF-16 units. */
internal object EditorDecodeContext {
    const val MAX_UTF16_UNITS = 4

    fun retain(text: CharSequence?): String {
        if (text.isNullOrEmpty()) return ""
        var start = text.length
        repeat(2) {
            if (start > 0) start -= Character.charCount(Character.codePointBefore(text, start))
        }
        // Slice before conversion, even if a host returns more text than requested.
        // Punctuation is retained: the decoder owns its BOS boundary semantics.
        return text.subSequence(start, text.length).toString()
    }
}
