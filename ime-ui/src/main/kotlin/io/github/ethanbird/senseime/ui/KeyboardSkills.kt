package io.github.ethanbird.senseime.ui

import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Directional slot exposed by the keyboard Skill picker.
 *
 * The order is stable because the UI keeps a four-element, allocation-free
 * drawing cache indexed by [ordinal].
 */
enum class KeyboardSkillDirection {
    UP,
    RIGHT,
    DOWN,
    LEFT,
}

/**
 * UI-only projection of a persisted Skill binding.
 *
 * Full Skill instructions deliberately stay outside the View. The keyboard
 * only needs enough data to identify and describe a choice.
 */
data class KeyboardSkillBinding(
    val keyCode: Int,
    val direction: KeyboardSkillDirection,
    val skillId: String,
    val label: String,
    val description: String = "",
) {
    init {
        require(keyCode != 0) { "A Skill binding requires a real key code" }
        require(skillId.isNotBlank()) { "A Skill binding requires an id" }
        require(label.isNotBlank()) { "A Skill binding requires a label" }
    }
}

/** The binding that currently owns the keyboard's animated active state. */
data class ActiveKeyboardSkill(
    val skillId: String,
    val sourceKeyCode: Int,
    val direction: KeyboardSkillDirection,
) {
    init {
        require(skillId.isNotBlank()) { "An active Skill requires an id" }
        require(sourceKeyCode != 0) { "An active Skill requires a source key" }
    }
}

enum class KeyboardSkillToggleAction {
    ACTIVATE,
    DEACTIVATE,
}

data class KeyboardSkillSelection(
    val binding: KeyboardSkillBinding,
    val action: KeyboardSkillToggleAction,
    /** UI-only correlation token; it is never persisted as part of the Skill catalog. */
    val requestToken: Long,
) {
    init {
        require(requestToken > 0L) { "A Skill selection requires a positive request token" }
    }
}

/**
 * Stable, View-local identity for one physical rendering of a semantic key.
 *
 * Bounds, label, and capitalization are deliberately absent, so resize, theme, Shift, and locale
 * rebuilds can recover the same owner. [occurrence] disambiguates otherwise identical controls.
 */
data class KeyboardSkillPhysicalOwner(
    val surface: Surface,
    val panelToken: String?,
    val signature: Signature,
    val occurrence: Int,
) {
    enum class Surface {
        TOOLBAR,
        PANEL,
        SYSTEM_BAR,
    }

    data class Signature(
        val keyCode: Int,
        val styleToken: String,
        val iconToken: String?,
        val editorActionToken: String?,
        val clipboardActionToken: String?,
    )

    init {
        require((surface == Surface.PANEL) == (panelToken != null)) {
            "Only panel-owned keys carry a panel token"
        }
        require(signature.keyCode != 0) { "A physical Skill owner requires a real key" }
        require(signature.styleToken.isNotBlank())
        require(occurrence >= 0)
    }
}

data class KeyboardSkillPendingVisualOwner(
    val requestToken: Long,
    val expectedActive: ActiveKeyboardSkill?,
    val owner: KeyboardSkillPhysicalOwner?,
) {
    init {
        require(requestToken > 0L)
        require((expectedActive == null) == (owner == null)) {
            "Activation requires an owner; deactivation requires neither"
        }
    }
}

data class KeyboardSkillConfirmedVisualOwner(
    val requestToken: Long,
    val active: ActiveKeyboardSkill,
    val owner: KeyboardSkillPhysicalOwner,
)

data class KeyboardSkillVisualOwnerState(
    val confirmed: KeyboardSkillConfirmedVisualOwner? = null,
    val pending: List<KeyboardSkillPendingVisualOwner> = emptyList(),
) {
    init {
        require(pending.size <= KeyboardSkillVisualOwnerPolicy.MAX_PENDING_REQUESTS)
        require(pending.zipWithNext().all { (left, right) ->
            left.requestToken < right.requestToken
        }) { "Pending Skill visual tokens must be strictly increasing" }
    }
}

/**
 * Keeps a physical Aurora owner provisional until an authoritative catalog projection agrees.
 *
 * A known owner that is temporarily hidden must not migrate to a different duplicate key. The
 * caller therefore distinguishes `confirmed == null` (canonical fallback allowed) from a
 * confirmed descriptor that currently resolves to no visible key (draw no Aurora).
 */
object KeyboardSkillVisualOwnerPolicy {
    const val MAX_PENDING_REQUESTS = 8

    fun request(
        state: KeyboardSkillVisualOwnerState,
        request: KeyboardSkillPendingVisualOwner,
    ): KeyboardSkillVisualOwnerState {
        val lastToken = maxOf(
            state.confirmed?.requestToken ?: 0L,
            state.pending.lastOrNull()?.requestToken ?: 0L,
        )
        require(
            request.requestToken > lastToken,
        ) { "Skill visual request tokens must increase monotonically" }
        return state.copy(
            pending = (state.pending + request).takeLast(MAX_PENDING_REQUESTS),
        )
    }

