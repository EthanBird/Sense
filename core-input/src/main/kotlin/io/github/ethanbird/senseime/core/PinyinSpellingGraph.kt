package io.github.ethanbird.senseime.core

/**
 * One complete route through the weighted spelling graph.
 *
 * [canonical] is separator-free pinyin suitable for dictionary lookup.
 * [syllableEnds] retains the concrete graph edges, so a corrected route such
 * as `xi + an` cannot later be reinterpreted as the single syllable `xian`.
 */
data class PinyinSpellingPath(
    val canonical: String,
    val cost: Float,
    val syllableEnds: List<Int>,
    val firstEditOffset: Int,
) {
    val syllableCount: Int
        get() = syllableEnds.size
}

/**
 * Builds a bounded graph over typed pinyin and the canonical syllable inventory.
 *
 * Exact, abbreviated (through the lexicon), fuzzy and mobile-keyboard typo
 * routes can therefore coexist. Corrections are generated per syllable edge
 * instead of enumerating every possible character insertion for the whole
 * query.
 */
class PinyinSpellingGraph(syllables: Collection<String>) {
    private val inventory = syllables
        .asSequence()
        .map(PinyinSyllableSegmenter::normalize)
        .filter { it.isNotEmpty() && it.length <= MAX_SYLLABLE_LENGTH }
        .distinct()
        .sortedWith(compareBy<String> { it.first() }.thenByDescending { it.length }.thenBy { it })
        .toList()

    fun paths(
        rawInput: String,
        maxPaths: Int = DEFAULT_MAX_PATHS,
        maxCost: Float = DEFAULT_MAX_COST,
    ): List<PinyinSpellingPath> {
        if (maxPaths <= 0 || maxCost < 0f) return emptyList()
        val parsed = parse(rawInput)
        val query = parsed.code
        if (
            query.isEmpty() ||
            query.length > PinyinInputLimits.MAX_COMPOSING_CODE_LENGTH ||
            inventory.isEmpty()
        ) {
            return emptyList()
        }

        val edges = arrayOfNulls<List<Edge>>(query.length)
        val beams = arrayOfNulls<MutableList<PathState>>(query.length + 1)
        beams[0] = mutableListOf(
            PathState(
                canonical = "",
                cost = 0f,
                syllableEnds = emptyList(),
                firstEditOffset = NO_EDIT_OFFSET,
                firstEditTier = null,
            ),
        )

        query.indices.forEach { start ->
            val states = beams[start] ?: return@forEach
            prune(states, maxPaths)
            val outgoing = edges[start] ?: edgesAt(parsed, start).also { edges[start] = it }
            if (outgoing.isEmpty()) return@forEach
            states.forEach { state ->
                outgoing.forEach { edge ->
                    val nextCost = state.cost + edge.cost
                    if (nextCost > maxCost + EPSILON) return@forEach
                    val target = beams[edge.end] ?: mutableListOf<PathState>().also { beams[edge.end] = it }
                    target += PathState(
                        canonical = state.canonical + edge.syllable,
                        cost = nextCost,
                        syllableEnds = state.syllableEnds + (state.canonical.length + edge.syllable.length),
                        firstEditOffset = when {
                            state.firstEditOffset != NO_EDIT_OFFSET -> state.firstEditOffset
                            edge.editOffset != NO_EDIT_OFFSET -> edge.editOffset
                            else -> NO_EDIT_OFFSET
                        },
                        firstEditTier = state.firstEditTier
                            ?: edge.cost.takeIf { it > EPSILON }?.let(::edgeCostTier),
                    )
                    if (target.size >= maxPaths * PRUNE_MULTIPLIER) prune(target, maxPaths)
                }
            }
        }

        val completed = beams[query.length] ?: return emptyList()
        prune(completed, maxPaths)
        return completed
            .sortedWith(PATH_ORDER)
            .map {
                PinyinSpellingPath(
                    canonical = it.canonical,
                    cost = it.cost,
                    syllableEnds = it.syllableEnds,
                    firstEditOffset = it.firstEditOffset,
                )
            }
    }

