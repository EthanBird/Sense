package io.github.ethanbird.senseime.core

import java.util.PriorityQueue

/** How a digit span contributes to a pinyin path. */
enum class T9PinyinSegmentKind {
    SYLLABLE,
    INITIAL,
    INCOMPLETE,
}

/** One indexed spelling edge over a half-open range of the raw digit stream. */
data class T9PinyinSegment(
    val spelling: String,
    val digitStart: Int,
    val digitEnd: Int,
    val kind: T9PinyinSegmentKind,
)

/** A complete, bounded interpretation of a T9 composition. */
data class T9PinyinPath(
    val segments: List<T9PinyinSegment>,
) {
    val canonical: String
        get() = segments.joinToString(separator = "") { it.spelling }

    val formatted: String
        get() = segments.joinToString(separator = "'") { it.spelling }

    val syllableEnds: List<Int>
        get() {
            var end = 0
            return segments.map { segment ->
                end += segment.spelling.length
                end
            }
        }
}

/** Bounded source of numeric-pinyin paths; injectable so decoding policy stays testable. */
fun interface T9PinyinPathSource {
    fun paths(composition: T9Composition, maxPaths: Int): List<T9PinyinPath>
}

/**
 * Digit trie generated from the canonical pinyin inventory.
 *
 * Search walks at most one trie branch per input offset and retains a bounded
 * beam. It never expands a digit into every possible letter combination.
 */
class T9SyllableIndex(syllables: Collection<String>) : T9PinyinPathSource {
    private val root = TrieNode()
    private var maximumSignatureLength = 0

    init {
        val inventory = syllables.asSequence()
            .map(::normalizeSpelling)
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()
            .toList()
        val initialUsage = buildSet {
            inventory.forEach { syllable ->
                add(syllable.first().toString())
                READABLE_DOUBLE_INITIALS.firstOrNull(syllable::startsWith)?.let(::add)
            }
        }.associateWith { initial -> inventory.count { it.startsWith(initial) } }
        val finalUsage = inventory.groupingBy(::pinyinFinal).eachCount()
        inventory.forEach { spelling ->
            add(
                spelling = spelling,
                kind = T9PinyinSegmentKind.SYLLABLE,
                inventoryPrior = (initialUsage[pinyinInitial(spelling)] ?: 0) +
                    (finalUsage[pinyinFinal(spelling)] ?: 0),
            )
        }
        val fullSpellings = inventory.toHashSet()
        val initials = initialUsage.keys
        initials.filterNot(fullSpellings::contains)
            .sorted()
            .forEach { initial ->
                add(
                    spelling = initial,
                    kind = T9PinyinSegmentKind.INITIAL,
                    inventoryPrior = initialUsage.getValue(initial),
                )
            }
        inventory.asSequence()
            .flatMap { syllable ->
                (2 until syllable.length).asSequence().map { length -> syllable.take(length) }
            }
            .filterNot(fullSpellings::contains)
            .filterNot(initials::contains)
            .distinct()
            .sorted()
            .forEach { prefix ->
                add(
                    spelling = prefix,
                    kind = T9PinyinSegmentKind.INCOMPLETE,
                    inventoryPrior = inventory.count { it.startsWith(prefix) },
                )
            }
    }

    fun paths(composition: T9Composition): List<T9PinyinPath> =
        paths(composition, DEFAULT_MAX_PATHS)

