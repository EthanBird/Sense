package io.github.ethanbird.senseime.ui

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.Build
import android.os.SystemClock
import kotlin.math.max
import kotlin.math.sin

/**
 * Keycaps, scrollable key runs and keyboard-skill visuals.
 *
 * The renderer reads the already-published scene and picker hit geometry. Its
 * Paint, Path, Matrix, RectF and text scratch objects live for the module
 * lifetime; drawing never publishes interaction or business state.
 */
internal class KeyboardKeyRenderer(
    private val density: Float,
    fontScale: Float,
    private val metrics: KeyboardMetrics,
    private val palette: KeyboardPalette,
) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = KeyboardCanvasText()
    private val iconPainter = KeyboardIconPainter(density, fontScale)
    private val toolboxTileBounds = RectF()
    private val toolboxIconBounds = RectF()
    private val auroraMatrix = Matrix()
    private val auroraPath = Path()
    private val skillLabelBuilder = StringBuilder(64)
    private var fontScale = fontScale
    private var auroraShader: SweepGradient? = null
    private var voiceButtonShader: Shader? = null

    fun updateSurface(
        width: Int,
        height: Int,
        fontScale: Float,
    ) {
        this.fontScale = fontScale
        iconPainter.updateFontScale(fontScale)
        voiceButtonShader = LinearGradient(
            dp(22f),
            0f,
            maxOf(dp(23f), width - dp(22f)),
            0f,
            intArrayOf(
                color(0xFF20C7EE.toInt(), 0xFF34D9FF.toInt()),
                color(0xFF557EF7.toInt(), 0xFF7A89FF.toInt()),
                color(0xFFA24DF4.toInt(), 0xFFC05CFF.toInt()),
            ),
            null,
            Shader.TileMode.CLAMP,
        )
        auroraShader = SweepGradient(
            0f,
            0f,
            intArrayOf(
                color(0xFF16E6D4.toInt(), 0xFF43F1E1.toInt()),
                color(0xFF4F7CFF.toInt(), 0xFF7593FF.toInt()),
                color(0xFFD355FF.toInt(), 0xFFE477FF.toInt()),
                color(0xFFFF5CB6.toInt(), 0xFFFF72C2.toInt()),
                color(0xFF77F4A8.toInt(), 0xFF8CFFC0.toInt()),
                color(0xFF16E6D4.toInt(), 0xFF43F1E1.toInt()),
            ),
            null,
        )
    }

    fun drawKeys(
        canvas: Canvas,
        state: KeyboardRendererState,
    ) {
        val keys = state.scene.keys
        var index = 0
        while (index < keys.size) {
            val scrollPanel = keys[index].scrollPanel
            if (scrollPanel == null) {
                drawKey(canvas, state, keys[index])
                index += 1
                continue
            }
            val runStart = index
            while (index < keys.size && keys[index].scrollPanel == scrollPanel) {
                index += 1
            }
            drawScrollableKeyRun(
                canvas = canvas,
                state = state,
                panel = scrollPanel,
                startIndex = runStart,
                endIndex = index,
            )
        }
        drawSkillPicker(canvas, state)
    }

    private fun drawScrollableKeyRun(
        canvas: Canvas,
        state: KeyboardRendererState,
        panel: ScrollPanel,
        startIndex: Int,
        endIndex: Int,
    ) {
        val viewport = state.scene.viewportBounds(panel) ?: return
        val offset = state.scene.scrollOffset(panel)
        val saveCount = canvas.save()
        canvas.clipRect(viewport)
        canvas.translate(0f, -offset)
        val visibleContentTop = viewport.top + offset
        val visibleContentBottom = viewport.bottom + offset
        val keys = state.scene.keys
        var low = startIndex
        var high = endIndex
        while (low < high) {
            val middle = (low + high) ushr 1
            if (keys[middle].bounds.bottom <= visibleContentTop) {
                low = middle + 1
            } else {
                high = middle
            }
        }
        var index = low
        while (index < endIndex) {
            val key = keys[index]
            if (key.bounds.top >= visibleContentBottom) break
            drawKey(canvas, state, key)
            index += 1
        }
        canvas.restoreToCount(saveCount)
    }

    private fun drawKey(
        canvas: Canvas,
        state: KeyboardRendererState,
        key: Key,
    ) {
        val pressed = state.isKeyPressed(key)
        when (key.style) {
            KeyStyle.TOOL -> drawToolKey(canvas, key, pressed)
            KeyStyle.SYSTEM -> drawSystemKey(canvas, key, pressed)
            KeyStyle.CARD -> drawCardKey(canvas, key, pressed)
            KeyStyle.EMOJI -> drawEmojiKey(canvas, key, pressed)
            KeyStyle.CATEGORY -> drawCategoryKey(canvas, state, key, pressed)
            KeyStyle.SYMBOL -> drawSymbolKey(canvas, key, pressed)
            KeyStyle.SYMBOL_CATEGORY ->
                drawSymbolCategoryKey(canvas, state, key, pressed)
            KeyStyle.RAIL -> drawRailKey(canvas, key, pressed)
            KeyStyle.EDITOR_DIRECTION -> drawEditorDirectionKey(canvas, key, pressed)
            KeyStyle.EDITOR_PRIMARY ->
                drawEditorPrimaryKey(canvas, state, key, pressed)
            KeyStyle.EDITOR_ACTION -> drawEditorActionKey(canvas, state, key, pressed)
            KeyStyle.VOICE_PRIMARY -> drawVoicePrimaryKey(canvas, state, key, pressed)
            KeyStyle.TOOLBOX_CARD -> drawToolboxCard(canvas, key, pressed)
            else -> drawKeyboardKey(canvas, state, key, pressed)
        }
        if (
            isActiveSkillSource(state, key) &&
            key.style != KeyStyle.LETTER &&
            key.style != KeyStyle.ACTION
        ) {
            if (!state.hasAuroraSibling) {
                drawSkillAuroraOverlay(canvas, key.bounds, foregroundOnly = true)
            }
            drawActiveSkillMarker(canvas, key.bounds)
        }
    }

    private fun drawToolboxCard(
        canvas: Canvas,
        key: Key,
        pressed: Boolean,
    ) {
        val accent = when (key.icon) {
            Icon.SYMBOLS -> color(0xFF4D78EA.toInt(), 0xFF8BA8FF.toInt())
            Icon.EDITOR -> color(0xFF2E8E9E.toInt(), 0xFF5DD2E3.toInt())
            Icon.VOICE -> color(0xFF8B5BE8.toInt(), 0xFFB99AFF.toInt())
            Icon.CLIPBOARD -> color(0xFFE17B42.toInt(), 0xFFFFA66F.toInt())
            Icon.EMOJI -> color(0xFFCC8B25.toInt(), 0xFFFFC561.toInt())
            else -> color(0xFF4C6F9D.toInt(), 0xFF95A9C6.toInt())
        }
        if (pressed) {
            paint.style = Paint.Style.FILL
            paint.color = color(0x145B7DF0, 0x286D61D8)
            canvas.drawRoundRect(key.bounds, dp(16f), dp(16f), paint)
        }
        val tileSize = minOf(
            dp(54f),
            key.bounds.width() - dp(14f),
            key.bounds.height() - dp(30f),
        )
        val tileLeft = key.bounds.centerX() - tileSize / 2f
        val tileTop = key.bounds.top + dp(5f)
        toolboxTileBounds.set(
            tileLeft,
            tileTop,
            tileLeft + tileSize,
            tileTop + tileSize,
        )
        paint.style = Paint.Style.FILL
        paint.color = if (pressed) {
            accent
        } else {
            color(0xB8FFFFFF.toInt(), 0xFF292B2E.toInt())
        }
        canvas.drawRoundRect(toolboxTileBounds, dp(17f), dp(17f), paint)

        toolboxIconBounds.set(
            toolboxTileBounds.left + dp(12f),
            toolboxTileBounds.top + dp(12f),
            toolboxTileBounds.right - dp(12f),
            toolboxTileBounds.bottom - dp(12f),
        )
        val icon = key.icon
        if (icon != null) {
            drawIcon(
                canvas = canvas,
                icon = icon,
                bounds = toolboxIconBounds,
                tint = if (pressed) Color.WHITE else accent,
            )
        }
        paint.style = Paint.Style.FILL
        paint.color = color(0xFF354257.toInt(), 0xFFE3E5EA.toInt())
        paint.textSize = sp(12.5f)
        paint.textAlign = Paint.Align.CENTER
        text.drawCentered(
            canvas,
            key.label,
            paint,
            key.bounds.centerX(),
            minOf(key.bounds.bottom - dp(7f), toolboxTileBounds.bottom + dp(17f)),
        )
    }

    private fun drawVoicePrimaryKey(
        canvas: Canvas,
        state: KeyboardRendererState,
        key: Key,
        pressed: Boolean,
    ) {
        val enabled = state.isKeyEnabled(key)
        paint.style = Paint.Style.FILL
        paint.shader = if (enabled) voiceButtonShader else null
        paint.alpha = when {
            !enabled -> 255
            pressed -> 195
            else -> 245
        }
        if (!enabled) {
            paint.color = color(0xFFD7DEE9.toInt(), 0xFF303238.toInt())
        }
        canvas.drawRoundRect(key.bounds, dp(14f), dp(14f), paint)
        paint.shader = null
        paint.alpha = 255
        paint.color = if (enabled) {
            Color.WHITE
        } else {
            color(0xFF788397.toInt(), 0xFF989DA7.toInt())
        }
        paint.textSize = sp(16.5f)
        paint.textAlign = Paint.Align.CENTER
        text.drawCentered(
            canvas,
            key.label,
            paint,
            key.bounds.centerX(),
            key.bounds.centerY(),
        )
    }

    private fun drawKeyboardKey(
        canvas: Canvas,
        state: KeyboardRendererState,
        key: Key,
        pressed: Boolean,
    ) {
        paint.style = Paint.Style.FILL
        paint.color = if (pressed) {
            color(0xFF5B7DF0.toInt(), 0xFF6D61D8.toInt())
        } else if (key.style == KeyStyle.ACTION) {
            color(0xFFCED6E1.toInt(), 0xFF242526.toInt())
        } else {
            color(0xEFFFFFFF.toInt(), 0xFF303132.toInt())
        }
        canvas.drawRoundRect(key.bounds, metrics.keyRadius, metrics.keyRadius, paint)
        if (isActiveSkillSource(state, key) && !state.hasAuroraSibling) {
            drawSkillAuroraOverlay(canvas, key.bounds, foregroundOnly = false)
        }

        paint.style = Paint.Style.FILL
        paint.color = if (pressed) {
            Color.WHITE
        } else {
            color(0xFF111827.toInt(), 0xFFF6F7F9.toInt())
        }
        val icon = key.icon
        if (icon != null) {
            drawIcon(canvas, icon, key.bounds, paint.color)
        } else {
            paint.textSize = sp(if (key.label.length > 2) 13f else 20f)
            paint.textAlign = Paint.Align.CENTER
            text.drawCentered(
                canvas,
                key.label,
                paint,
                key.bounds.centerX(),
                key.bounds.centerY() + if (key.hint == null) 0f else dp(3f),
            )
        }

        val hint = key.hint
        if (hint != null) {
            paint.color = color(0xFF7C8799.toInt(), 0xFF83868D.toInt())
            paint.textSize = sp(8.5f)
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText(hint, key.bounds.centerX(), key.bounds.top + dp(10f), paint)
        }
        if (isActiveSkillSource(state, key)) {
            drawActiveSkillMarker(canvas, key.bounds)
        }
    }

    /**
     * A cached sweep shader is transformed instead of recreated each frame.
     * Only matrix coefficients, paint alpha and the reusable path are mutated.
     */
    private fun drawSkillAuroraOverlay(
        canvas: Canvas,
        bounds: RectF,
        foregroundOnly: Boolean,
    ) {
        val shader = auroraShader ?: return
        val phase = skillAnimationPhase()
        val scale = maxOf(bounds.width(), bounds.height()).coerceAtLeast(1f)
        auroraMatrix.reset()
        auroraMatrix.setScale(scale, scale)
        auroraMatrix.postRotate(phase * 360f)
        auroraMatrix.postTranslate(bounds.centerX(), bounds.centerY())
        shader.setLocalMatrix(auroraMatrix)

        val pulse = (sin(phase * TWO_PI) + 1f) * 0.5f
        paint.style = Paint.Style.FILL
        paint.shader = shader
        paint.alpha = if (foregroundOnly) {
            (46f + pulse * 24f).toInt()
        } else {
            (92f + pulse * 42f).toInt()
        }
        canvas.drawRoundRect(bounds, metrics.keyRadius, metrics.keyRadius, paint)

        auroraPath.reset()
        auroraPath.addRoundRect(
            bounds,
            metrics.keyRadius,
            metrics.keyRadius,
            Path.Direction.CW,
        )
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(if (foregroundOnly) 1.25f else 1.7f)
        paint.alpha = (175f + pulse * 65f).toInt()
        canvas.drawPath(auroraPath, paint)
        paint.shader = null
        paint.alpha = 255
        paint.style = Paint.Style.FILL
    }

    private fun drawActiveSkillMarker(
        canvas: Canvas,
        bounds: RectF,
    ) {
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        canvas.drawCircle(
            bounds.right - dp(6.5f),
            bounds.top + dp(6.5f),
            dp(2.4f),
            paint,
        )
        paint.color = color(0xFF6A5BFF.toInt(), 0xFFB88AFF.toInt())
        canvas.drawCircle(
            bounds.right - dp(6.5f),
            bounds.top + dp(6.5f),
            dp(1.35f),
            paint,
        )
    }

    private fun drawSkillPicker(
        canvas: Canvas,
        state: KeyboardRendererState,
    ) {
        if (!state.skillPickerVisible) return
        val options = state.skillPickerOptions ?: return
        val sourceBounds = state.skillPickerSourceBounds
        if (sourceBounds.isEmpty) return
        val optionBounds = state.skillPickerOptionBounds
        val sourceX = sourceBounds.centerX()
        val sourceY = sourceBounds.centerY()

        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = maxOf(density, dp(1.2f))
        paint.color = color(0x664E7DFF, 0x999D82FF.toInt())
        var ordinal = 0
        while (ordinal < KeyboardSkillDirection.entries.size) {
            val slot = optionBounds[ordinal]
            if (!slot.isEmpty) {
                canvas.drawLine(sourceX, sourceY, slot.centerX(), slot.centerY(), paint)
            }
            ordinal += 1
        }

        val phase = skillAnimationPhase()
        ordinal = 0
        while (ordinal < KeyboardSkillDirection.entries.size) {
            val direction = KeyboardSkillDirection.entries[ordinal]
            val binding = options.binding(direction)
            val slot = optionBounds[ordinal]
            if (binding == null || slot.isEmpty) {
                ordinal += 1
                continue
            }
            val highlighted = state.highlightedSkillDirection == direction
            paint.style = Paint.Style.FILL
            paint.shader = null
            paint.alpha = 255
            paint.color = when {
                highlighted -> color(0xEE4F70F3.toInt(), 0xEE735FDE.toInt())
                state.activeKeyboardSkill?.skillId == binding.skillId ->
                    color(0xEEDAF3F1.toInt(), 0xEE31343B.toInt())
                else -> color(0xF5FFFFFF.toInt(), 0xF52A2B2F.toInt())
            }
            canvas.drawRoundRect(slot, dp(13f), dp(13f), paint)

            val shader = auroraShader
            if (shader != null) {
                auroraMatrix.reset()
                auroraMatrix.setScale(slot.width(), slot.height())
                auroraMatrix.postRotate(phase * 360f + ordinal * 40f)
                auroraMatrix.postTranslate(slot.centerX(), slot.centerY())
                shader.setLocalMatrix(auroraMatrix)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(if (highlighted) 2.1f else 1.1f)
                paint.shader = shader
                paint.alpha = if (highlighted) 255 else 180
                canvas.drawRoundRect(slot, dp(13f), dp(13f), paint)
                paint.shader = null
                paint.alpha = 255
            }

            paint.style = Paint.Style.FILL
            paint.color = if (highlighted) {
                Color.WHITE
            } else {
                color(0xFF172033.toInt(), 0xFFF4F5F8.toInt())
            }
            paint.textSize = sp(11.5f)
            paint.textAlign = Paint.Align.CENTER
            skillLabelBuilder.setLength(0)
            skillLabelBuilder.append(direction.arrow())
            skillLabelBuilder.append(' ')
            skillLabelBuilder.append(binding.label)
            val saveCount = canvas.save()
            canvas.clipRect(slot)
            text.drawEllipsized(
                canvas = canvas,
                text = skillLabelBuilder,
                paint = paint,
                x = slot.centerX(),
                centerY = slot.centerY(),
                maximumWidth = slot.width() - dp(12f),
                trimTrailingWhitespace = false,
            )
            canvas.restoreToCount(saveCount)
            ordinal += 1
        }
        paint.shader = null
        paint.alpha = 255
        paint.style = Paint.Style.FILL
    }

    private fun drawToolKey(
        canvas: Canvas,
        key: Key,
        pressed: Boolean,
    ) {
        if (pressed) {
            paint.style = Paint.Style.FILL
            paint.color = color(0x254F7CF5, 0x405E63D8)
            canvas.drawRoundRect(key.bounds, dp(9f), dp(9f), paint)
        }
        val iconColor = color(0xFF586477.toInt(), 0xFFB6BAC2.toInt())
        val icon = key.icon
        if (icon != null) {
            drawIcon(canvas, icon, key.bounds, iconColor)
        } else {
            paint.color = iconColor
            paint.textSize = sp(if (key.label.length > 1) 14f else 19f)
            paint.textAlign = Paint.Align.CENTER
            text.drawCentered(
                canvas,
                key.label,
                paint,
                key.bounds.centerX(),
                key.bounds.centerY(),
            )
        }
    }

    private fun drawSystemKey(
        canvas: Canvas,
        key: Key,
        pressed: Boolean,
    ) {
        if (pressed) {
            paint.style = Paint.Style.FILL
            paint.color = color(0x244F7CF5, 0x405E63D8)
            canvas.drawRoundRect(key.bounds, dp(12f), dp(12f), paint)
        }
        drawIcon(
            canvas = canvas,
            icon = if (key.code == KeyCodes.SWITCH_INPUT_METHOD) {
                Icon.KEYBOARD
            } else {
                Icon.CLIPBOARD
            },
            bounds = key.bounds,
            tint = color(0xFF39465B.toInt(), 0xFFE1E3E8.toInt()),
        )
    }

    private fun drawCardKey(
        canvas: Canvas,
        key: Key,
        pressed: Boolean,
    ) {
        paint.style = Paint.Style.FILL
        paint.color = if (pressed) {
            color(0x335B7DF0, 0x556D61D8)
        } else {
            color(0xBFFFFFFF.toInt(), 0xFF292A2C.toInt())
        }
        canvas.drawRoundRect(key.bounds, dp(11f), dp(11f), paint)
        paint.color = color(0xFF263247.toInt(), 0xFFF0F1F4.toInt())
        paint.textSize = sp(13f)
        paint.textAlign = Paint.Align.LEFT
        val x = key.bounds.left + dp(11f)
        val saveCount = canvas.save()
        canvas.clipRect(
            x,
            key.bounds.top + dp(3f),
            key.bounds.right - dp(40f),
            key.bounds.bottom - dp(3f),
        )
        text.drawCentered(
            canvas,
            key.label,
            paint,
            x,
            key.bounds.centerY() - if (key.secondaryLabel != null) dp(8f) else 0f,
        )
        val secondLine = key.secondaryLabel
        if (secondLine != null) {
            paint.color = color(0xFF6B7484.toInt(), 0xFF9B9EA5.toInt())
            text.drawCentered(
                canvas,
                secondLine,
                paint,
                x,
                key.bounds.centerY() + dp(10f),
            )
        }
        canvas.restoreToCount(saveCount)
    }

    private fun drawEmojiKey(
        canvas: Canvas,
        key: Key,
        pressed: Boolean,
    ) {
        if (pressed) {
            paint.style = Paint.Style.FILL
            paint.color = color(0x255B7DF0, 0x456D61D8)
            canvas.drawCircle(
                key.bounds.centerX(),
                key.bounds.centerY(),
                minOf(key.bounds.width(), key.bounds.height()) * 0.42f,
                paint,
            )
        }
        paint.style = Paint.Style.FILL
        paint.color = color(0xFF172033.toInt(), 0xFFF5F5F7.toInt())
        paint.textSize = sp(25f)
        paint.textAlign = Paint.Align.CENTER
        text.drawCentered(
            canvas,
            key.label,
            paint,
            key.bounds.centerX(),
            key.bounds.centerY(),
        )
    }

    private fun drawCategoryKey(
        canvas: Canvas,
        state: KeyboardRendererState,
        key: Key,
        pressed: Boolean,
    ) {
        val selected =
            (key.action as? KeyAction.SelectEmojiCategory)?.index == state.emojiGroupIndex
        if (selected || pressed) {
            paint.style = Paint.Style.FILL
            paint.color = color(0x224F7CF5, 0x385E63D8)
            canvas.drawRoundRect(key.bounds, dp(10f), dp(10f), paint)
        }
        paint.color = if (selected) {
            color(0xFF4F6FE8.toInt(), 0xFFC0B8FF.toInt())
        } else {
            color(0xFF647084.toInt(), 0xFFA4A8B0.toInt())
        }
        paint.textSize = sp(16f)
        paint.textAlign = Paint.Align.CENTER
        text.drawCentered(
            canvas,
            key.label,
            paint,
            key.bounds.centerX(),
            key.bounds.centerY(),
        )
    }

    private fun drawSymbolCategoryKey(
        canvas: Canvas,
        state: KeyboardRendererState,
        key: Key,
        pressed: Boolean,
    ) {
        val selected =
            (key.action as? KeyAction.SelectSymbolCategory)?.index ==
                state.symbolCategoryIndex
        if (selected || pressed) {
            paint.style = Paint.Style.FILL
            paint.color = if (selected) {
                color(0xFFCFD8E6.toInt(), 0xFF3B3D40.toInt())
            } else {
                color(0x804F7CF5.toInt(), 0x705E63D8)
            }
            canvas.drawRoundRect(key.bounds, dp(6f), dp(6f), paint)
        }
        paint.color = if (selected) {
            color(0xFF152033.toInt(), 0xFFF4F4F6.toInt())
        } else {
            color(0xFF5B6678.toInt(), 0xFFB8BBC2.toInt())
        }
        paint.textSize = sp(13.5f)
        paint.textAlign = Paint.Align.CENTER
        text.drawCentered(
            canvas,
            key.label,
            paint,
            key.bounds.centerX(),
            key.bounds.centerY(),
        )
    }

    private fun drawSymbolKey(
        canvas: Canvas,
        key: Key,
        pressed: Boolean,
    ) {
        paint.style = Paint.Style.FILL
        paint.color = if (pressed) {
            color(0x405B7DF0, 0x556D61D8)
        } else {
            color(0x66FFFFFF, 0x10FFFFFF)
        }
        canvas.drawRect(key.bounds, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = max(1f, density)
        paint.color = color(0x18172033, 0x24FFFFFF)
        canvas.drawRect(key.bounds, paint)
        paint.style = Paint.Style.FILL
        paint.color = color(0xFF172033.toInt(), 0xFFF5F5F7.toInt())
        paint.textSize = sp(
            when {
                key.label.length <= 2 -> 20f
                key.label.length <= 5 -> 15f
                else -> 10.5f
            },
        )
        paint.textAlign = Paint.Align.CENTER
        val textSave = canvas.save()
        canvas.clipRect(
            key.bounds.left + dp(2f),
            key.bounds.top,
            key.bounds.right - dp(2f),
            key.bounds.bottom,
        )
        text.drawCentered(
            canvas,
            key.label,
            paint,
            key.bounds.centerX(),
            key.bounds.centerY(),
        )
        canvas.restoreToCount(textSave)
    }

    private fun drawRailKey(
        canvas: Canvas,
        key: Key,
        pressed: Boolean,
    ) {
        paint.style = Paint.Style.FILL
        paint.color = if (pressed) {
            color(0xFF5B7DF0.toInt(), 0xFF6D61D8.toInt())
        } else {
            color(0xE6FFFFFF.toInt(), 0xFF303132.toInt())
        }
        canvas.drawRoundRect(key.bounds, dp(5f), dp(5f), paint)
        paint.color = if (pressed) {
            Color.WHITE
        } else {
            color(0xFF1C2433.toInt(), 0xFFF3F4F7.toInt())
        }
        paint.textSize = sp(17f)
        paint.textAlign = Paint.Align.CENTER
        text.drawCentered(
            canvas,
            key.label,
            paint,
            key.bounds.centerX(),
            key.bounds.centerY(),
        )
    }

    private fun drawEditorPrimaryKey(
        canvas: Canvas,
        state: KeyboardRendererState,
        key: Key,
        pressed: Boolean,
    ) {
        paint.style = Paint.Style.FILL
        paint.color = if (pressed || state.editorSelectionMode) {
            color(0xFF5B7DF0.toInt(), 0xFF6D61D8.toInt())
        } else {
            color(0xE8F5F7FB.toInt(), 0xFF303134.toInt())
        }
        canvas.drawRoundRect(key.bounds, dp(10f), dp(10f), paint)
        if (!pressed && !state.editorSelectionMode) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = max(1f, density)
            paint.color = color(0x30172033, 0x45FFFFFF)
            canvas.drawRoundRect(key.bounds, dp(10f), dp(10f), paint)
        }
        paint.color = if (pressed || state.editorSelectionMode) {
            Color.WHITE
        } else {
            color(0xFF172033.toInt(), 0xFFF3F4F7.toInt())
        }
        paint.textSize = sp(15f)
        paint.textAlign = Paint.Align.CENTER
        text.drawCentered(
            canvas,
            if (state.editorSelectionMode) EDITOR_CANCEL_SELECTION else EDITOR_START_SELECTION,
            paint,
            key.bounds.centerX(),
            key.bounds.centerY(),
        )
        paint.style = Paint.Style.FILL
    }

    private fun drawEditorActionKey(
        canvas: Canvas,
        state: KeyboardRendererState,
        key: Key,
        pressed: Boolean,
    ) {
        val enabled = state.isKeyEnabled(key)
        paint.style = Paint.Style.FILL
        paint.color = when {
            !enabled -> color(0x5CE1E6EE, 0x66303336)
            pressed -> color(0xFF5B7DF0.toInt(), 0xFF6D61D8.toInt())
            else -> color(0xD9E2E8F1.toInt(), 0xFF303134.toInt())
        }
        canvas.drawRoundRect(key.bounds, dp(8f), dp(8f), paint)
        if (enabled && !pressed) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = max(1f, density)
            paint.color = color(0x15172033, 0x24FFFFFF)
            canvas.drawRoundRect(key.bounds, dp(8f), dp(8f), paint)
        }
        paint.style = Paint.Style.FILL
        val tint = when {
            !enabled -> color(0x66717B8C, 0x668C9098)
            pressed -> Color.WHITE
            else -> color(0xFF344054.toInt(), 0xFFE8EAF0.toInt())
        }
        val icon = key.icon
        if (icon != null) {
            drawIcon(canvas, icon, key.bounds, tint)
        } else {
            paint.color = tint
            paint.textSize = sp(14.5f)
            paint.textAlign = Paint.Align.CENTER
            text.drawCentered(
                canvas,
                key.label,
                paint,
                key.bounds.centerX(),
                key.bounds.centerY(),
            )
        }
    }

    private fun drawEditorDirectionKey(
        canvas: Canvas,
        key: Key,
        pressed: Boolean,
    ) {
        if (pressed) {
            paint.style = Paint.Style.FILL
            paint.color = color(0x255B7DF0, 0x456D61D8)
            canvas.drawRoundRect(key.bounds, dp(9f), dp(9f), paint)
        }
        val tint = color(0xFF5D687A.toInt(), 0xFFB8BBC2.toInt())
        val icon = key.icon
        if (icon != null) {
            drawIcon(canvas, icon, key.bounds, tint)
        } else {
            paint.style = Paint.Style.FILL
            paint.color = color(0xFF172033.toInt(), 0xFFF3F4F7.toInt())
            paint.textSize = sp(16f)
            paint.textAlign = Paint.Align.CENTER
            text.drawCentered(
                canvas,
                key.label,
                paint,
                key.bounds.centerX(),
                key.bounds.centerY(),
            )
        }
    }

    private fun isActiveSkillSource(
        state: KeyboardRendererState,
        key: Key,
    ): Boolean = key === state.activeSkillSourceKey

    private fun drawIcon(
        canvas: Canvas,
        icon: Icon,
        bounds: RectF,
        tint: Int,
    ) {
        iconPainter.draw(canvas, icon, bounds, tint)
    }

    private fun skillAnimationPhase(): Float =
        if (ValueAnimator.areAnimatorsEnabled()) {
            val durationScale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ValueAnimator.getDurationScale().takeIf {
                    it.isFinite() && it > 0f
                } ?: 1f
            } else {
                1f
            }
            val period = (SKILL_AURORA_PERIOD_MILLIS * durationScale)
                .toLong()
                .coerceAtLeast(1L)
            (SystemClock.uptimeMillis() % period).toFloat() / period.toFloat()
        } else {
            0f
        }

    private fun KeyboardSkillDirection.arrow(): Char = when (this) {
        KeyboardSkillDirection.UP -> '↑'
        KeyboardSkillDirection.RIGHT -> '→'
        KeyboardSkillDirection.DOWN -> '↓'
        KeyboardSkillDirection.LEFT -> '←'
    }

    private fun color(
        light: Int,
        dark: Int,
    ): Int = palette.color(light, dark)

    private fun dp(value: Float): Float = value * density

    private fun sp(value: Float): Float = value * density * fontScale

    private companion object {
        const val EDITOR_CANCEL_SELECTION = "取消选择"
        const val EDITOR_START_SELECTION = "开始选择"
        const val SKILL_AURORA_PERIOD_MILLIS = 4_800L
        const val TWO_PI = 6.2831855f
    }
}
