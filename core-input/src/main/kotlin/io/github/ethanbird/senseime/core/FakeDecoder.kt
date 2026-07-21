package io.github.ethanbird.senseime.core

/**
 * M0 decoder used to freeze the UI-to-decoder contract before librime enters M2.
 */
class FakeDecoder : InputDecoder {
    private val dictionary = mapOf(
        "ni" to listOf("你", "呢", "泥", "拟"),
        "hao" to listOf("好", "号", "浩", "豪"),
        "nihao" to listOf("你好", "你号", "拟好"),
        "sense" to listOf("先思", "Sense"),
        "xiansi" to listOf("先思", "闲思", "先四"),
        "zhongwen" to listOf("中文", "中闻"),
    )

    override fun decode(composing: String, limit: Int): List<Candidate> {
        if (composing.isBlank() || limit <= 0) return emptyList()
        val normalized = composing.lowercase()
        val values = dictionary[normalized] ?: listOf(normalized)
        return values.take(limit).mapIndexed { index, text ->
            Candidate(text = text, score = 1f - index * 0.1f)
        }
    }
}