    override fun paths(
        composition: T9Composition,
        maxPaths: Int,
    ): List<T9PinyinPath> {
        if (
            maxPaths <= 0 ||
            composition.rawDigits.isEmpty() ||
            composition.rawDigits.length > PinyinInputLimits.MAX_COMPOSING_CODE_LENGTH
        ) {
            return emptyList()
        }
        val pathLimit = minOf(maxPaths, MAX_PATH_LIMIT)
        val digits = composition.rawDigits
        val beams = arrayOfNulls<PathBeam>(digits.length + 1)
        beams[0] = PathBeam(pathLimit).also { beam ->
            beam.retain(
                PathState(
                    tail = null,
                    segmentCount = 0,
                    inventoryPrior = 0,
                    incompleteSegments = 0,
                    initialSegments = 0,
                    fullCharacters = 0,
                ),
            )
        }

        for (start in digits.indices) {
            val states = beams[start]?.values ?: continue
            for (indexedEdge in exactEdgesAt(digits, start)) {
                val edge = indexedEdge.segment
                if (
                    edge.kind == T9PinyinSegmentKind.INCOMPLETE &&
                    edge.digitEnd != digits.length
                ) {
                    continue
                }
                if (crossesForcedJoint(composition.forcedJoints, start, edge.digitEnd)) {
                    continue
                }
                if (conflictsWithLockedEdge(edge, composition.lockedEdges)) continue
                for (state in states) {
                    retain(
                        beams = beams,
                        end = edge.digitEnd,
                        candidate = state.append(edge, indexedEdge.inventoryPrior),
                        maxPaths = pathLimit,
                    )
                }
            }
        }

        val completed = beams[digits.length] ?: return emptyList()
        return completed.ordered().map { T9PinyinPath(it.materializeSegments()) }
    }

    private fun add(
        spelling: String,
        kind: T9PinyinSegmentKind,
        inventoryPrior: Int,
    ) {
        val signature = digitsFor(spelling) ?: return
        maximumSignatureLength = maxOf(maximumSignatureLength, signature.length)
        var node = root
        signature.forEach { digit ->
            val childIndex = digit - '2'
            node = node.children[childIndex]
                ?: TrieNode().also { node.children[childIndex] = it }
        }
        val indexed = IndexedSpelling(spelling, kind, inventoryPrior)
        if (indexed !in node.spellings) node.spellings += indexed
    }

    private fun exactEdgesAt(digits: String, start: Int): List<IndexedEdge> {
        val edges = ArrayList<IndexedEdge>()
        var node = root
        val limit = minOf(digits.length, start + maximumSignatureLength)
        for (index in start until limit) {
            node = node.children[digits[index] - '2'] ?: break
            node.spellings.forEach { indexed ->
                edges += IndexedEdge(
                    segment = T9PinyinSegment(
                        spelling = indexed.spelling,
                        digitStart = start,
                        digitEnd = index + 1,
                        kind = indexed.kind,
                    ),
                    inventoryPrior = indexed.inventoryPrior,
                )
            }
        }
        return edges
    }

    private fun crossesForcedJoint(joints: Set<Int>, start: Int, end: Int): Boolean =
        joints.any { it > start && it < end }

    private fun conflictsWithLockedEdge(
        segment: T9PinyinSegment,
        lockedEdges: List<T9LockedEdge>,
    ): Boolean = lockedEdges.any { locked ->
        val overlaps = segment.digitStart < locked.digitEnd &&
            locked.digitStart < segment.digitEnd
        overlaps && !(
            segment.digitStart == locked.digitStart &&
                segment.digitEnd == locked.digitEnd &&
                segment.spelling == locked.spelling
            )
    }

    private fun retain(
        beams: Array<PathBeam?>,
        end: Int,
        candidate: PathState,
        maxPaths: Int,
    ) {
        val beam = beams[end] ?: PathBeam(maxPaths).also { beams[end] = it }
        beam.retain(candidate)
    }

    /** Maintains the exact best K states incrementally; the heap root is the current worst. */
    private class PathBeam(private val maximumSize: Int) {
        private val queue = PriorityQueue<PathState>(maximumSize, PATH_ORDER.reversed())

        val values: Collection<PathState>
            get() = queue

        fun retain(candidate: PathState) {
            if (queue.size < maximumSize) {
                queue += candidate
                return
            }
            val worst = queue.peek()
            if (PATH_ORDER.compare(candidate, worst) < 0) {
                queue.poll()
                queue += candidate
            }
        }

        fun ordered(): List<PathState> = queue.sortedWith(PATH_ORDER)
    }

    private data class TrieNode(
        val children: Array<TrieNode?> = arrayOfNulls(T9_KEY_COUNT),
        val spellings: MutableList<IndexedSpelling> = ArrayList(),
    )

