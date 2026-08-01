package io.github.ethanbird.senseime.core

/** A selected pinyin spelling over a half-open range of T9 digits. */
data class T9LockedEdge(
    val digitStart: Int,
    val digitEnd: Int,
    val spelling: String,
)

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
        return copy(rawDigits = rawDigits + digit, revision = nextRevision())
    }

    /** Adds the separator produced by the `1` key at the current cursor. */
    fun forceJoint(): T9Composition {
        val joint = rawDigits.length
        if (joint == 0 || joint in forcedJoints) return this
        return copy(
            forcedJoints = forcedJoints + joint,
            revision = nextRevision(),
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
        return copy(
            lockedEdges = lockedEdges.filterNot(edge::overlaps) + edge,
            revision = nextRevision(),
        )
    }

    /** Unlocks the latest spelling, removes a trailing separator, then digits. */
    fun backspace(): T9Composition {
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