    private fun edgesAt(parsed: ParsedInput, start: Int): List<Edge> {
        val query = parsed.code
        val best = HashMap<EdgeIdentity, Edge>()
        inventory.forEach { syllable ->
            val minimumConsumed = maxOf(1, syllable.length - 1)
            val maximumConsumed = minOf(query.length - start, syllable.length + 1)
            if (minimumConsumed > maximumConsumed) return@forEach
            for (consumed in minimumConsumed..maximumConsumed) {
                val end = start + consumed
                if (parsed.hasJointInside(start, end)) continue
                val edit = editCost(syllable, query, start, consumed)
                if (edit.cost > MAX_EDGE_COST) continue
                val edge = Edge(end, syllable, edit.cost, edit.editOffset)
                val key = EdgeIdentity(end, syllable)
                val previous = best[key]
                if (
                    previous == null ||
                    edge.cost < previous.cost ||
                    edge.cost == previous.cost && edge.editOffset > previous.editOffset
                ) {
                    best[key] = edge
                }
            }
        }
        val ordered = best.values.sortedWith(EDGE_ORDER)
        val retained = LinkedHashMap<EdgeIdentity, Edge>(MAX_EDGES_PER_OFFSET)
        EdgeCostTier.entries.forEach { tier ->
            ordered.asSequence()
                .filter { edge -> edgeCostTier(edge.cost) == tier }
                .take(MIN_EDGES_PER_COST_TIER)
                .forEach { edge -> retained[EdgeIdentity(edge.end, edge.syllable)] = edge }
        }
        ordered.forEach { edge ->
            if (retained.size >= MAX_EDGES_PER_OFFSET) return@forEach
            retained.putIfAbsent(EdgeIdentity(edge.end, edge.syllable), edge)
        }
        return retained.values.toList()
    }

    private fun editCost(
        expected: String,
        typed: String,
        offset: Int,
        typedLength: Int,
    ): EditResult = when {
        expected.length == typedLength -> equalLengthCost(expected, typed, offset)
        typedLength == expected.length + 1 -> extraTypedCharacterCost(expected, typed, offset, typedLength)
        typedLength + 1 == expected.length -> missingTypedCharacterCost(expected, typed, offset, typedLength)
        else -> editResult(Float.POSITIVE_INFINITY, NO_EDIT_OFFSET)
    }

    private fun equalLengthCost(expected: String, typed: String, offset: Int): EditResult {
        var first = -1
        var second = -1
        for (index in expected.indices) {
            if (expected[index] == typed[offset + index]) continue
            if (first < 0) {
                first = index
            } else if (second < 0) {
                second = index
            } else {
                return editResult(Float.POSITIVE_INFINITY, NO_EDIT_OFFSET)
            }
        }
        if (first < 0) return editResult(0f, NO_EDIT_OFFSET)
        if (second < 0) {
            return editResult(
                substitutionCost(expected[first], typed[offset + first]),
                offset + first,
            )
        }
        val cost = if (
            second == first + 1 &&
            expected[first] == typed[offset + second] &&
            expected[second] == typed[offset + first]
        ) {
            TRANSPOSITION_COST
        } else {
            Float.POSITIVE_INFINITY
        }
        return editResult(
            cost,
            if (cost.isFinite()) offset + first else NO_EDIT_OFFSET,
        )
    }

    private fun extraTypedCharacterCost(
        expected: String,
        typed: String,
        offset: Int,
        typedLength: Int,
    ): EditResult {
        var bestCost = Float.POSITIVE_INFINITY
        var bestOffset = NO_EDIT_OFFSET
        for (removed in 0 until typedLength) {
            var expectedIndex = 0
            var matches = true
            for (typedIndex in 0 until typedLength) {
                if (typedIndex == removed) continue
                if (expected[expectedIndex++] != typed[offset + typedIndex]) {
                    matches = false
                    break
                }
            }
            if (matches) {
                val extra = typed[offset + removed]
                val repeated = removed > 0 && typed[offset + removed - 1] == extra ||
                    removed + 1 < typedLength && typed[offset + removed + 1] == extra
                val cost = if (repeated) {
                    REPEATED_KEY_COST
                } else {
                    insertionDeletionCost(extra, typed.getOrNull(offset + removed - 1))
                }
                val absoluteOffset = offset + removed
                if (
                    cost < bestCost ||
                    cost == bestCost && absoluteOffset > bestOffset
                ) {
                    bestCost = cost
                    bestOffset = absoluteOffset
                }
            }
        }
        return editResult(bestCost, bestOffset)
    }

    private fun missingTypedCharacterCost(
        expected: String,
        typed: String,
        offset: Int,
        typedLength: Int,
    ): EditResult {
        var bestCost = Float.POSITIVE_INFINITY
        var bestOffset = NO_EDIT_OFFSET
        for (missing in expected.indices) {
            var typedIndex = 0
            var matches = true
            for (expectedIndex in expected.indices) {
                if (expectedIndex == missing) continue
                if (typedIndex >= typedLength || expected[expectedIndex] != typed[offset + typedIndex++]) {
                    matches = false
                    break
                }
            }
            if (matches) {
                val cost = insertionDeletionCost(expected[missing], expected.getOrNull(missing - 1))
                val absoluteOffset = offset + minOf(missing, typedLength)
                if (
                    cost < bestCost ||
                    cost == bestCost && absoluteOffset > bestOffset
                ) {
                    bestCost = cost
                    bestOffset = absoluteOffset
                }
            }
        }
        return editResult(bestCost, bestOffset)
    }

