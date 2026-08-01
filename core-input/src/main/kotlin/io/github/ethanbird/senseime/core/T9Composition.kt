package io.github.ethanbird.senseime.core

/** A selected pinyin spelling over a half-open range of T9 digits. */
data class T9LockedEdge(
    val digitStart: Int,
    val digitEnd: Int,
    val spelling: String,
)

/**
 * Immutable edit journal used to make T9 backspace undo the most recent user action.
 *
 * Digits, explicit joints and spelling locks can be interleaved. Keeping that order avoids
 * guessing from the final sets (for example, a digit typed after selecting `hun` must be removed
 * before the earlier selection is unlocked).
 */
sealed interface T9EditOperation {
    data object Digit : T9EditOperation

    data class Joint(val digitOffset: Int) : T9EditOperation

    data class Lock(
        val applied: T9LockedEdge,
        val previousLockedEdges: List<T9LockedEdge>,
    ) : T9EditOperation
}

/**
 * Immutable composing transaction for nine-key pinyin.
 *
 * Digits remain the source of truth. Spelling selections are recorded as
 * constraints instead of rewriting [rawDigits], which makes every selection
 * reversible without reconstructing the original key stream.
 */
data class T9Composition(
    val rawDigits: String = "",
    val forcedJoints: Set<Int> = emptySet(),
    val lockedEdges: List<T9LockedEdge> = emptyList(),
    val revision: Long = 0L,
    val editOperations: List<T9EditOperation> = emptyList(),
) {
    init {
        require(rawDigits.all { it in T9_DIGITS }) { "T9 input accepts digits 2..9" }
        require(rawDigits.length <= PinyinInputLimits.MAX_COMPOSING_CODE_LENGTH) {
            "T9 composition exceeds the shared input boundary"
        }
        require(forcedJoints.all { it in 1..rawDigits.length }) {
            "Forced joints must follow an existing digit"
        }
        require(lockedEdges.all { edge ->
            edge.digitStart >= 0 &&
                edge.digitEnd in (edge.digitStart + 1)..rawDigits.length &&
                edge.spelling.isNotEmpty() &&
                edge.spelling.all { it in 'a'..'z' }
        }) { "Locked edges must address a non-empty digit range" }
        require(lockedEdges.indices.none { left ->
            ((left + 1) until lockedEdges.size).any { right ->
                lockedEdges[left].overlaps(lockedEdges[right])
            }
        }) { "Locked edges must not overlap" }
    }

    fun typeDigit(digit: Char): T9Composition {
        require(digit in T9_DIGITS) { "T9 input accepts digits 2..9" }
        if (rawDigits.length >= PinyinInputLimits.MAX_COMPOSING_CODE_LENGTH) return this
        return copy(
            rawDigits = rawDigits + digit,
            revision = nextRevision(),
            editOperations = editOperations + T9EditOperation.Digit,
        )
    }

    /** Adds the separator produced by the `1` key at the current cursor. */
    fun forceJoint(): T9Composition {
        val joint = rawDigits.length
        if (joint == 0 || joint in forcedJoints) return this
        return copy(
            forcedJoints = forcedJoints + joint,
            revision = nextRevision(),
            editOperations = editOperations + T9EditOperation.Joint(joint),
        )
    }

    /**
     * Constrains path search to [edge]. Selecting another spelling for an
     * overlapping range replaces the previous constraint atomically.
     */
    fun lockEdge(edge: T9LockedEdge): T9Composition {
        require(edge.digitStart >= 0 && edge.digitEnd <= rawDigits.length) {
            "Locked edge is outside the current digit stream"
        }
        require(edge.digitStart < edge.digitEnd && edge.spelling.all { it in 'a'..'z' }) {
            "Locked edge must contain a lowercase pinyin spelling"
        }
        require(forcedJoints.none { it > edge.digitStart && it < edge.digitEnd }) {
            "Locked edge must not cross a forced joint"
        }
        if (edge in lockedEdges) return this
        val previousLockedEdges = lockedEdges
        return copy(
            lockedEdges = previousLockedEdges.filterNot(edge::overlaps) + edge,
            revision = nextRevision(),
            editOperations = editOperations + T9EditOperation.Lock(edge, previousLockedEdges),
        )
    }

    /**
     * Applies one dynamic pinyin choice only when it still belongs to this immutable revision.
     * Stale UI choices are ignored; a valid choice keeps digits intact and locks one next edge.
     */
    fun selectPinyin(choice: T9PinyinChoice): T9Composition {
        if (choice.sourceDigits != rawDigits || choice.sourceRevision != revision) return this
        var nextUnresolvedDigit = 0
        val locksByStart = lockedEdges.associateBy(T9LockedEdge::digitStart)
        while (nextUnresolvedDigit < rawDigits.length) {
            nextUnresolvedDigit = locksByStart[nextUnresolvedDigit]?.digitEnd ?: break
        }
        if (choice.digitStart != nextUnresolvedDigit) return this
        val expectedDigits = T9SyllableIndex.digitsFor(choice.canonicalPinyin)
        if (expectedDigits != rawDigits.substring(choice.digitStart, choice.digitEnd)) return this
        return lockEdge(choice.lockedEdge)
    }

    /** Reverses the latest digit, explicit joint or spelling selection in true edit order. */
    fun backspace(): T9Composition {
        when (val latest = editOperations.lastOrNull()) {
            T9EditOperation.Digit -> if (rawDigits.isNotEmpty()) {
                val newLength = rawDigits.length - 1
                return copy(
                    rawDigits = rawDigits.dropLast(1),
                    forcedJoints = forcedJoints.filterTo(linkedSetOf()) { it <= newLength },
                    revision = nextRevision(),
                    editOperations = editOperations.dropLast(1),
                )
            }

            is T9EditOperation.Joint -> if (latest.digitOffset in forcedJoints) {
                return copy(
                    forcedJoints = forcedJoints - latest.digitOffset,
                    revision = nextRevision(),
                    editOperations = editOperations.dropLast(1),
                )
            }

            is T9EditOperation.Lock -> if (latest.applied in lockedEdges) {
                return copy(
                    lockedEdges = latest.previousLockedEdges,
                    revision = nextRevision(),
                    editOperations = editOperations.dropLast(1),
                )
            }

            null -> Unit
        }

        // Preserve source compatibility for directly constructed compositions that predate the
        // journal. Production edits always take the ordered path above.
        if (lockedEdges.isNotEmpty()) {
            return copy(
                lockedEdges = lockedEdges.dropLast(1),
                revision = nextRevision(),
            )
        }
        val trailingJoint = rawDigits.length
        if (trailingJoint in forcedJoints) {
            return copy(
                forcedJoints = forcedJoints - trailingJoint,
                revision = nextRevision(),
            )
        }
        if (rawDigits.isEmpty()) return this
        val newLength = rawDigits.length - 1
        return copy(
            rawDigits = rawDigits.dropLast(1),
            forcedJoints = forcedJoints.filterTo(linkedSetOf()) { it <= newLength },
            revision = nextRevision(),
        )
    }

    private fun nextRevision(): Long = if (revision == Long.MAX_VALUE) 1L else revision + 1L

    private companion object {
        val T9_DIGITS = '2'..'9'
    }
}

private fun T9LockedEdge.overlaps(other: T9LockedEdge): Boolean =
    digitStart < other.digitEnd && other.digitStart < digitEnd
