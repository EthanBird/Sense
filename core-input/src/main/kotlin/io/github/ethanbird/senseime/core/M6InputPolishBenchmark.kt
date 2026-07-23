package io.github.ethanbird.senseime.core

import java.io.File
import java.time.Instant
import java.util.Locale
import kotlin.math.ceil
import kotlin.system.measureNanoTime

/**
 * Correctness and bounded-latency gate for M6 English composition and
 * late-ranked semantic Emoji/symbol candidates.
 *
 * Candidate-strip physics, continuous Emoji scrolling and the categorized
 * symbol panel are pure ime-ui contracts and remain blocking unit tests in the
 * same CI verify job.
 */
object M6InputPolishBenchmark {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2) {
            "Usage: M6InputPolishBenchmark <english-lexicon> <report>"
        }
        val englishFile = File(args[0])
        val report = File(args[1])
        report.parentFile?.mkdirs()

        val english = englishFile.inputStream().buffered().use(EnglishLexicon::load)
        verifyEnglishComposition(english)
        verifySemanticCandidates()

        repeat(WARMUP_COUNT) {
            newHostSession(english)
            SemanticCandidateCatalog.suggest("you", 8)
            mergeYouCandidates()
        }

        val englishSamples = LongArray(SAMPLE_COUNT) {
            measureNanoTime {
                repeat(ENGLISH_SESSIONS) {
                    check(newHostSession(english).candidates.first().text == "host")
                }
            }
        }
        val semanticSamples = LongArray(SAMPLE_COUNT) {
            measureNanoTime {
                repeat(SEMANTIC_LOOKUPS) {
                    check(SemanticCandidateCatalog.suggest("you", 8).size == 2)
                }
            }
        }
        val mixerSamples = LongArray(SAMPLE_COUNT) {
            measureNanoTime {
                repeat(MIXER_LOOKUPS) {
                    check(mergeYouCandidates()[6].text == "🈶")
                }
            }
        }

        val englishP95 = perOperation(englishSamples, ENGLISH_SESSIONS)
        val semanticP95 = perOperation(semanticSamples, SEMANTIC_LOOKUPS)
        val mixerP95 = perOperation(mixerSamples, MIXER_LOOKUPS)
        check(englishP95 <= ENGLISH_SESSION_P95_GATE_NS) {
            "English composition p95 regression: $englishP95 ns"
        }
        check(semanticP95 <= SEMANTIC_LOOKUP_P95_GATE_NS) {
            "Semantic lookup p95 regression: $semanticP95 ns"
        }
        check(mixerP95 <= SEMANTIC_MIXER_P95_GATE_NS) {
            "Semantic mixer p95 regression: $mixerP95 ns"
        }

        report.writeText(
            """
            {
              "schemaVersion": 1,
              "stage": "M6-input-polish",
              "generatedAt": "${Instant.now()}",
              "correctness": {
                "englishHost": ["host", "hosts", "hostile"],
                "finalBackspaceEditorReplacement": "",
                "emoji": {
                  "ji": "🐔",
                  "yao": "💊",
                  "you": "🈶",
                  "suo": "🔒"
                },
                "symbols": {
                  "oumu": "Ω",
                  "pai": "π",
                  "zuo": "←",
                  "you": "→"
                },
                "youCombinedOrder": ["🈶", "→"],
                "semanticInsertionIndex": 6,
                "primaryPrefixPreserved": 6
              },
              "englishComposition": {
                "p95Ns": ${format(englishP95)},
                "gateNs": ${format(ENGLISH_SESSION_P95_GATE_NS)}
              },
              "semanticLookup": {
                "p95Ns": ${format(semanticP95)},
                "gateNs": ${format(SEMANTIC_LOOKUP_P95_GATE_NS)}
              },
              "semanticMixer": {
                "p95Ns": ${format(mixerP95)},
                "gateNs": ${format(SEMANTIC_MIXER_P95_GATE_NS)}
              }
            }
            """.trimIndent() + "\n",
        )
        println("M6 input-polish benchmark written to ${report.absolutePath}")
    }

    private fun verifyEnglishComposition(english: EnglishLexicon) {
        val session = newHostSession(english)
        check(session.candidates.take(3).map(Candidate::text) == listOf("host", "hosts", "hostile")) {
            "English-mode host order regression: ${session.candidates.take(8)}"
        }
        val currentRevision = session.revision
        check(session.select(currentRevision - 1, 0) == null) {
            "A stale English candidate revision must not commit"
        }
        check(session.select(currentRevision, 0)?.text == "host")
        repeat(4) { check(session.backspace()) }
        check(session.composing.isEmpty())
        check(editorComposingTextUpdate(session.composing).text.isEmpty()) {
            "Deleting the final composing letter must replace the editor composing span with empty text"
        }
    }

    private fun verifySemanticCandidates() {
        val expected = linkedMapOf(
            "ji" to listOf("🐔" to SemanticCandidateKind.EMOJI),
            "yao" to listOf("💊" to SemanticCandidateKind.EMOJI),
            "you" to listOf(
                "🈶" to SemanticCandidateKind.EMOJI,
                "→" to SemanticCandidateKind.SYMBOL,
            ),
            "suo" to listOf("🔒" to SemanticCandidateKind.EMOJI),
            "oumu" to listOf("Ω" to SemanticCandidateKind.SYMBOL),
            "pai" to listOf("π" to SemanticCandidateKind.SYMBOL),
            "zuo" to listOf("←" to SemanticCandidateKind.SYMBOL),
        )
        expected.forEach { (pinyin, values) ->
            val actual = SemanticCandidateCatalog.suggest(pinyin, 8)
            check(actual.map { it.candidate.text to it.kind } == values) {
                "$pinyin semantic regression: $actual"
            }
            check(actual.all { it.preferredInsertionIndex == SEMANTIC_INSERTION_INDEX }) {
                "$pinyin semantic candidates must stay late in the first row"
            }
        }

        val merged = mergeYouCandidates()
        check(merged.take(6).map(Candidate::text) == primaryCandidates().take(6).map(Candidate::text))
        check(merged.slice(6..7).map(Candidate::text) == listOf("🈶", "→")) {
            "Semantic insertion order regression: $merged"
        }
        val survivingPrimary = merged.filter { it.text.startsWith("主") }
        check(survivingPrimary == primaryCandidates()) {
            "Semantic mixing must preserve the primary candidate order"
        }
    }

    private fun newHostSession(english: EnglishLexicon): EnglishInputSession =
        EnglishInputSession(english).also { session ->
            "host".forEach { character -> check(session.type(character)) }
        }

    private fun mergeYouCandidates(): List<Candidate> =
        SemanticCandidateMixer.merge(
            primary = primaryCandidates(),
            semantic = SemanticCandidateCatalog.suggest("you", 8),
            limit = 16,
        )

    private fun primaryCandidates(): List<Candidate> =
        List(PRIMARY_CANDIDATE_COUNT) { index -> Candidate(text = "主$index", score = 100f - index) }

    private fun perOperation(samples: LongArray, operations: Int): Double {
        val sorted = samples.sorted()
        val index = (ceil(0.95 * sorted.size).toInt() - 1).coerceIn(0, sorted.lastIndex)
        return sorted[index].toDouble() / operations
    }

    private fun format(value: Double): String = "%.2f".format(Locale.US, value)

    private const val SAMPLE_COUNT = 7
    private const val WARMUP_COUNT = 200
    private const val ENGLISH_SESSIONS = 1_000
    private const val SEMANTIC_LOOKUPS = 20_000
    private const val MIXER_LOOKUPS = 10_000
    private const val PRIMARY_CANDIDATE_COUNT = 8
    private const val SEMANTIC_INSERTION_INDEX = 6
    private const val ENGLISH_SESSION_P95_GATE_NS = 1_000_000.0
    private const val SEMANTIC_LOOKUP_P95_GATE_NS = 250_000.0
    private const val SEMANTIC_MIXER_P95_GATE_NS = 250_000.0
}
