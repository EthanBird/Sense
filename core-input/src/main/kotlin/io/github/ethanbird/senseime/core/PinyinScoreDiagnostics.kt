package io.github.ethanbird.senseime.core

import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * Opt-in host evaluation only. No Candidate ABI changes, persistent traces, or input logging.
 * Evidence is accumulated where a score is produced, never inferred from the final residual.
 * The IME has no observer. Its paths carry a null evidence reference, not a feature vector.
 */
internal object PinyinScoreDiagnostics {
    enum class Feature {
        LOG_FREQUENCY, FALLBACK_TIER, UNIGRAM_MASS, NORMALIZATION_ANCHOR,
        INTERNAL_BIGRAM, WORD_BOUNDARY, INITIALS_LENGTH, MIXED_TYPED,
        COMPLETION, PERSONAL_BASE, PERSONAL_FLOOR, PERSONAL_ADJUSTMENT,
        SPELLING, CHARACTER_LM, CORRECTION_CALIBRATION, EXTERNAL_BIGRAM,
    }

    data class LexicalEdge(val text: String, val code: String, val weight: Long?, val tier: Int?)

    /** Immutable, including the private vector. A path owns its actual selected lexical edges. */
    class Evidence private constructor(
        private val values: DoubleArray,
        val edges: List<LexicalEdge>,
        val segments: Int,
        val normalizer: Float,
    ) {
        operator fun get(feature: Feature): Double = values[feature.ordinal]
        fun sum(): Double = values.sum()
        fun plus(feature: Feature, value: Float): Evidence = Evidence(values.copyOf().also {
            it[feature.ordinal] += value.toDouble()
        }, edges, segments, normalizer)
        fun append(other: Evidence): Evidence = Evidence(DoubleArray(values.size) {
            values[it] + other.values[it]
        }, edges + other.edges, segments + other.segments, normalizer)
        fun divide(divisor: Float): Evidence {
            require(divisor.isFinite() && divisor > 0f)
            return Evidence(DoubleArray(values.size) { values[it] / divisor }, edges, segments, divisor)
        }
        companion object {
            val EMPTY = Evidence(DoubleArray(Feature.entries.size), emptyList(), 0, 1f)
            fun edge(edge: LexicalEdge) = Evidence(DoubleArray(Feature.entries.size), listOf(edge), 1, 1f)
        }
    }

    data class Entry(val candidate: Candidate, val evidence: Evidence, val sourcePrior: Float) {
        val total: Float get() = candidate.score + sourcePrior
        // Float arithmetic groups operations differently from an additive Double feature vector.
        // Expose this small difference; never use it as a learned feature or a missing-score bucket.
        val arithmeticError: Double get() = candidate.score.toDouble() - evidence.sum()
    }
    data class Trace(val query: String, val seam: String, val exact: Boolean, val composed: Boolean,
        val entries: List<Entry>, val ranked: List<Candidate>)

    class Session {
        private val values = IdentityHashMap<Candidate, Evidence>()
        fun get(candidate: Candidate): Evidence = checkNotNull(values[candidate]) {
            "Missing actual score provenance: ${candidate.matchKind}"
        }
        fun put(candidate: Candidate, evidence: Evidence) {
            check(abs(candidate.score.toDouble() - evidence.sum()) <= 0.0002) {
                "Score provenance mismatch: ${candidate.matchKind}, score=${candidate.score}, sum=${evidence.sum()}"
            }
            values[candidate] = evidence
        }
        fun inherit(before: Candidate, after: Candidate) = put(after, get(before))
        fun add(before: Candidate, after: Candidate, feature: Feature, amount: Float) =
            put(after, get(before).plus(feature, amount))
        fun lexical(candidate: Candidate, code: String, weight: Long, tier: Int, frequency: Float, penalty: Float) =
            put(candidate, Evidence.edge(LexicalEdge(candidate.text, code, weight, tier))
                .plus(Feature.LOG_FREQUENCY, frequency).plus(Feature.FALLBACK_TIER, -penalty))
        fun normalize(before: Candidate, after: Candidate, mass: Float, divisor: Float) =
            put(after, get(before).plus(Feature.UNIGRAM_MASS, -mass).divide(divisor)
                .plus(Feature.NORMALIZATION_ANCHOR, mass))
        fun personal(original: Candidate?, after: Candidate, floor: Float, adjustment: Float) {
            val base = if (original == null) {
                Evidence.edge(LexicalEdge(after.text, after.canonicalPinyin.orEmpty(), null, null))
                    .plus(Feature.PERSONAL_BASE, floor)
            } else get(original).plus(Feature.PERSONAL_FLOOR, maxOf(0f, floor - original.score))
            put(after, base.plus(Feature.PERSONAL_ADJUSTMENT, adjustment))
        }
    }

    private class Scope(val observer: (Trace) -> Unit, var session: Session? = null)
    private val scope = ThreadLocal<Scope?>()
    // The production process never installs observers. Skip its per-edge ThreadLocal lookup.
    // A counter (not one boolean) also preserves nested and concurrent host observers.
    private val observers = AtomicInteger()
    val isObserving: Boolean get() = observers.get() > 0 && scope.get() != null
    val current: Session? get() = if (observers.get() == 0) null else scope.get()?.session

    fun <T> observe(observer: (Trace) -> Unit, block: () -> T): T {
        val previous = scope.get()
        scope.set(Scope(observer))
        observers.incrementAndGet()
        return try { block() } finally {
            if (previous == null) scope.remove() else scope.set(previous)
            observers.decrementAndGet()
        }
    }

    /** Called only for an explicit observer; every decode releases its identity table in finally. */
    fun <T> query(block: () -> T): T {
        val active = checkNotNull(scope.get())
        val previous = active.session
        active.session = Session()
        return try { block() } finally { active.session = previous }
    }

    fun record(query: String, seam: String, exact: Boolean, composed: Boolean,
        candidates: List<Candidate>, ranked: List<Candidate>) {
        if (observers.get() == 0) return
        val active = scope.get() ?: return
        val session = checkNotNull(active.session)
        active.observer(Trace(query, seam, exact, composed, candidates.map {
            Entry(it, session.get(it), CandidateRanker.sourcePrior(it.matchKind, exact, composed))
        }, ranked))
    }
}