    fun project(
        state: KeyboardSkillVisualOwnerState,
        active: ActiveKeyboardSkill?,
    ): KeyboardSkillVisualOwnerState {
        val matching = state.pending.lastOrNull { it.expectedActive == active }
        val confirmed = when {
            matching != null && active != null -> KeyboardSkillConfirmedVisualOwner(
                requestToken = matching.requestToken,
                active = active,
                owner = requireNotNull(matching.owner),
            )
            matching != null -> null
            active == null -> null
            state.confirmed?.active == active -> state.confirmed
            else -> null
        }
        val pending = if (matching == null) {
            state.pending
        } else {
            state.pending.filter { it.requestToken > matching.requestToken }
        }
        return KeyboardSkillVisualOwnerState(
            confirmed = confirmed,
            pending = pending,
        )
    }

    fun reject(
        state: KeyboardSkillVisualOwnerState,
        requestToken: Long,
    ): KeyboardSkillVisualOwnerState = KeyboardSkillVisualOwnerState(
        confirmed = state.confirmed?.takeUnless { it.requestToken == requestToken },
        pending = state.pending.filterNot { it.requestToken == requestToken },
    )
}

fun interface KeyboardSkillSelectionListener {
    fun onSkillSelection(selection: KeyboardSkillSelection)
}

/**
 * Process-scoped monotonic correlation source shared by every recreated keyboard View.
 *
 * A View-local counter could reuse token 1 after input-window recreation, letting a late failure
 * from the old View reject the new View's pending owner. CAS rejects exhaustion before wraparound.
 */
class KeyboardSkillRequestTokenSource(initialValue: Long = 0L) {
    private val current = AtomicLong(initialValue)

    init {
        require(initialValue >= 0L)
    }

    fun next(): Long {
        while (true) {
            val previous = current.get()
            check(previous < Long.MAX_VALUE) { "Skill selection request token exhausted" }
            val next = previous + 1L
            if (current.compareAndSet(previous, next)) return next
        }
    }
}

/**
 * Immutable, conflict-resolved index used by touch handling and drawing.
 *
 * A malformed/stale producer may briefly send two bindings for one slot while
 * two processes converge. Last writer wins deterministically, and the View
 * never draws two overlapping choices.
 */
class KeyboardSkillBindingSet private constructor(
    private val byKey: Map<Int, KeyboardSkillOptions>,
) {
    val keyCount: Int
        get() = byKey.size

    /**
     * Compares the resolved, bounded keyboard projection rather than the producer's input order.
     *
     * Lifecycle refreshes can publish the same catalog while a finger is waiting for the
     * long-press timeout. Treating those refreshes as changes would cancel the armed gesture.
     */
    fun hasSameProjection(other: KeyboardSkillBindingSet): Boolean =
        this === other || byKey == other.byKey

    fun optionsForKey(keyCode: Int): KeyboardSkillOptions? = byKey[keyCode]

    fun binding(
        keyCode: Int,
        direction: KeyboardSkillDirection,
    ): KeyboardSkillBinding? = byKey[keyCode]?.binding(direction)

    companion object {
        val EMPTY = KeyboardSkillBindingSet(emptyMap())

        /**
         * Bound the projection accepted by the rendering process. This is not
         * a data-retention limit; persisted Skill documents remain complete.
         */
        const val MAX_VISIBLE_BINDINGS = 512

        fun from(bindings: List<KeyboardSkillBinding>): KeyboardSkillBindingSet {
            if (bindings.isEmpty()) return EMPTY
            val mutable = LinkedHashMap<Int, Array<KeyboardSkillBinding?>>()
            bindings.takeLast(MAX_VISIBLE_BINDINGS).forEach { binding ->
                val slots = mutable.getOrPut(binding.keyCode) {
                    arrayOfNulls(KeyboardSkillDirection.entries.size)
                }
                slots[binding.direction.ordinal] = binding
            }
            return KeyboardSkillBindingSet(
                mutable.mapValues { (_, slots) ->
                    KeyboardSkillOptions(
                        up = slots[KeyboardSkillDirection.UP.ordinal],
                        right = slots[KeyboardSkillDirection.RIGHT.ordinal],
                        down = slots[KeyboardSkillDirection.DOWN.ordinal],
                        left = slots[KeyboardSkillDirection.LEFT.ordinal],
                    )
                },
            )
        }
    }
}