    private fun editResult(cost: Float, editOffset: Int): EditResult =
        EditResult(
            (cost.toRawBits().toLong() shl Int.SIZE_BITS) or
                (editOffset.toLong() and 0xFFFF_FFFFL),
        )

    private fun substitutionCost(expected: Char, typed: Char): Float = when {
        expected == typed -> 0f
        expected to typed in FUZZY_SUBSTITUTIONS || typed to expected in FUZZY_SUBSTITUTIONS -> FUZZY_COST
        typed in KEY_NEIGHBORS[expected].orEmpty() -> NEIGHBOR_COST
        else -> SUBSTITUTION_COST
    }

    private fun insertionDeletionCost(character: Char, previous: Char?): Float = when {
        character == 'h' && previous != null && previous in "zcs" -> FUZZY_COST
        character == 'g' && previous == 'n' -> FUZZY_COST
        else -> INSERTION_DELETION_COST
    }

    private fun prune(
        states: MutableList<PathState>,
        limit: Int,
    ) {
        if (states.isEmpty()) return
        val best = HashMap<PathIdentity, PathState>(states.size)
        states.forEach { state ->
            val key = PathIdentity(state.canonical, state.syllableEnds)
            val previous = best[key]
            if (
                previous == null ||
                state.cost < previous.cost ||
                state.cost == previous.cost && state.firstEditOffset > previous.firstEditOffset
            ) {
                best[key] = state
            }
        }
        val familyCounts = HashMap<CanonicalCostFamily, Int>()
        val eligible = best.values
            .sortedWith(PATH_ORDER)
            .filter { state ->
                val family = CanonicalCostFamily(
                    canonical = state.canonical,
                    quantizedCost = (state.cost * COST_QUANTIZATION).toInt(),
                )
                val count = familyCounts[family] ?: 0
                if (count >= MAX_SEGMENTATIONS_PER_CANONICAL_COST) {
                    false
                } else {
                    familyCounts[family] = count + 1
                    true
                }
            }
        val retainedByIdentity = LinkedHashMap<PathIdentity, PathState>(limit)
        fun retain(state: PathState) {
            if (retainedByIdentity.size >= limit) return
            retainedByIdentity.putIfAbsent(
                PathIdentity(state.canonical, state.syllableEnds),
                state,
            )
        }
        eligible.asSequence()
            .filter { it.firstEditOffset == NO_EDIT_OFFSET }
            .take(MAX_EXACT_PATHS_PER_BEAM)
            .forEach(::retain)
        eligible.asSequence()
            .filter { it.firstEditOffset != NO_EDIT_OFFSET && it.firstEditTier != null }
            .groupBy { CorrectionOrigin(it.firstEditOffset, requireNotNull(it.firstEditTier)) }
            .entries
            .sortedWith(
                compareByDescending<Map.Entry<CorrectionOrigin, List<PathState>>> {
                    it.key.offset
                }.thenBy { correctionTierPriority(it.key.tier) },
            )
            .forEach { (_, paths) ->
                paths.asSequence()
                    .take(MIN_PATHS_PER_CORRECTION_ORIGIN)
                    .forEach(::retain)
            }
        eligible.forEach(::retain)
        states.clear()
        states.addAll(retainedByIdentity.values.sortedWith(PATH_ORDER))
    }

    private data class ParsedInput(
        val code: String,
        val forcedJoints: BooleanArray,
    ) {
        fun hasJointInside(start: Int, end: Int): Boolean {
            for (joint in (start + 1) until end) {
                if (forcedJoints[joint]) return true
            }
            return false
        }
    }

    private data class Edge(
        val end: Int,
        val syllable: String,
        val cost: Float,
        val editOffset: Int,
    )

    @JvmInline
    private value class EditResult(private val packed: Long) {
        val cost: Float
            get() = Float.fromBits((packed ushr Int.SIZE_BITS).toInt())

        val editOffset: Int
            get() = packed.toInt()
    }

    private data class EdgeIdentity(
        val end: Int,
        val syllable: String,
    )

    private enum class EdgeCostTier {
        EXACT,
        FUZZY,
        REPEATED,
        NEIGHBOR,
        TRANSPOSITION,
        SUBSTITUTION,
        INSERT_DELETE,
    }

    private data class PathState(
        val canonical: String,
        val cost: Float,
        val syllableEnds: List<Int>,
        val firstEditOffset: Int,
        val firstEditTier: EdgeCostTier?,
    )

    private data class PathIdentity(
        val canonical: String,
        val syllableEnds: List<Int>,
    )

    private data class CanonicalCostFamily(
        val canonical: String,
        val quantizedCost: Int,
    )

    private data class CorrectionOrigin(
        val offset: Int,
        val tier: EdgeCostTier,
    )

