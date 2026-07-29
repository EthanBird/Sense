package io.github.ethanbird.senseime.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class KeyboardSkillsTest {
    private fun binding(
        keyCode: Int = 'a'.code,
        direction: KeyboardSkillDirection = KeyboardSkillDirection.UP,
        skillId: String = "rewrite",
        label: String = "改写",
    ) = KeyboardSkillBinding(
        keyCode = keyCode,
        direction = direction,
        skillId = skillId,
        label = label,
        description = "让表达更清楚",
    )

    private fun session() = KeyboardSkillGestureSession(
        longPressTimeoutMillis = 380L,
        activationConfirmationMillis = 16L,
        maximumStationaryDistance = 12f,
        selectionDistance = 24f,
    )

    private fun active(
        skillId: String,
        keyCode: Int = 'a'.code,
        direction: KeyboardSkillDirection = KeyboardSkillDirection.UP,
    ) = ActiveKeyboardSkill(skillId, keyCode, direction)

    private fun physicalOwner(
        surface: KeyboardSkillPhysicalOwner.Surface,
        keyCode: Int = 'a'.code,
        panelToken: String? =
            if (surface == KeyboardSkillPhysicalOwner.Surface.PANEL) "LETTERS" else null,
        styleToken: String = "LETTER",
        occurrence: Int = 0,
    ) = KeyboardSkillPhysicalOwner(
        surface = surface,
        panelToken = panelToken,
        signature = KeyboardSkillPhysicalOwner.Signature(
            keyCode = keyCode,
            styleToken = styleToken,
            iconToken = null,
            editorActionToken = null,
            clipboardActionToken = null,
        ),
        occurrence = occurrence,
    )

    private fun pickerLayout(
        source: KeyboardSkillPickerBounds,
        mask: Int = 0b1111,
        viewportWidth: Float = 360f,
        viewportHeight: Float = 300f,
    ): KeyboardSkillPickerLayout = KeyboardSkillPickerGeometry.layout(
        viewportWidth = viewportWidth,
        viewportHeight = viewportHeight,
        source = source,
        enabledDirectionMask = mask,
        chipWidth = minOf(76f, viewportWidth * 0.24f, viewportWidth - 12f),
        chipHeight = minOf(36f, viewportHeight - 8f),
        horizontalRadius = 82f,
        verticalRadius = 54f,
        horizontalInset = minOf(6f, viewportWidth * 0.1f),
        verticalInset = minOf(4f, viewportHeight * 0.1f),
        minimumReachDistance = 28f,
        gap = 4f,
    )

    private fun isInsideStrictDirectionCone(
        layout: KeyboardSkillPickerLayout,
        slot: KeyboardSkillPickerSlot,
    ): Boolean {
        val deltaX = slot.bounds.centerX - layout.source.centerX
        val deltaY = slot.bounds.centerY - layout.source.centerY
        return when (slot.direction) {
            KeyboardSkillDirection.UP -> -deltaY >= kotlin.math.abs(deltaX)
            KeyboardSkillDirection.RIGHT -> deltaX >= kotlin.math.abs(deltaY)
            KeyboardSkillDirection.DOWN -> deltaY >= kotlin.math.abs(deltaX)
            KeyboardSkillDirection.LEFT -> -deltaX >= kotlin.math.abs(deltaY)
        }
    }

    private fun assertInsideStrictDirectionCone(
        layout: KeyboardSkillPickerLayout,
        slot: KeyboardSkillPickerSlot,
    ) = assertTrue(isInsideStrictDirectionCone(layout, slot))

    private fun assertAnchorResolves(
        layout: KeyboardSkillPickerLayout,
        slot: KeyboardSkillPickerSlot,
    ) {
        assertEquals(
            slot.direction,
            KeyboardSkillPickerSelectionResolver.resolve(
                layout = layout,
                pointerX = slot.bounds.centerX,
                pointerY = slot.bounds.centerY,
                originX = layout.source.centerX,
                originY = layout.source.centerY,
                current = null,
                enterDistance = 24f,
                exitDistance = 15f,
                switchHysteresisDistance = 10f,
            ),
        )
    }

    @Test
    fun bindingSetResolvesSlotConflictsByLastWriter() {
        val old = binding(skillId = "old", label = "旧")
        val current = binding(skillId = "current", label = "新")
        val right = binding(
            direction = KeyboardSkillDirection.RIGHT,
            skillId = "translate",
            label = "翻译",
        )

        val index = KeyboardSkillBindingSet.from(listOf(old, right, current))

        assertEquals(1, index.keyCount)
        assertEquals(2, index.optionsForKey('a'.code)?.count)
        assertEquals(current, index.binding('a'.code, KeyboardSkillDirection.UP))
        assertEquals(right, index.binding('a'.code, KeyboardSkillDirection.RIGHT))
    }

    @Test
    fun equivalentBindingSetsHaveTheSameResolvedProjection() {
        val up = binding(skillId = "answer", label = "回答")
        val right = binding(
            direction = KeyboardSkillDirection.RIGHT,
            skillId = "rewrite",
            label = "改写",
        )

        val current = KeyboardSkillBindingSet.from(listOf(up, right))
        val refreshed = KeyboardSkillBindingSet.from(listOf(right.copy(), up.copy()))

        assertTrue(current.hasSameProjection(refreshed))
    }

    @Test
    fun changedBindingSetHasADifferentResolvedProjection() {
        val current = KeyboardSkillBindingSet.from(
            listOf(binding(skillId = "answer", label = "回答")),
        )
        val changed = KeyboardSkillBindingSet.from(
            listOf(binding(skillId = "rewrite", label = "改写")),
        )

        assertFalse(current.hasSameProjection(changed))
    }

    @Test
    fun requestedPhysicalOwnerStaysProvisionalUntilAuthoritativeProjectionMatches() {
        val expected = active("rewrite")
        val owner = physicalOwner(KeyboardSkillPhysicalOwner.Surface.PANEL)
        val requested = KeyboardSkillVisualOwnerPolicy.request(
            KeyboardSkillVisualOwnerState(),
            KeyboardSkillPendingVisualOwner(1L, expected, owner),
        )

        assertNull(requested.confirmed)
        assertEquals(listOf(1L), requested.pending.map { it.requestToken })

        val projected = KeyboardSkillVisualOwnerPolicy.project(requested, expected)

        assertEquals(owner, projected.confirmed?.owner)
        assertEquals(expected, projected.confirmed?.active)
        assertTrue(projected.pending.isEmpty())
    }

    @Test
    fun toolbarAndPanelCopiesOfSameSemanticCodeRemainDistinctPhysicalOwners() {
        val toolbar = physicalOwner(
            surface = KeyboardSkillPhysicalOwner.Surface.TOOLBAR,
            keyCode = KeyCodes.LETTERS,
            styleToken = "TOOL",
        )
        val panel = physicalOwner(
            surface = KeyboardSkillPhysicalOwner.Surface.PANEL,
            keyCode = KeyCodes.LETTERS,
            panelToken = "NUMBERS",
            styleToken = "RAIL",
        )

        assertFalse(toolbar == panel)
        assertNull(toolbar.panelToken)
        assertEquals("NUMBERS", panel.panelToken)
    }

    @Test
    fun sameActiveRefreshKeepsConfirmedOwnerButExternalActiveChangeClearsIt() {
        val expected = active("rewrite")
        val owner = physicalOwner(KeyboardSkillPhysicalOwner.Surface.TOOLBAR)
        val confirmed = KeyboardSkillVisualOwnerPolicy.project(
            KeyboardSkillVisualOwnerPolicy.request(
                KeyboardSkillVisualOwnerState(),
                KeyboardSkillPendingVisualOwner(1L, expected, owner),
            ),
            expected,
        )

        assertEquals(confirmed, KeyboardSkillVisualOwnerPolicy.project(confirmed, expected))
        assertNull(
            KeyboardSkillVisualOwnerPolicy.project(
                confirmed,
                active("translate", keyCode = 't'.code),
            ).confirmed,
        )
    }

    @Test
    fun lateFailureCannotClearNewerPendingPhysicalOwner() {
        val first = KeyboardSkillPendingVisualOwner(
            requestToken = 1L,
            expectedActive = active("rewrite"),
            owner = physicalOwner(KeyboardSkillPhysicalOwner.Surface.TOOLBAR),
        )
        val second = KeyboardSkillPendingVisualOwner(
            requestToken = 2L,
            expectedActive = active("translate", keyCode = 't'.code),
            owner = physicalOwner(
                surface = KeyboardSkillPhysicalOwner.Surface.PANEL,
                keyCode = 't'.code,
            ),
        )
        val state = KeyboardSkillVisualOwnerPolicy.request(
            KeyboardSkillVisualOwnerPolicy.request(
                KeyboardSkillVisualOwnerState(),
                first,
            ),
            second,
        )

        val afterOldFailure = KeyboardSkillVisualOwnerPolicy.reject(state, 1L)

        assertEquals(listOf(2L), afterOldFailure.pending.map { it.requestToken })
        assertEquals(
            second.owner,
            KeyboardSkillVisualOwnerPolicy.project(
                afterOldFailure,
                second.expectedActive,
            ).confirmed?.owner,
        )
    }

    @Test
    fun authoritativeDeactivationClearsConfirmedOwner() {
        val expected = active("rewrite")
        val owner = physicalOwner(KeyboardSkillPhysicalOwner.Surface.PANEL)
        val confirmed = KeyboardSkillVisualOwnerPolicy.project(
            KeyboardSkillVisualOwnerPolicy.request(
                KeyboardSkillVisualOwnerState(),
                KeyboardSkillPendingVisualOwner(1L, expected, owner),
            ),
            expected,
        )
        val deactivationRequested = KeyboardSkillVisualOwnerPolicy.request(
            confirmed,
            KeyboardSkillPendingVisualOwner(
                requestToken = 2L,
                expectedActive = null,
                owner = null,
            ),
        )

        val projected = KeyboardSkillVisualOwnerPolicy.project(
            deactivationRequested,
            active = null,
        )

        assertNull(projected.confirmed)
        assertTrue(projected.pending.isEmpty())
    }

    @Test
    fun identicalPhysicalSignaturesUseOccurrenceToRemainDistinct() {
        val first = physicalOwner(
            surface = KeyboardSkillPhysicalOwner.Surface.SYSTEM_BAR,
            occurrence = 0,
        )
        val second = first.copy(occurrence = 1)

        assertFalse(first == second)
    }

    @Test
    fun oneProcessTokenSourceDoesNotCollideAcrossRecreatedViewConsumers() {
        val processSource = KeyboardSkillRequestTokenSource()

        val oldViewToken = processSource.next()
        val newViewToken = processSource.next()

        assertEquals(1L, oldViewToken)
        assertEquals(2L, newViewToken)
        val newViewState = KeyboardSkillVisualOwnerPolicy.request(
            KeyboardSkillVisualOwnerState(),
            KeyboardSkillPendingVisualOwner(
                requestToken = newViewToken,
                expectedActive = active("translate"),
                owner = physicalOwner(KeyboardSkillPhysicalOwner.Surface.TOOLBAR),
            ),
        )
        assertEquals(
            listOf(newViewToken),
            KeyboardSkillVisualOwnerPolicy.reject(
                newViewState,
                oldViewToken,
            ).pending.map { it.requestToken },
        )
    }

    @Test(expected = IllegalStateException::class)
    fun requestTokenSourceRejectsExhaustionBeforeWraparound() {
        KeyboardSkillRequestTokenSource(Long.MAX_VALUE).next()
    }

    @Test
    fun renderingProjectionIsBoundedWithoutMutatingSourceList() {
        val source = (1..600).map { keyCode ->
            binding(
                keyCode = keyCode,
                skillId = "skill-$keyCode",
                label = "技能$keyCode",
            )
        }

        val index = KeyboardSkillBindingSet.from(source)

        assertEquals(600, source.size)
        assertEquals(KeyboardSkillBindingSet.MAX_VISIBLE_BINDINGS, index.keyCount)
        assertNull(index.optionsForKey(1))
        assertEquals("skill-600", index.optionsForKey(600)?.up?.skillId)
    }

    @Test
    fun unconfiguredKeyCannotArmPicker() {
        val session = session()

        assertNull(session.begin(1, 10f, 10f, 0L, enabledDirectionMask = 0))
        assertFalse(session.isPickerVisible())
    }

    @Test
    fun spaceAndDeleteStayReservedWhileOrdinaryActionKeysRemainBindable() {
        assertFalse(KeyboardSkillKeyPolicy.supportsKeyCode(KeyCodes.SPACE))
        assertFalse(KeyboardSkillKeyPolicy.supportsKeyCode(KeyCodes.DELETE))
        assertFalse(KeyboardSkillKeyPolicy.supportsKeyCode(0))
        assertTrue(KeyboardSkillKeyPolicy.supportsKeyCode('a'.code))
        assertTrue(KeyboardSkillKeyPolicy.supportsKeyCode(KeyCodes.SHIFT))
        assertTrue(KeyboardSkillKeyPolicy.supportsKeyCode(KeyCodes.ENTER))
    }

    @Test
    fun movementBeforeLongPressCancelsOnlySkillEligibility() {
        val session = session()
        val arm = requireNotNull(session.begin(1, 10f, 10f, 0L, enabledDirectionMask = 1))

        assertEquals(
            KeyboardSkillGestureSession.Outcome.ELIGIBILITY_CANCELLED,
            session.move(1, 10f, -3f),
        )
        assertNull(session.armedGeneration())
        assertEquals(
            KeyboardSkillGestureSession.Outcome.NONE,
            session.tryActivate(1, arm.generation, 1_000L),
        )
    }

    @Test
    fun preActivationMovementStillLetsOrdinaryReducerResolveCharacterFlick() {
        val session = session()
        session.begin(1, 20f, 100f, 0L, enabledDirectionMask = 1)
        val reducer = TouchInputReducer<String>(
            swipeThreshold = 20f,
            maximumHorizontalDrift = 40f,
        )
        val policy = TouchInputReducer.GesturePolicy.upwardFlick(
            minimumDistance = 20f,
            verticalDominanceRatio = 1.15f,
        )
        reducer.onPrimaryDown(1, "a", 20f, 100f)

        assertEquals(
            KeyboardSkillGestureSession.Outcome.ELIGIBILITY_CANCELLED,
            session.move(1, 20f, 70f),
        )
        reducer.onMove(1, 20f, 70f, insideTapTarget = false, policy = policy)

        assertEquals(
            TouchInputReducer.Gesture.SWIPE_UP,
            reducer.onUp(1, 20f, 70f, insideTapTarget = false, policy = policy)?.gesture,
        )
    }

    @Test
    fun staleCallbackCannotActivateNewerPress() {
        val session = session()
        val first = requireNotNull(session.begin(1, 0f, 0f, 0L, 1))
        session.pointerUp(1)
        val second = requireNotNull(session.begin(1, 0f, 0f, 500L, 1))

        assertEquals(
            KeyboardSkillGestureSession.Outcome.NONE,
            session.tryActivate(1, first.generation, 2_000L),
        )
        assertEquals(second.generation, session.armedGeneration())
    }

    @Test
    fun pickerActivatesAfterConfirmationThenSelectsEnabledDirection() {
        val session = session()
        val mask =
            (1 shl KeyboardSkillDirection.UP.ordinal) or
                (1 shl KeyboardSkillDirection.RIGHT.ordinal)
        val arm = requireNotNull(session.begin(7, 100f, 100f, 1_000L, mask))

        assertEquals(
            KeyboardSkillGestureSession.Outcome.NONE,
            session.tryActivate(7, arm.generation, arm.activationAtMillis),
        )
        assertEquals(
            KeyboardSkillGestureSession.Outcome.PICKER_SHOWN,
            session.tryActivate(7, arm.generation, arm.activationAtMillis + 1L),
        )
        assertEquals(
            KeyboardSkillGestureSession.Outcome.HIGHLIGHT_CHANGED,
            session.move(7, 100f, 70f),
        )
        assertEquals(KeyboardSkillDirection.UP, session.highlightedDirection())

        val finish = session.pointerUp(7)

        assertTrue(finish.consumed)
        assertEquals(KeyboardSkillDirection.UP, finish.direction)
        assertFalse(session.isPickerVisible())
    }

    @Test
    fun disabledDirectionClearsHighlightAndCannotCommit() {
        val session = session()
        val upOnly = 1 shl KeyboardSkillDirection.UP.ordinal
        val arm = requireNotNull(session.begin(1, 0f, 0f, 0L, upOnly))
        session.tryActivate(1, arm.generation, arm.activationAtMillis + 1L)
        session.move(1, 0f, -30f)
        assertEquals(KeyboardSkillDirection.UP, session.highlightedDirection())

        assertEquals(
            KeyboardSkillGestureSession.Outcome.HIGHLIGHT_CHANGED,
            session.move(1, 30f, 0f),
        )
        assertNull(session.highlightedDirection())
        assertNull(session.pointerUp(1).direction)
    }

    @Test
    fun diagonalDeadZoneAvoidsAccidentalDirectionChoice() {
        assertNull(
            KeyboardSkillGestureSession.resolveDirection(
                deltaX = 30f,
                deltaY = -29f,
                minimumDistance = 20f,
            ),
        )
        assertEquals(
            KeyboardSkillDirection.RIGHT,
            KeyboardSkillGestureSession.resolveDirection(
                deltaX = 34f,
                deltaY = 20f,
                minimumDistance = 20f,
            ),
        )
    }

    @Test
    fun secondPointerCannotStealPicker() {
        val session = session()
        val arm = requireNotNull(session.begin(4, 0f, 0f, 0L, 1))

        assertNull(session.begin(8, 0f, 0f, 1L, 1))
        assertEquals(
            KeyboardSkillGestureSession.Outcome.PICKER_SHOWN,
            session.tryActivate(4, arm.generation, arm.activationAtMillis + 1L),
        )
        assertFalse(session.pointerUp(8).consumed)
        assertTrue(session.isPickerVisible())
        assertTrue(session.blocksOrdinaryPointer(8))
        assertFalse(session.blocksOrdinaryPointer(4))
    }

    @Test
    fun centeredPickerUsesCompleteStrictRadialAssignment() {
        val source = KeyboardSkillPickerBounds(162f, 126f, 198f, 174f)

        val layout = pickerLayout(source)

        assertEquals(KeyboardSkillPickerLayoutMode.RADIAL, layout.mode)
        assertEquals(4, layout.slots.size)
        layout.slots.forEach { slot ->
            assertInsideStrictDirectionCone(layout, slot)
            assertAnchorResolves(layout, slot)
        }
    }

    @Test
    fun impossibleEdgeDirectionSwitchesWholePickerToDirectionalFan() {
        val cases = listOf(
            KeyboardSkillPickerBounds(6f, 110f, 42f, 158f) to
                KeyboardSkillDirection.LEFT,
            KeyboardSkillPickerBounds(318f, 110f, 354f, 158f) to
                KeyboardSkillDirection.RIGHT,
            KeyboardSkillPickerBounds(146f, 4f, 214f, 40f) to
                KeyboardSkillDirection.UP,
        )

        cases.forEach { (source, impossibleDirection) ->
            val layout = pickerLayout(source)

            assertEquals(KeyboardSkillPickerLayoutMode.DIRECTIONAL_FAN, layout.mode)
            assertEquals(4, layout.slots.size)
            val displaced = requireNotNull(layout.slot(impossibleDirection))
            assertFalse(isInsideStrictDirectionCone(layout, displaced))
            layout.slots.forEach { assertAnchorResolves(layout, it) }
        }
    }

    @Test
    fun singleImpossibleEdgeDirectionAlsoUsesDirectionalFan() {
        val source = KeyboardSkillPickerBounds(6f, 110f, 42f, 158f)
        val leftOnly = 1 shl KeyboardSkillDirection.LEFT.ordinal

        val layout = pickerLayout(source, mask = leftOnly)

        assertEquals(KeyboardSkillPickerLayoutMode.DIRECTIONAL_FAN, layout.mode)
        assertEquals(1, layout.slots.size)
        assertAnchorResolves(layout, requireNotNull(layout.slot(KeyboardSkillDirection.LEFT)))
    }

    @Test
    fun pickerModeAndGeometryAreStableAcrossViewportSourceAndMaskMatrix() {
        val viewports = listOf(
            320f to 300f,
            360f to 300f,
            411f to 300f,
            640f to 258f,
        )

        viewports.forEach { (viewportWidth, viewportHeight) ->
            val sources = listOf(
                KeyboardSkillPickerBounds(
                    viewportWidth / 2f - 18f,
                    viewportHeight / 2f - 24f,
                    viewportWidth / 2f + 18f,
                    viewportHeight / 2f + 24f,
                ),
                KeyboardSkillPickerBounds(
                    6f,
                    viewportHeight / 2f - 24f,
                    42f,
                    viewportHeight / 2f + 24f,
                ),
                KeyboardSkillPickerBounds(
                    viewportWidth - 42f,
                    viewportHeight / 2f - 24f,
                    viewportWidth - 6f,
                    viewportHeight / 2f + 24f,
                ),
                KeyboardSkillPickerBounds(
                    viewportWidth / 2f - 34f,
                    4f,
                    viewportWidth / 2f + 34f,
                    40f,
                ),
            )
            val horizontalInset = minOf(6f, viewportWidth * 0.1f)
            val verticalInset = minOf(4f, viewportHeight * 0.1f)

            sources.forEach { source ->
                for (mask in 1..0b1111) {
                    val first = pickerLayout(
                        source = source,
                        mask = mask,
                        viewportWidth = viewportWidth,
                        viewportHeight = viewportHeight,
                    )
                    val repeated = pickerLayout(
                        source = source,
                        mask = mask,
                        viewportWidth = viewportWidth,
                        viewportHeight = viewportHeight,
                    )

                    assertEquals(first, repeated)
                    assertEquals(Integer.bitCount(mask), first.slots.size)
                    first.slots.forEach { slot ->
                        assertTrue(slot.bounds.left >= horizontalInset)
                        assertTrue(slot.bounds.right <= viewportWidth - horizontalInset)
                        assertTrue(slot.bounds.top >= verticalInset)
                        assertTrue(slot.bounds.bottom <= viewportHeight - verticalInset)
                        assertFalse(slot.bounds.overlaps(source, gap = 4f))
                        assertAnchorResolves(first, slot)
                        if (first.mode == KeyboardSkillPickerLayoutMode.RADIAL) {
                            assertInsideStrictDirectionCone(first, slot)
                        }
                    }
                    first.slots.forEachIndexed { leftIndex, left ->
                        first.slots.drop(leftIndex + 1).forEach { right ->
                            assertFalse(left.bounds.overlaps(right.bounds, gap = 4f))
                        }
                    }
                }
            }
        }
    }

    @Test
    fun edgeSlotsAreReachableAtTheirRenderedAnchors() {
        val sources = listOf(
            // Q-left edge, P-right edge, and a top toolbar key.
            KeyboardSkillPickerBounds(6f, 110f, 42f, 158f),
            KeyboardSkillPickerBounds(318f, 110f, 354f, 158f),
            KeyboardSkillPickerBounds(146f, 4f, 214f, 40f),
        )
        val expected = listOf(
            KeyboardSkillDirection.LEFT,
            KeyboardSkillDirection.RIGHT,
            KeyboardSkillDirection.UP,
        )

        sources.zip(expected).forEach { (source, direction) ->
            val layout = pickerLayout(source)
            val slot = requireNotNull(layout.slot(direction))

            assertFalse(slot.bounds.overlaps(source, gap = 4f))
            assertTrue(slot.bounds.left >= 6f)
            assertTrue(slot.bounds.right <= 354f)
            assertTrue(slot.bounds.top >= 4f)
            assertTrue(slot.bounds.bottom <= 296f)
            assertEquals(
                direction,
                KeyboardSkillPickerSelectionResolver.resolve(
                    layout = layout,
                    pointerX = slot.bounds.centerX,
                    pointerY = slot.bounds.centerY,
                    originX = source.centerX,
                    originY = source.centerY,
                    current = null,
                    enterDistance = 24f,
                    exitDistance = 15f,
                    switchHysteresisDistance = 10f,
                ),
            )
        }
    }

    @Test
    fun pickerGeometryStaysInsideViewportAndNonOverlappingAcrossSourcePositions() {
        val random = Random(0x51_11_5)
        repeat(1_000) {
            val centerX = random.nextFloat() * (360f - 36f) + 18f
            val centerY = random.nextFloat() * (300f - 48f) + 24f
            val source = KeyboardSkillPickerBounds(
                centerX - 18f,
                centerY - 24f,
                centerX + 18f,
                centerY + 24f,
            )
            val layout = pickerLayout(source)

            assertEquals(4, layout.slots.size)
            layout.slots.forEach { slot ->
                assertTrue(slot.bounds.left >= 6f)
                assertTrue(slot.bounds.right <= 354f)
                assertTrue(slot.bounds.top >= 4f)
                assertTrue(slot.bounds.bottom <= 296f)
                assertFalse(slot.bounds.overlaps(source, gap = 4f))
                assertEquals(
                    slot.direction,
                    KeyboardSkillPickerSelectionResolver.resolve(
                        layout = layout,
                        pointerX = slot.bounds.centerX,
                        pointerY = slot.bounds.centerY,
                        originX = source.centerX,
                        originY = source.centerY,
                        current = null,
                        enterDistance = 24f,
                        exitDistance = 15f,
                        switchHysteresisDistance = 10f,
                    ),
                )
            }
            layout.slots.forEachIndexed { leftIndex, left ->
                layout.slots.drop(leftIndex + 1).forEach { right ->
                    assertFalse(left.bounds.overlaps(right.bounds, gap = 4f))
                }
            }
        }
    }

    @Test
    fun selectionHysteresisRetainsCurrentChoiceUntilNewAnchorClearlyWins() {
        val source = KeyboardSkillPickerBounds(162f, 126f, 198f, 174f)
        val layout = pickerLayout(source)
        val up = requireNotNull(layout.slot(KeyboardSkillDirection.UP))
        val right = requireNotNull(layout.slot(KeyboardSkillDirection.RIGHT))
        val midpointX = (up.bounds.centerX + right.bounds.centerX) * 0.5f
        val midpointY = (up.bounds.centerY + right.bounds.centerY) * 0.5f

        assertEquals(
            KeyboardSkillDirection.RIGHT,
            KeyboardSkillPickerSelectionResolver.resolve(
                layout = layout,
                pointerX = midpointX,
                pointerY = midpointY,
                originX = source.centerX,
                originY = source.centerY,
                current = KeyboardSkillDirection.RIGHT,
                enterDistance = 24f,
                exitDistance = 15f,
                switchHysteresisDistance = 10f,
            ),
        )
        assertEquals(
            KeyboardSkillDirection.UP,
            KeyboardSkillPickerSelectionResolver.resolve(
                layout = layout,
                pointerX = up.bounds.centerX,
                pointerY = up.bounds.centerY,
                originX = source.centerX,
                originY = source.centerY,
                current = KeyboardSkillDirection.RIGHT,
                enterDistance = 24f,
                exitDistance = 15f,
                switchHysteresisDistance = 10f,
            ),
        )
    }

    @Test
    fun gestureSessionUsesRenderedAnchorInsteadOfNominalOffscreenRay() {
        val source = KeyboardSkillPickerBounds(6f, 110f, 42f, 158f)
        val layout = pickerLayout(
            source,
            mask = 1 shl KeyboardSkillDirection.LEFT.ordinal,
        )
        val left = requireNotNull(layout.slot(KeyboardSkillDirection.LEFT))
        val session = session()
        val arm = requireNotNull(
            session.begin(
                pointerId = 1,
                x = source.centerX,
                y = source.centerY,
                eventTimeMillis = 0L,
                enabledDirectionMask = 1 shl KeyboardSkillDirection.LEFT.ordinal,
            ),
        )
        session.tryActivate(1, arm.generation, arm.activationAtMillis + 1L)

        assertEquals(
            KeyboardSkillGestureSession.Outcome.HIGHLIGHT_CHANGED,
            session.move(
                pointerId = 1,
                x = left.bounds.centerX,
                y = left.bounds.centerY,
                pickerLayout = layout,
            ),
        )
        assertEquals(KeyboardSkillDirection.LEFT, session.pointerUp(1).direction)
    }

    @Test
    fun finalPointerCoordinateCanChangeCommittedDirection() {
        val source = KeyboardSkillPickerBounds(162f, 126f, 198f, 174f)
        val layout = pickerLayout(source)
        val up = requireNotNull(layout.slot(KeyboardSkillDirection.UP))
        val right = requireNotNull(layout.slot(KeyboardSkillDirection.RIGHT))
        val session = session()
        val arm = requireNotNull(
            session.begin(
                pointerId = 1,
                x = source.centerX,
                y = source.centerY,
                eventTimeMillis = 0L,
                enabledDirectionMask = 0b1111,
            ),
        )
        session.tryActivate(1, arm.generation, arm.activationAtMillis + 1L)
        session.move(1, up.bounds.centerX, up.bounds.centerY, layout)

        session.move(1, right.bounds.centerX, right.bounds.centerY, layout)
        val finish = session.pointerUp(1)

        assertTrue(finish.consumed)
        assertEquals(KeyboardSkillDirection.RIGHT, finish.direction)
    }

    @Test
    fun finalPointerCoordinateInsideExitRadiusCancelsSelection() {
        val source = KeyboardSkillPickerBounds(162f, 126f, 198f, 174f)
        val layout = pickerLayout(source)
        val up = requireNotNull(layout.slot(KeyboardSkillDirection.UP))
        val session = session()
        val arm = requireNotNull(
            session.begin(
                pointerId = 1,
                x = source.centerX,
                y = source.centerY,
                eventTimeMillis = 0L,
                enabledDirectionMask = 0b1111,
            ),
        )
        session.tryActivate(1, arm.generation, arm.activationAtMillis + 1L)
        session.move(1, up.bounds.centerX, up.bounds.centerY, layout)

        session.move(1, source.centerX, source.centerY, layout)
        val finish = session.pointerUp(1)

        assertTrue(finish.consumed)
        assertNull(finish.direction)
    }

    @Test
    fun highlightHapticsAreDirectionOnlyAndRateLimited() {
        val gate = KeyboardSkillHapticGate(minimumIntervalMillis = 70L)

        assertFalse(gate.shouldEmit(100L, null))
        assertTrue(gate.shouldEmit(100L, KeyboardSkillDirection.UP))
        assertFalse(gate.shouldEmit(150L, KeyboardSkillDirection.RIGHT))
        assertTrue(gate.shouldEmit(170L, KeyboardSkillDirection.RIGHT))
        gate.reset()
        assertTrue(gate.shouldEmit(171L, KeyboardSkillDirection.LEFT))
    }

    @Test
    fun releasingVisiblePickerWithoutDirectionConsumesLongPress() {
        val session = session()
        val arm = requireNotNull(session.begin(2, 0f, 0f, 0L, 1))
        session.tryActivate(2, arm.generation, arm.activationAtMillis + 1L)

        val finish = session.pointerUp(2)

        assertTrue(finish.consumed)
        assertNull(finish.direction)
    }

    @Test
    fun cancelNeverCommitsAndOnlyConsumesVisiblePicker() {
        val armed = session()
        armed.begin(1, 0f, 0f, 0L, 1)
        assertFalse(armed.pointerCancel(1))
        assertFalse(armed.isPickerVisible())

        val picking = session()
        val arm = requireNotNull(picking.begin(2, 0f, 0f, 0L, 1))
        picking.tryActivate(2, arm.generation, arm.activationAtMillis + 1L)
        picking.move(2, 0f, -30f)
        assertTrue(picking.pointerCancel(2))
        assertFalse(picking.isPickerVisible())
        assertNull(picking.highlightedDirection())
    }

    @Test
    fun selectingCurrentSkillTogglesItOffRegardlessOfBindingSlot() {
        val selected = binding(skillId = "translate")
        val active = ActiveKeyboardSkill(
            skillId = "translate",
            sourceKeyCode = 't'.code,
            direction = KeyboardSkillDirection.LEFT,
        )

        assertEquals(
            KeyboardSkillToggleAction.DEACTIVATE,
            KeyboardSkillTogglePolicy.resolve(active, selected),
        )
        assertNull(KeyboardSkillTogglePolicy.nextActive(active, selected))
    }

    @Test
    fun selectingDifferentSkillMovesActiveAuroraToNewSourceKey() {
        val selected = binding(
            keyCode = 'f'.code,
            direction = KeyboardSkillDirection.DOWN,
            skillId = "format",
            label = "格式化",
        )

        val next = KeyboardSkillTogglePolicy.nextActive(
            ActiveKeyboardSkill("rewrite", 'a'.code, KeyboardSkillDirection.UP),
            selected,
        )

        assertEquals("format", next?.skillId)
        assertEquals('f'.code, next?.sourceKeyCode)
        assertEquals(KeyboardSkillDirection.DOWN, next?.direction)
    }

    @Test
    fun staleProjectionNeverReversesExplicitActivationIntent() {
        assertTrue(
            KeyboardSkillMutationIntentPolicy.shouldToggle(
                action = KeyboardSkillToggleAction.ACTIVATE,
                currentActiveSkillId = "rewrite",
                requestedSkillId = "translate",
            ),
        )
        assertFalse(
            KeyboardSkillMutationIntentPolicy.shouldToggle(
                action = KeyboardSkillToggleAction.ACTIVATE,
                currentActiveSkillId = "translate",
                requestedSkillId = "translate",
            ),
        )
        assertTrue(
            KeyboardSkillMutationIntentPolicy.shouldToggle(
                action = KeyboardSkillToggleAction.DEACTIVATE,
                currentActiveSkillId = "translate",
                requestedSkillId = "translate",
            ),
        )
        assertFalse(
            KeyboardSkillMutationIntentPolicy.shouldToggle(
                action = KeyboardSkillToggleAction.DEACTIVATE,
                currentActiveSkillId = null,
                requestedSkillId = "translate",
            ),
        )
    }

    @Test
    fun accessibilityTextCoversPickerHighlightAndAuthoritativeToggle() {
        val options = KeyboardSkillOptions(
            up = binding(skillId = "rewrite", label = "改写"),
            right = binding(
                direction = KeyboardSkillDirection.RIGHT,
                skillId = "translate",
                label = "翻译",
            ),
            down = null,
            left = null,
        )

        assertEquals(
            "先思键盘，未激活 Skill",
            KeyboardSkillAccessibilityText.keyboardContentDescription(
                activeSkillId = null,
                activeLabel = null,
                pickerOptions = null,
                highlightedDirection = null,
            ),
        )
        assertEquals(
            "先思键盘，当前 Skill：翻译，Skill 选择器，上：改写，右：翻译，当前指向右：翻译",
            KeyboardSkillAccessibilityText.keyboardContentDescription(
                activeSkillId = "translate",
                activeLabel = "翻译",
                pickerOptions = options,
                highlightedDirection = KeyboardSkillDirection.RIGHT,
            ),
        )
        assertEquals(
            "Skill 选择器，上：改写，右：翻译",
            KeyboardSkillAccessibilityText.pickerOpened(options),
        )
        assertEquals(
            "右：翻译",
            KeyboardSkillAccessibilityText.highlighted(
                KeyboardSkillDirection.RIGHT,
                options.right,
            ),
        )
        assertEquals(
            "未选择 Skill",
            KeyboardSkillAccessibilityText.highlighted(null, null),
        )
        assertEquals(
            "已激活 Skill：翻译",
            KeyboardSkillAccessibilityText.activeChanged(
                previousSkillId = "rewrite",
                previousLabel = "改写",
                currentSkillId = "translate",
                currentLabel = "翻译",
            ),
        )
        assertEquals(
            "已取消 Skill：翻译",
            KeyboardSkillAccessibilityText.activeChanged(
                previousSkillId = "translate",
                previousLabel = "翻译",
                currentSkillId = null,
                currentLabel = null,
            ),
        )
        assertNull(
            KeyboardSkillAccessibilityText.activeChanged(
                previousSkillId = "translate",
                previousLabel = "翻译",
                currentSkillId = "translate",
                currentLabel = "翻译",
            ),
        )
    }

    @Test
    fun animationRunsOnlyForVisibleActiveOrPickerState() {
        assertFalse(KeyboardSkillAnimationPolicy.shouldAnimate(null, false, true))
        assertFalse(
            KeyboardSkillAnimationPolicy.shouldAnimate(
                ActiveKeyboardSkill("rewrite", 'a'.code, KeyboardSkillDirection.UP),
                pickerVisible = false,
                isShown = false,
            ),
        )
        assertTrue(
            KeyboardSkillAnimationPolicy.shouldAnimate(
                ActiveKeyboardSkill("rewrite", 'a'.code, KeyboardSkillDirection.UP),
                pickerVisible = false,
                isShown = true,
            ),
        )
        assertTrue(
            KeyboardSkillAnimationPolicy.shouldAnimate(
                activeSkill = null,
                pickerVisible = true,
                isShown = true,
            ),
        )
        assertFalse(
            KeyboardSkillAnimationPolicy.shouldAnimate(
                ActiveKeyboardSkill("rewrite", 'a'.code, KeyboardSkillDirection.UP),
                pickerVisible = true,
                isShown = true,
                animatorsEnabled = false,
            ),
        )
        assertEquals(33L, KeyboardSkillAnimationPolicy.FRAME_INTERVAL_MILLIS)
    }

    @Test(timeout = 1_500L)
    fun moveResolutionRemainsLinearUnderDenseMotionStream() {
        val session = session()
        val arm = requireNotNull(session.begin(1, 0f, 0f, 0L, 0b1111))
        session.tryActivate(1, arm.generation, arm.activationAtMillis + 1L)

        repeat(250_000) { index ->
            val offset = if (index and 1 == 0) 30f else -30f
            session.move(1, offset, 0f)
        }

        assertEquals(KeyboardSkillDirection.LEFT, session.highlightedDirection())
    }
}