data class KeyboardSkillOptions(
    val up: KeyboardSkillBinding? = null,
    val right: KeyboardSkillBinding? = null,
    val down: KeyboardSkillBinding? = null,
    val left: KeyboardSkillBinding? = null,
) {
    val count: Int
        get() =
            (if (up != null) 1 else 0) +
                (if (right != null) 1 else 0) +
                (if (down != null) 1 else 0) +
                (if (left != null) 1 else 0)

    val directionMask: Int
        get() =
            bitIfPresent(KeyboardSkillDirection.UP, up) or
                bitIfPresent(KeyboardSkillDirection.RIGHT, right) or
                bitIfPresent(KeyboardSkillDirection.DOWN, down) or
                bitIfPresent(KeyboardSkillDirection.LEFT, left)

    fun binding(direction: KeyboardSkillDirection): KeyboardSkillBinding? = when (direction) {
        KeyboardSkillDirection.UP -> up
        KeyboardSkillDirection.RIGHT -> right
        KeyboardSkillDirection.DOWN -> down
        KeyboardSkillDirection.LEFT -> left
    }

    private fun bitIfPresent(
        direction: KeyboardSkillDirection,
        binding: KeyboardSkillBinding?,
    ): Int = if (binding == null) 0 else 1 shl direction.ordinal
}

enum class KeyboardBuiltInCommand(
    val keyCode: Int,
    val label: String,
) {
    UNDO(KeyCodes.UNDO, "撤销"),
    REDO(KeyCodes.REDO, "重做"),
}

/**
 * Built-in long-hold gestures share the directional picker with Skills while remaining
 * non-persistable commands. Z/Y down are reserved so the same physical gesture is deterministic
 * even when the Skill catalog is refreshed by another process.
 */
object KeyboardBuiltInGesturePolicy {
    private const val UNDO_SKILL_ID = "\u0000sense-undo"
    private const val REDO_SKILL_ID = "\u0000sense-redo"

    fun optionsForKey(
        keyCode: Int,
        configured: KeyboardSkillOptions?,
    ): KeyboardSkillOptions? {
        val command = commandForKey(keyCode) ?: return configured
        val builtIn = KeyboardSkillBinding(
            keyCode = keyCode,
            direction = KeyboardSkillDirection.DOWN,
            skillId = when (command) {
                KeyboardBuiltInCommand.UNDO -> UNDO_SKILL_ID
                KeyboardBuiltInCommand.REDO -> REDO_SKILL_ID
            },
            label = command.label,
            description = "内置编辑命令",
        )
        return (configured ?: KeyboardSkillOptions()).copy(down = builtIn)
    }

    fun command(binding: KeyboardSkillBinding): KeyboardBuiltInCommand? =
        when (binding.skillId) {
            UNDO_SKILL_ID -> KeyboardBuiltInCommand.UNDO
            REDO_SKILL_ID -> KeyboardBuiltInCommand.REDO
            else -> null
        }

    fun reservedCommand(
        keyCode: Int,
        direction: KeyboardSkillDirection,
    ): KeyboardBuiltInCommand? =
        commandForKey(keyCode).takeIf { direction == KeyboardSkillDirection.DOWN }

    private fun commandForKey(keyCode: Int): KeyboardBuiltInCommand? =
        when (keyCode) {
            'z'.code, 'Z'.code -> KeyboardBuiltInCommand.UNDO
            'y'.code, 'Y'.code -> KeyboardBuiltInCommand.REDO
            else -> null
        }
}

object KeyboardSkillTogglePolicy {
    fun resolve(
        active: ActiveKeyboardSkill?,
        binding: KeyboardSkillBinding,
    ): KeyboardSkillToggleAction =
        if (active?.skillId == binding.skillId) {
            KeyboardSkillToggleAction.DEACTIVATE
        } else {
            KeyboardSkillToggleAction.ACTIVATE
        }

    fun nextActive(
        active: ActiveKeyboardSkill?,
        binding: KeyboardSkillBinding,
    ): ActiveKeyboardSkill? = when (resolve(active, binding)) {
        KeyboardSkillToggleAction.ACTIVATE -> ActiveKeyboardSkill(
            skillId = binding.skillId,
            sourceKeyCode = binding.keyCode,
            direction = binding.direction,
        )
        KeyboardSkillToggleAction.DEACTIVATE -> null
    }
}

/**
 * Preserves an explicit selection intent if another process changes active state between picker
 * projection and the background store lock.
 */
object KeyboardSkillMutationIntentPolicy {
    fun shouldToggle(
        action: KeyboardSkillToggleAction,
        currentActiveSkillId: String?,
        requestedSkillId: String,
    ): Boolean = when (action) {
        KeyboardSkillToggleAction.ACTIVATE -> currentActiveSkillId != requestedSkillId
        KeyboardSkillToggleAction.DEACTIVATE -> currentActiveSkillId == requestedSkillId
    }
}

/** Stable TalkBack text for the canvas-only Skill controls. */
object KeyboardSkillAccessibilityText {
    fun keyboardContentDescription(
        activeSkillId: String?,
        activeLabel: String?,
        pickerOptions: KeyboardSkillOptions?,
        highlightedDirection: KeyboardSkillDirection?,
    ): String {
        val activeState = if (activeSkillId == null) {
            "未激活 Skill"
        } else {
            "当前 Skill：${activeLabel ?: activeSkillId}"
        }
        if (pickerOptions == null) return "先思键盘，$activeState"
        val highlighted = highlightedDirection?.let { direction ->
            pickerOptions.binding(direction)?.let { binding ->
                "，当前指向${direction.spokenName()}：${binding.label}"
            }
        }.orEmpty()
        return "先思键盘，$activeState，${pickerOpened(pickerOptions)}$highlighted"
    }