    private fun parse(rawInput: String): ParsedInput {
        val code = StringBuilder(rawInput.length)
        val joints = ArrayList<Int>()
        var pendingJoint = false
        rawInput.forEach { character ->
            when {
                character == '\'' -> pendingJoint = code.isNotEmpty()
                character.lowercaseChar() in 'a'..'z' -> {
                    if (pendingJoint && code.isNotEmpty()) joints += code.length
                    code.append(character.lowercaseChar())
                    pendingJoint = false
                }
            }
        }
        val forced = BooleanArray(code.length + 1)
        joints.forEach { forced[it] = true }
        return ParsedInput(code.toString(), forced)
    }

    companion object {
        private val PATH_ORDER =
            compareBy<PathState> { it.cost }
                .thenBy { it.syllableEnds.size }
                .thenComparator { left, right ->
                    compareSyllableEnds(left.syllableEnds, right.syllableEnds)
                }
                .thenBy { it.canonical }
        private val EDGE_ORDER =
            compareBy<Edge> { it.cost }
                .thenByDescending { it.editOffset }
                .thenByDescending { it.syllable.length }
                .thenBy { it.syllable }

        private const val DEFAULT_MAX_PATHS = 24
        private const val DEFAULT_MAX_COST = 1.05f
        private const val MAX_EDGE_COST = 1f
        // Small embedders may provide only phrase codes and no separate
        // syllable inventory. Phrase spelling units remain bounded by the
        // decoder's maximum word-lattice edge length.
        private const val MAX_SYLLABLE_LENGTH = 24
        private const val MAX_EDGES_PER_OFFSET = 64
        private const val MIN_EDGES_PER_COST_TIER = 8
        private const val PRUNE_MULTIPLIER = 4
        private const val MAX_SEGMENTATIONS_PER_CANONICAL_COST = 4
        private const val MAX_EXACT_PATHS_PER_BEAM = 4
        private const val MIN_PATHS_PER_CORRECTION_ORIGIN = 12
        private const val COST_QUANTIZATION = 1_000f
        private const val NO_EDIT_OFFSET = -1
        private const val EPSILON = 0.0001f
        private const val FUZZY_COST = 0.25f
        private const val NEIGHBOR_COST = 0.35f
        private const val REPEATED_KEY_COST = 0.3f
        private const val TRANSPOSITION_COST = 0.45f
        private const val SUBSTITUTION_COST = 0.9f
        private const val INSERTION_DELETION_COST = 1f
        private val FUZZY_SUBSTITUTIONS = setOf('n' to 'l', 'f' to 'h')
        private val KEY_NEIGHBORS = mapOf(
            'q' to "wa", 'w' to "qeas", 'e' to "wrsd", 'r' to "etdf", 't' to "ryfg",
            'y' to "tugh", 'u' to "yihj", 'i' to "uojk", 'o' to "ipkl", 'p' to "ol",
            'a' to "qwsz", 's' to "awedxz", 'd' to "serfcx", 'f' to "drtgcv", 'g' to "ftyhbv",
            'h' to "gyujbn", 'j' to "huiknm", 'k' to "jiolm", 'l' to "kop",
            'z' to "asx", 'x' to "zsdc", 'c' to "xdfv", 'v' to "cfgb", 'b' to "vghn",
            'n' to "bhjm", 'm' to "njk",
        )

        private fun edgeCostTier(cost: Float): EdgeCostTier = when {
            cost <= EPSILON -> EdgeCostTier.EXACT
            cost <= FUZZY_COST + EPSILON -> EdgeCostTier.FUZZY
            cost <= REPEATED_KEY_COST + EPSILON -> EdgeCostTier.REPEATED
            cost <= NEIGHBOR_COST + EPSILON -> EdgeCostTier.NEIGHBOR
            cost <= TRANSPOSITION_COST + EPSILON -> EdgeCostTier.TRANSPOSITION
            cost <= SUBSTITUTION_COST + EPSILON -> EdgeCostTier.SUBSTITUTION
            else -> EdgeCostTier.INSERT_DELETE
        }

        private fun correctionTierPriority(tier: EdgeCostTier): Int = when (tier) {
            EdgeCostTier.INSERT_DELETE -> 0
            EdgeCostTier.REPEATED -> 1
            EdgeCostTier.TRANSPOSITION -> 2
            EdgeCostTier.NEIGHBOR -> 3
            EdgeCostTier.FUZZY -> 4
            EdgeCostTier.SUBSTITUTION -> 5
            EdgeCostTier.EXACT -> 6
        }

        private fun compareSyllableEnds(left: List<Int>, right: List<Int>): Int {
            val shared = minOf(left.size, right.size)
            repeat(shared) { index ->
                val difference = left[index] - right[index]
                if (difference != 0) return difference
            }
            return left.size - right.size
        }
    }
}
