package io.github.ethanbird.senseime.service

import android.app.Activity
import io.github.ethanbird.senseime.core.*
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PersistentUserSentenceLearningTest {
    private lateinit var activity: Activity

    @Before fun setUp() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.deleteDatabase("sense_user_lexicon.db")
    }

    @After fun tearDown() {
        activity.deleteDatabase("sense_user_lexicon.db")
    }

    @Test
    fun actualSqliteJournalRebuildsWordEdgesAndPersistsForget() {
        val base = file("pinyin_lexicon.bin").inputStream().use(PinyinDecoder::load)
        val syllables = PinyinSyllableSegmenter(file("pinyin_syllables.txt").readLines())
        val first = PersistentUserLexicon(activity)
        try {
            val adaptive = AdaptivePinyinDecoder(base, first, syllables)
            assertNotNull(adaptive.learn("zhinengti", Candidate("智能体", canonicalPinyin = "zhinengti", canonicalInitials = "znt")))
            assertEquals("智能体可以帮忙", adaptive.decode("zhinengtikeyibangmang", 255).first().text)
        } finally {
            first.close()
            assertTrue(first.awaitClosed(5, TimeUnit.SECONDS))
        }
        val second = PersistentUserLexicon(activity)
        try {
            assertTrue(second.matchFullPinyin("wozhinengti").isNotEmpty())
            val adaptive = AdaptivePinyinDecoder(base, second, syllables)
            assertEquals("智能体可以帮忙", adaptive.decode("zhinengtikeyibangmang", 255).first().text)
            assertTrue(second.forget("zhinengti", "智能体"))
        } finally {
            second.close()
            assertTrue(second.awaitClosed(5, TimeUnit.SECONDS))
        }
        val third = PersistentUserLexicon(activity)
        try {
            assertTrue(third.matchFullPinyin("wozhinengti").isEmpty())
            assertEquals(base.decode("zhinengtikeyibangmang", 255), AdaptivePinyinDecoder(base, third, syllables).decode("zhinengtikeyibangmang", 255))
        } finally {
            third.close()
            assertTrue(third.awaitClosed(5, TimeUnit.SECONDS))
        }
    }

    private fun file(name: String): File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .map { File(it, "ime-service/src/main/assets/$name") }.first { it.isFile }
}