    fun pickerOpened(options: KeyboardSkillOptions): String = buildString {
        append("Skill 选择器")
        KeyboardSkillDirection.entries.forEach { direction ->
            val binding = options.binding(direction) ?: return@forEach
            append("，")
            append(direction.spokenName())
            append("：")
            append(binding.label)
        }
    }

    fun highlighted(
        direction: KeyboardSkillDirection?,
        binding: KeyboardSkillBinding?,
    ): String =
        if (direction == null || binding == null) {
            "未选择 Skill"
        } else {
            "${direction.spokenName()}：${binding.label}"
        }

    fun activeChanged(
        previousSkillId: String?,
        previousLabel: String?,
        currentSkillId: String?,
        currentLabel: String?,
    ): String? = when {
        previousSkillId == currentSkillId -> null
        currentSkillId != null -> "已激活 Skill：${currentLabel ?: currentSkillId}"
        previousSkillId != null -> "已取消 Skill：${previousLabel ?: previousSkillId}"
        else -> null
    }

    private fun KeyboardSkillDirection.spokenName(): String = when (this) {
        KeyboardSkillDirection.UP -> "上"
        KeyboardSkillDirection.RIGHT -> "右"
        KeyboardSkillDirection.DOWN -> "下"
        KeyboardSkillDirection.LEFT -> "左"
    }
}

object KeyboardSkillKeyPolicy {
    fun supportsKeyCode(keyCode: Int): Boolean =
        keyCode != 0 &&
            keyCode != KeyCodes.SPACE &&
            keyCode != KeyCodes.DELETE
}

/**
 * Small Android-free rectangle used by the picker geometry contract.
 *
 * Keeping this contract out of [android.graphics.RectF] lets the edge cases be
 * exhaustively tested without an emulator.
 */
data class KeyboardSkillPickerBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(
            left.isFinite() &&
                top.isFinite() &&
                right.isFinite() &&
                bottom.isFinite(),
        )
        require(right >= left && bottom >= top)
    }

    val centerX: Float
        get() = (left + right) * 0.5f

    val centerY: Float
        get() = (top + bottom) * 0.5f

    fun overlaps(other: KeyboardSkillPickerBounds, gap: Float = 0f): Boolean =
        right + gap > other.left &&
            other.right + gap > left &&
            bottom + gap > other.top &&
            other.bottom + gap > top
}

data class KeyboardSkillPickerSlot(
    val direction: KeyboardSkillDirection,
    val bounds: KeyboardSkillPickerBounds,
)

/**
 * A radial layout preserves the physical direction of every configured slot.
 *
 * When an edge makes a complete radial assignment impossible, the whole
 * picker switches to a direction-labelled fan. It never mixes radial slots
 * with individually clamped slots that visually contradict their direction.
 */
enum class KeyboardSkillPickerLayoutMode {
    RADIAL,
    DIRECTIONAL_FAN,
}

/**
 * Immutable picker projection. A slot's center is its real selection anchor;
 * [mode] tells the renderer whether those anchors preserve their physical
 * directions or form a direction-labelled edge fan.
 */
data class KeyboardSkillPickerLayout(
    val source: KeyboardSkillPickerBounds,
    val slots: List<KeyboardSkillPickerSlot>,
    val mode: KeyboardSkillPickerLayoutMode,
) {
    fun slot(direction: KeyboardSkillDirection): KeyboardSkillPickerSlot? =
        slots.firstOrNull { it.direction == direction }
}

/**
 * Places every enabled choice inside the view without covering the source key
 * or another choice.
 *
 * Eight compass anchors are augmented with safe viewport anchors, then a tiny
 * exhaustive assignment (four choices over a small candidate set) minimizes
 * angular and travel drift.
 * This makes Q-LEFT, P-RIGHT and top-toolbar-UP physically reachable. The
 * rendered arrow still communicates the configured logical direction, while
 * selection follows the slot that the user can actually touch.
 */
object KeyboardSkillPickerGeometry {
    private data class Candidate(
        val x: Float,
        val y: Float,
        val bounds: KeyboardSkillPickerBounds,
    )

