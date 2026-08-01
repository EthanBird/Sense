package io.github.ethanbird.senseime.config

/** Chinese spelling/shape scheme. Geometry is derived separately by the keyboard surface. */
enum class ChineseInputScheme {
    PINYIN_QWERTY,
    PINYIN_T9,
    WUBI_86,
}

enum class WubiAutoCommitMode {
    OFF,
    UNIQUE_AT_4,
    RIME_STYLE,
}

/** Versioned preferences shared by the settings and isolated IME processes. */
data class ImePreferencesV1(
    val chineseInputScheme: ChineseInputScheme = ChineseInputScheme.PINYIN_QWERTY,
    val wubiAutoCommitMode: WubiAutoCommitMode = WubiAutoCommitMode.RIME_STYLE,
    val portraitKeyboardHeightDp: Int = KeyboardHeightPolicy.DEFAULT_PORTRAIT_HEIGHT_DP,
    val landscapeKeyboardHeightDp: Int = KeyboardHeightPolicy.DEFAULT_LANDSCAPE_HEIGHT_DP,
    val t9SideSymbols: List<String> = T9SideSymbolPolicy.DEFAULT_SYMBOLS,
) {
    init {
        KeyboardHeightPolicy.requireValid(portraitKeyboardHeightDp, "portraitKeyboardHeightDp")
        KeyboardHeightPolicy.requireValid(landscapeKeyboardHeightDp, "landscapeKeyboardHeightDp")
        T9SideSymbolPolicy.requireValid(t9SideSymbols)
    }

    val schemaVersion: Int
        get() = SCHEMA_VERSION

    companion object {
        const val SCHEMA_VERSION = 1
        val DEFAULT = ImePreferencesV1()
    }
}

/** Deterministic, dependency-free wire format kept small enough to re-read at every editor start. */
object ImePreferencesCodec {
    fun encode(value: ImePreferencesV1): ByteArray = buildString {
        append("schema_version=").append(value.schemaVersion).append('\n')
        append("chinese_input_scheme=").append(value.chineseInputScheme.name).append('\n')
        append("wubi_auto_commit_mode=").append(value.wubiAutoCommitMode.name).append('\n')
        append("portrait_keyboard_height_dp=").append(value.portraitKeyboardHeightDp).append('\n')
        append("landscape_keyboard_height_dp=").append(value.landscapeKeyboardHeightDp).append('\n')
        append("t9_side_symbols=").append(encodeSymbols(value.t9SideSymbols)).append('\n')
    }.encodeToByteArray()

    fun decode(bytes: ByteArray): ImePreferencesV1 {
        val values = LinkedHashMap<String, String>()
        bytes.decodeToString().lineSequence().forEach { line ->
            if (line.isBlank()) return@forEach
            val separator = line.indexOf('=')
            require(separator > 0 && separator < line.lastIndex) { "Malformed IME preference" }
            val key = line.substring(0, separator)
            require(values.put(key, line.substring(separator + 1)) == null) {
                "Duplicate IME preference: $key"
            }
        }
        require(values["schema_version"]?.toIntOrNull() == ImePreferencesV1.SCHEMA_VERSION) {
            "Unsupported IME preference schema"
        }
        return ImePreferencesV1(
            chineseInputScheme = values["chinese_input_scheme"]
                ?.let(ChineseInputScheme::valueOf)
                ?: ChineseInputScheme.PINYIN_QWERTY,
            wubiAutoCommitMode = values["wubi_auto_commit_mode"]
                ?.let(WubiAutoCommitMode::valueOf)
                ?: WubiAutoCommitMode.RIME_STYLE,
            portraitKeyboardHeightDp = values["portrait_keyboard_height_dp"]
                ?.toInt()
                ?: KeyboardHeightPolicy.DEFAULT_PORTRAIT_HEIGHT_DP,
            landscapeKeyboardHeightDp = values["landscape_keyboard_height_dp"]
                ?.toInt()
                ?: KeyboardHeightPolicy.DEFAULT_LANDSCAPE_HEIGHT_DP,
            t9SideSymbols = values["t9_side_symbols"]
                ?.let(::decodeSymbols)
                ?: T9SideSymbolPolicy.DEFAULT_SYMBOLS,
        )
    }

    private fun encodeSymbols(values: List<String>): String =
        T9SideSymbolPolicy.requireValid(values).joinToString(",") { value ->
            buildList {
                var offset = 0
                while (offset < value.length) {
                    val codePoint = value.codePointAt(offset)
                    add(codePoint.toString(16))
                    offset += Character.charCount(codePoint)
                }
            }.joinToString(".")
        }

    private fun decodeSymbols(encoded: String): List<String> = encoded.split(',').map { token ->
        require(token.isNotBlank()) { "Malformed T9 side symbol" }
        buildString {
            token.split('.').forEach { codePointToken ->
                val codePoint = codePointToken.toIntOrNull(16)
                require(
                    codePoint != null &&
                        Character.isValidCodePoint(codePoint) &&
                        codePoint !in Character.MIN_SURROGATE.code..Character.MAX_SURROGATE.code,
                ) { "Malformed T9 side symbol" }
                appendCodePoint(codePoint)
            }
        }
    }
}
