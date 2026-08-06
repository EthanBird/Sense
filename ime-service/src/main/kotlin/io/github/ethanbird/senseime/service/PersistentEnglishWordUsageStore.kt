package io.github.ethanbird.senseime.service

import android.content.Context
import android.content.SharedPreferences
import io.github.ethanbird.senseime.core.EnglishWordUsageStore
import io.github.ethanbird.senseime.core.LearnedEnglishWord
import io.github.ethanbird.senseime.core.MemoryEnglishWordUsageStore
import io.github.ethanbird.senseime.core.UserLearningEvidence

internal class PersistentEnglishWordUsageStore(context: Context) : EnglishWordUsageStore {
    private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    private val delegate = MemoryEnglishWordUsageStore(
        initial = load(preferences),
        onRecord = ::persist,
        maximumRecords = MAX_RECORDS,
    )

    override fun find(word: String): LearnedEnglishWord? = delegate.find(word)

    override fun record(
        word: String,
        evidence: UserLearningEvidence,
    ): LearnedEnglishWord = delegate.record(word, evidence)

    private fun persist(value: LearnedEnglishWord) {
        preferences.edit()
            .putString(
                value.word,
                "${value.useCount}|${value.lastUsedAtMillis}|${value.positiveEvidence}",
            )
            .apply()
    }

    private companion object {
        const val FILE_NAME = "english_word_usage_v1"
        const val MAX_RECORDS = 2_048

        fun load(preferences: SharedPreferences): List<LearnedEnglishWord> =
            preferences.all.mapNotNull { (word, encoded) ->
                val parts = (encoded as? String)?.split('|') ?: return@mapNotNull null
                if (parts.size != 3) return@mapNotNull null
                val count = parts[0].toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
                val timestamp = parts[1].toLongOrNull()?.takeIf { it >= 0 } ?: return@mapNotNull null
                val evidence = parts[2].toFloatOrNull()
                    ?.takeIf { it.isFinite() && it > 0f }
                    ?: return@mapNotNull null
                LearnedEnglishWord(word, count, timestamp, evidence)
            }
    }
}