    fun layout(
        viewportWidth: Float,
        viewportHeight: Float,
        source: KeyboardSkillPickerBounds,
        enabledDirectionMask: Int,
        chipWidth: Float,
        chipHeight: Float,
        horizontalRadius: Float,
        verticalRadius: Float,
        horizontalInset: Float,
        verticalInset: Float,
        minimumReachDistance: Float,
        gap: Float,
    ): KeyboardSkillPickerLayout {
        require(viewportWidth > 0f && viewportWidth.isFinite())
        require(viewportHeight > 0f && viewportHeight.isFinite())
        require(chipWidth > 0f && chipWidth.isFinite())
        require(chipHeight > 0f && chipHeight.isFinite())
        require(horizontalRadius > 0f && horizontalRadius.isFinite())
        require(verticalRadius > 0f && verticalRadius.isFinite())
        require(horizontalInset >= 0f && horizontalInset.isFinite())
        require(verticalInset >= 0f && verticalInset.isFinite())
        require(minimumReachDistance >= 0f && minimumReachDistance.isFinite())
        require(gap >= 0f && gap.isFinite())
        require(chipWidth + horizontalInset * 2f <= viewportWidth)
        require(chipHeight + verticalInset * 2f <= viewportHeight)

        val directions = KeyboardSkillDirection.entries.filter { direction ->
            enabledDirectionMask and (1 shl direction.ordinal) != 0
        }
        if (directions.isEmpty()) {
            return KeyboardSkillPickerLayout(
                source = source,
                slots = emptyList(),
                mode = KeyboardSkillPickerLayoutMode.RADIAL,
            )
        }

        val halfWidth = chipWidth * 0.5f
        val halfHeight = chipHeight * 0.5f
        val minimumX = horizontalInset + halfWidth
        val maximumX = viewportWidth - horizontalInset - halfWidth
        val minimumY = verticalInset + halfHeight
        val maximumY = viewportHeight - verticalInset - halfHeight
        val candidates = ArrayList<Candidate>(20)

        fun addCandidate(rawX: Float, rawY: Float) {
            val x = rawX.coerceIn(minimumX, maximumX)
            val y = rawY.coerceIn(minimumY, maximumY)
            if (hypot(x - source.centerX, y - source.centerY) < minimumReachDistance) return
            if (candidates.any { abs(it.x - x) < 0.5f && abs(it.y - y) < 0.5f }) return
            val bounds = KeyboardSkillPickerBounds(
                left = x - halfWidth,
                top = y - halfHeight,
                right = x + halfWidth,
                bottom = y + halfHeight,
            )
            if (bounds.overlaps(source, gap)) return
            candidates += Candidate(x, y, bounds)
        }

        val sourceX = source.centerX
        val sourceY = source.centerY
        val horizontalOffsets = floatArrayOf(-horizontalRadius, 0f, horizontalRadius)
        val verticalOffsets = floatArrayOf(-verticalRadius, 0f, verticalRadius)
        verticalOffsets.forEach { deltaY ->
            horizontalOffsets.forEach { deltaX ->
                if (deltaX != 0f || deltaY != 0f) {
                    addCandidate(sourceX + deltaX, sourceY + deltaY)
                }
            }
        }
        // Safe anchors prevent a heavily clamped edge source from collapsing
        // the compass grid into too few unique positions.
        val safeXs = floatArrayOf(
            minimumX,
            minimumX + (maximumX - minimumX) / 3f,
            minimumX + (maximumX - minimumX) * 2f / 3f,
            maximumX,
        )
        val safeYs = floatArrayOf(minimumY, maximumY)
        safeYs.forEach { y -> safeXs.forEach { x -> addCandidate(x, y) } }
        addCandidate(minimumX, (minimumY + maximumY) * 0.5f)
        addCandidate(maximumX, (minimumY + maximumY) * 0.5f)

        fun angularCost(direction: KeyboardSkillDirection, candidate: Candidate): Float {
            val deltaX = candidate.x - sourceX
            val deltaY = candidate.y - sourceY
            val distance = hypot(deltaX, deltaY).coerceAtLeast(0.001f)
            val dot = when (direction) {
                KeyboardSkillDirection.UP -> -deltaY / distance
                KeyboardSkillDirection.RIGHT -> deltaX / distance
                KeyboardSkillDirection.DOWN -> deltaY / distance
                KeyboardSkillDirection.LEFT -> -deltaX / distance
            }
            val preferredDistance = when (direction) {
                KeyboardSkillDirection.LEFT,
                KeyboardSkillDirection.RIGHT -> horizontalRadius
                KeyboardSkillDirection.UP,
                KeyboardSkillDirection.DOWN -> verticalRadius
            }
            return (1f - dot) * 500f + abs(distance - preferredDistance) * 4f
        }

        fun isInsideStrictDirectionCone(
            direction: KeyboardSkillDirection,
            candidate: Candidate,
        ): Boolean {
            val deltaX = candidate.x - sourceX
            val deltaY = candidate.y - sourceY
            return when (direction) {
                KeyboardSkillDirection.UP -> -deltaY >= abs(deltaX)
                KeyboardSkillDirection.RIGHT -> deltaX >= abs(deltaY)
                KeyboardSkillDirection.DOWN -> deltaY >= abs(deltaX)
                KeyboardSkillDirection.LEFT -> -deltaX >= abs(deltaY)
            }
        }

        fun assign(requireStrictDirectionCone: Boolean): Array<Candidate?>? {
            val best = arrayOfNulls<Candidate>(directions.size)
            val working = arrayOfNulls<Candidate>(directions.size)
            var bestScore = Float.POSITIVE_INFINITY

            fun search(directionIndex: Int, used: BooleanArray, score: Float) {
                if (score >= bestScore) return
                if (directionIndex == directions.size) {
                    bestScore = score
                    for (index in working.indices) best[index] = working[index]
                    return
                }
                val direction = directions[directionIndex]
                for (candidateIndex in candidates.indices) {
                    if (used[candidateIndex]) continue
                    val candidate = candidates[candidateIndex]
                    if (
                        requireStrictDirectionCone &&
                        !isInsideStrictDirectionCone(direction, candidate)
                    ) {
                        continue
                    }
                    var collision = false
                    for (existingIndex in 0 until directionIndex) {
                        if (
                            candidate.bounds.overlaps(
                                requireNotNull(working[existingIndex]).bounds,
                                gap,
                            )
                        ) {
                            collision = true
                            break
                        }
                    }
                    if (collision) continue
                    used[candidateIndex] = true
                    working[directionIndex] = candidate
                    search(
                        directionIndex + 1,
                        used,
                        score + angularCost(direction, candidate),
                    )
                    used[candidateIndex] = false
                }
            }

            search(0, BooleanArray(candidates.size), 0f)
            return best.takeIf { choices -> choices.all { it != null } }
        }

        val radialAssignment = assign(requireStrictDirectionCone = true)
        val mode = if (radialAssignment != null) {
            KeyboardSkillPickerLayoutMode.RADIAL
        } else {
            KeyboardSkillPickerLayoutMode.DIRECTIONAL_FAN
        }
        val assignment =
            radialAssignment ?: assign(requireStrictDirectionCone = false) ?: emptyArray()
        val slots = directions.mapIndexedNotNull { index, direction ->
            assignment.getOrNull(index)?.let { KeyboardSkillPickerSlot(direction, it.bounds) }
        }
        return KeyboardSkillPickerLayout(
            source = source,
            slots = slots,
            mode = mode,
        )
    }
}

