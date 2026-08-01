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
) {
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
        )
    }
}