    private data class IndexedSpelling(
        val spelling: String,
        val kind: T9PinyinSegmentKind,
        val inventoryPrior: Int,
    )

    private data class IndexedEdge(
        val segment: T9PinyinSegment,
        val inventoryPrior: Int,
    )

    private data class SegmentChain(
        val segment: T9PinyinSegment,
        val previous: SegmentChain?,
    )

    private class PathState(
        val tail: SegmentChain?,
        val segmentCount: Int,
        val inventoryPrior: Int,
        val incompleteSegments: Int,
        val initialSegments: Int,
        val fullCharacters: Int,
    ) {
        private var cachedLexicalKey: String? = null

        val lexicalKey: String
            get() = cachedLexicalKey ?: materializeSegments()
                .joinToString(separator = "'") { it.spelling }
                .also { cachedLexicalKey = it }

        fun append(segment: T9PinyinSegment, segmentPrior: Int): PathState =
            PathState(
                tail = SegmentChain(segment, tail),
                segmentCount = segmentCount + 1,
                inventoryPrior = inventoryPrior + segmentPrior,
                incompleteSegments = incompleteSegments +
                    if (segment.kind == T9PinyinSegmentKind.INCOMPLETE) 1 else 0,
                initialSegments = initialSegments +
                    if (segment.kind == T9PinyinSegmentKind.INITIAL) 1 else 0,
                fullCharacters = fullCharacters +
                    if (segment.kind == T9PinyinSegmentKind.SYLLABLE) segment.spelling.length else 0,
            )

        fun materializeSegments(): List<T9PinyinSegment> {
            if (segmentCount == 0) return emptyList()
            val values = arrayOfNulls<T9PinyinSegment>(segmentCount)
            var node = tail
            var index = segmentCount - 1
            while (node != null) {
                values[index--] = node.segment
                node = node.previous
            }
            return values.map { requireNotNull(it) }
        }
    }

    companion object {
        const val DEFAULT_MAX_PATHS = 32
        private const val MAX_PATH_LIMIT = 256
        private const val T9_KEY_COUNT = 8
        private val READABLE_DOUBLE_INITIALS = listOf("zh", "ch", "sh")

        private val PATH_ORDER =
            compareBy<PathState> { it.incompleteSegments }
                .thenBy { it.initialSegments }
                .thenByDescending { it.fullCharacters }
                .thenBy { it.segmentCount }
                .thenByDescending { it.inventoryPrior }
                .thenBy { it.lexicalKey }

        /** Encodes lowercase pinyin with the standard 2=ABC through 9=WXYZ map. */
        fun digitsFor(spelling: String): String? {
            val normalized = normalizeSpelling(spelling)
            if (normalized.isEmpty()) return null
            val result = CharArray(normalized.length)
            normalized.forEachIndexed { index, letter ->
                result[index] = when (letter) {
                    in 'a'..'c' -> '2'
                    in 'd'..'f' -> '3'
                    in 'g'..'i' -> '4'
                    in 'j'..'l' -> '5'
                    in 'm'..'o' -> '6'
                    in 'p'..'s' -> '7'
                    in 't'..'v' -> '8'
                    in 'w'..'z' -> '9'
                    else -> return null
                }
            }
            return result.concatToString()
        }

        private fun pinyinInitial(spelling: String): String {
            READABLE_DOUBLE_INITIALS.firstOrNull(spelling::startsWith)?.let { return it }
            return spelling.first().takeIf { it !in ZERO_INITIAL_VOWELS }?.toString().orEmpty()
        }

        private fun pinyinFinal(spelling: String): String =
            spelling.removePrefix(pinyinInitial(spelling))

        private fun normalizeSpelling(value: String): String {
            val trimmed = value.trim().lowercase()
            return trimmed.takeIf { it.isNotEmpty() && it.all { letter -> letter in 'a'..'z' } }
                .orEmpty()
        }

        private val ZERO_INITIAL_VOWELS = setOf('a', 'e', 'o')
    }
}