/**
 * Resolves against real on-screen anchors instead of an imaginary cardinal
 * ray. Switching requires a clear distance advantage, so small diagonal hand
 * jitter does not vibrate between adjacent choices.
 */
object KeyboardSkillPickerSelectionResolver {
    fun resolve(
        layout: KeyboardSkillPickerLayout,
        pointerX: Float,
        pointerY: Float,
        originX: Float,
        originY: Float,
        current: KeyboardSkillDirection?,
        enterDistance: Float,
        exitDistance: Float,
        switchHysteresisDistance: Float,
    ): KeyboardSkillDirection? {
        require(pointerX.isFinite() && pointerY.isFinite())
        require(originX.isFinite() && originY.isFinite())
        require(enterDistance > 0f && enterDistance.isFinite())
        require(exitDistance in 0f..enterDistance && exitDistance.isFinite())
        require(switchHysteresisDistance >= 0f && switchHysteresisDistance.isFinite())
        if (layout.slots.isEmpty()) return null

        val radialDistance = hypot(pointerX - originX, pointerY - originY)
        if (current == null && radialDistance < enterDistance) return null
        if (current != null && radialDistance < exitDistance) return null

        var nearest: KeyboardSkillPickerSlot? = null
        var nearestDistance = Float.POSITIVE_INFINITY
        var currentDistance = Float.POSITIVE_INFINITY
        for (index in layout.slots.indices) {
            val slot = layout.slots[index]
            val distance = hypot(
                pointerX - slot.bounds.centerX,
                pointerY - slot.bounds.centerY,
            )
            if (distance < nearestDistance) {
                nearest = slot
                nearestDistance = distance
            }
            if (slot.direction == current) currentDistance = distance
        }
        val nearestSlot = nearest ?: return null
        if (current == null || nearestSlot.direction == current) return nearestSlot.direction
        if (!currentDistance.isFinite()) return nearestSlot.direction
        return if (
            nearestDistance + switchHysteresisDistance < currentDistance
        ) {
            nearestSlot.direction
        } else {
            current
        }
    }
}

/** Rate-limits optional highlight ticks without affecting selection state. */
class KeyboardSkillHapticGate(
    private val minimumIntervalMillis: Long = DEFAULT_MINIMUM_INTERVAL_MILLIS,
) {
    init {
        require(minimumIntervalMillis >= 0L)
    }

    private var lastEmissionMillis = Long.MIN_VALUE

    fun shouldEmit(nowMillis: Long, direction: KeyboardSkillDirection?): Boolean {
        require(nowMillis >= 0L)
        if (direction == null) return false
        val elapsed = if (lastEmissionMillis == Long.MIN_VALUE) {
            Long.MAX_VALUE
        } else {
            nowMillis - lastEmissionMillis
        }
        if (elapsed < minimumIntervalMillis) return false
        lastEmissionMillis = nowMillis
        return true
    }

    fun reset() {
        lastEmissionMillis = Long.MIN_VALUE
    }

    companion object {
        const val DEFAULT_MINIMUM_INTERVAL_MILLIS = 70L
    }
}

/**
 * Android-free state machine for long-pressing a non-Space key and selecting a
 * configured directional Skill.
 *
 * Movement before activation cancels only Skill eligibility, leaving the
 * ordinary key reducer free to resolve a tap or character flick. Once the
 * picker is visible, release is consumed even when no direction is selected.
 */
class KeyboardSkillGestureSession(
    private val longPressTimeoutMillis: Long = DEFAULT_LONG_PRESS_TIMEOUT_MILLIS,
    private val activationConfirmationMillis: Long = DEFAULT_ACTIVATION_CONFIRMATION_MILLIS,
    private val maximumStationaryDistance: Float,
    private val selectionDistance: Float,
    private val directionalDominanceRatio: Float = DEFAULT_DIRECTIONAL_DOMINANCE_RATIO,
    private val selectionExitDistance: Float = selectionDistance * DEFAULT_EXIT_DISTANCE_RATIO,
    private val switchHysteresisDistance: Float = DEFAULT_SWITCH_HYSTERESIS_DISTANCE,
) {
    init {
        require(longPressTimeoutMillis > 0L)
        require(activationConfirmationMillis >= 0L)
        require(maximumStationaryDistance > 0f && maximumStationaryDistance.isFinite())
        require(selectionDistance > maximumStationaryDistance && selectionDistance.isFinite())
        require(directionalDominanceRatio >= 1f && directionalDominanceRatio.isFinite())
        require(selectionExitDistance in 0f..selectionDistance && selectionExitDistance.isFinite())
        require(switchHysteresisDistance >= 0f && switchHysteresisDistance.isFinite())
    }

    enum class Outcome {
        NONE,
        ELIGIBILITY_CANCELLED,
        PICKER_SHOWN,
        HIGHLIGHT_CHANGED,
    }

    data class Arm(
        val pointerId: Int,
        val generation: Long,
        val activationAtMillis: Long,
    )

    data class Finish(
        val consumed: Boolean,
        val direction: KeyboardSkillDirection?,
    )

    private enum class State {
        IDLE,
        ARMED,
        PICKING,
    }

    private var state = State.IDLE
    private var ownerPointerId = NO_POINTER
    private var generation = 0L
    private var downX = 0f
    private var downY = 0f
    private var activationAtMillis = 0L
    private var directionMask = 0
    private var highlightedDirection: KeyboardSkillDirection? = null

    fun begin(
        pointerId: Int,
        x: Float,
        y: Float,
        eventTimeMillis: Long,
        enabledDirectionMask: Int,
    ): Arm? {
        require(x.isFinite() && y.isFinite())
        require(eventTimeMillis >= 0L)
        if (state != State.IDLE || enabledDirectionMask == 0) return null
        generation = if (generation == Long.MAX_VALUE) 1L else generation + 1L
        ownerPointerId = pointerId
        downX = x
        downY = y
        directionMask = enabledDirectionMask and ALL_DIRECTION_BITS
        activationAtMillis = saturatingAdd(
            saturatingAdd(eventTimeMillis, longPressTimeoutMillis),
            activationConfirmationMillis,
        )
        state = State.ARMED
        return Arm(pointerId, generation, activationAtMillis)
    }

    fun tryActivate(
        pointerId: Int,
        expectedGeneration: Long,
        nowMillis: Long,
    ): Outcome {
        require(nowMillis >= 0L)
        if (
            state != State.ARMED ||
            pointerId != ownerPointerId ||
            generation != expectedGeneration ||
            nowMillis <= activationAtMillis
        ) {
            return Outcome.NONE
        }
        state = State.PICKING
        return Outcome.PICKER_SHOWN
    }

    fun move(
        pointerId: Int,
        x: Float,
        y: Float,
        pickerLayout: KeyboardSkillPickerLayout? = null,
    ): Outcome {
        require(x.isFinite() && y.isFinite())
        if (pointerId != ownerPointerId) return Outcome.NONE
        val deltaX = x - downX
        val deltaY = y - downY
        return when (state) {
            State.IDLE -> Outcome.NONE
            State.ARMED -> {
                val maximumSquared = maximumStationaryDistance * maximumStationaryDistance
                if (deltaX * deltaX + deltaY * deltaY <= maximumSquared) {
                    Outcome.NONE
                } else {
                    clear()
                    Outcome.ELIGIBILITY_CANCELLED
                }
            }
            State.PICKING -> {
                val next = if (pickerLayout == null) {
                    resolveDirection(
                        deltaX = deltaX,
                        deltaY = deltaY,
                        minimumDistance = selectionDistance,
                        directionalDominanceRatio = directionalDominanceRatio,
                    )?.takeIf(::isEnabled)
                } else {
                    KeyboardSkillPickerSelectionResolver.resolve(
                        layout = pickerLayout,
                        pointerX = x,
                        pointerY = y,
                        originX = downX,
                        originY = downY,
                        current = highlightedDirection,
                        enterDistance = selectionDistance,
                        exitDistance = selectionExitDistance,
                        switchHysteresisDistance = switchHysteresisDistance,
                    )?.takeIf(::isEnabled)
                }
                if (next == highlightedDirection) {
                    Outcome.NONE
                } else {
                    highlightedDirection = next
                    Outcome.HIGHLIGHT_CHANGED
                }
            }
        }
    }

    fun pointerUp(pointerId: Int): Finish {
        if (pointerId != ownerPointerId) return Finish(consumed = false, direction = null)
        val wasPicking = state == State.PICKING
        val direction = highlightedDirection.takeIf { wasPicking }
        clear()
        return Finish(consumed = wasPicking, direction = direction)
    }

    fun pointerCancel(pointerId: Int): Boolean {
        if (pointerId != ownerPointerId) return false
        val consumed = state == State.PICKING
        clear()
        return consumed
    }

    fun cancelAll(): Boolean {
        val consumed = state == State.PICKING
        clear()
        return consumed
    }

    fun owns(pointerId: Int): Boolean =
        state != State.IDLE && ownerPointerId == pointerId

    fun isPickerVisible(): Boolean = state == State.PICKING

    fun armedGeneration(): Long? = generation.takeIf { state == State.ARMED }

    fun activeGeneration(): Long? = generation.takeIf { state == State.PICKING }

    fun highlightedDirection(): KeyboardSkillDirection? = highlightedDirection

    fun ownerPointerId(): Int? = ownerPointerId.takeIf { state != State.IDLE }

    fun blocksOrdinaryPointer(pointerId: Int): Boolean =
        state == State.PICKING && pointerId != ownerPointerId

    fun millisUntilActivation(nowMillis: Long): Long {
        require(nowMillis >= 0L)
        return if (state == State.ARMED) {
            if (nowMillis > activationAtMillis) {
                0L
            } else {
                saturatingAdd(activationAtMillis - nowMillis, 1L)
            }
        } else {
            0L
        }
    }

    private fun isEnabled(direction: KeyboardSkillDirection): Boolean =
        directionMask and (1 shl direction.ordinal) != 0

    private fun clear() {
        state = State.IDLE
        ownerPointerId = NO_POINTER
        downX = 0f
        downY = 0f
        activationAtMillis = 0L
        directionMask = 0
        highlightedDirection = null
    }

    companion object {
        const val DEFAULT_LONG_PRESS_TIMEOUT_MILLIS = 380L
        const val DEFAULT_ACTIVATION_CONFIRMATION_MILLIS = 16L
        const val DEFAULT_DIRECTIONAL_DOMINANCE_RATIO = 1.08f
        const val DEFAULT_EXIT_DISTANCE_RATIO = 0.62f
        const val DEFAULT_SWITCH_HYSTERESIS_DISTANCE = 10f

        private const val NO_POINTER = -1
        private const val ALL_DIRECTION_BITS = 0b1111

        fun resolveDirection(
            deltaX: Float,
            deltaY: Float,
            minimumDistance: Float,
            directionalDominanceRatio: Float = DEFAULT_DIRECTIONAL_DOMINANCE_RATIO,
        ): KeyboardSkillDirection? {
            require(deltaX.isFinite() && deltaY.isFinite())
            require(minimumDistance > 0f && minimumDistance.isFinite())
            require(directionalDominanceRatio >= 1f && directionalDominanceRatio.isFinite())
            val horizontal = abs(deltaX)
            val vertical = abs(deltaY)
            if (maxOf(horizontal, vertical) < minimumDistance) return null
            return when {
                horizontal >= vertical * directionalDominanceRatio ->
                    if (deltaX > 0f) KeyboardSkillDirection.RIGHT else KeyboardSkillDirection.LEFT
                vertical >= horizontal * directionalDominanceRatio ->
                    if (deltaY > 0f) KeyboardSkillDirection.DOWN else KeyboardSkillDirection.UP
                else -> null
            }
        }

        private fun saturatingAdd(left: Long, right: Long): Long =
            if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
    }
}

/** Centralizes animation scheduling so idle keyboards never redraw for Skills. */
object KeyboardSkillAnimationPolicy {
    const val FRAME_INTERVAL_MILLIS = 33L

    fun shouldAnimate(
        activeSkill: ActiveKeyboardSkill?,
        pickerVisible: Boolean,
        isShown: Boolean,
        animatorsEnabled: Boolean = true,
    ): Boolean =
        animatorsEnabled &&
            isShown &&
            (activeSkill != null || pickerVisible)
}
