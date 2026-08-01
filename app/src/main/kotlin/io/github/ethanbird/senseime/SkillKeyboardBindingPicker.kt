package io.github.ethanbird.senseime

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import io.github.ethanbird.senseime.brain.api.AgentSkillCatalog
import io.github.ethanbird.senseime.brain.api.AgentSkillDirection
import io.github.ethanbird.senseime.brain.api.AgentSkillSlot

/**
 * A real, tappable miniature keyboard for selecting a Skill binding slot.
 *
 * Every key and direction is an Android View rather than a canvas-only hit target, so TalkBack,
 * keyboard focus, and large-font users retain an explicit control for the same visual keyboard.
 */
@SuppressLint("ViewConstructor")
internal class SkillKeyboardBindingPicker(
    private val activity: Activity,
    private val views: SettingsViewFactory,
) : LinearLayout(activity) {
    private val keyButtons = LinkedHashMap<Int, TextView>()
    private val directionButtons = LinkedHashMap<AgentSkillDirection, TextView>()
    private val primaryKeyCodes = LinkedHashSet<Int>()
    private val extraKeyCodes = LinkedHashSet<Int>()
    private val selectionSummary = TextView(activity)
    private val moreKeysContainer = LinearLayout(activity)
    private val moreKeysToggle = TextView(activity)
    private val clearSelectionButton = TextView(activity)
    private var moreKeysVisible = false
    private var selectedKeyCode: Int? = null
    private var selectedDirection = AgentSkillDirection.UP
    private var catalog: AgentSkillCatalog? = null
    private var selectedSkillId: String? = null
    private var selectionListener: ((AgentSkillSlot?) -> Unit)? = null

    init {
        orientation = VERTICAL
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES

        addView(
            label(
                activity.getString(R.string.skills_binding_keyboard_title),
                15f,
                Typeface.BOLD,
                R.color.sense_primary,
            ),
        )
        addView(
            label(
                activity.getString(R.string.skills_binding_keyboard_hint),
                12f,
                Typeface.NORMAL,
                R.color.sense_secondary,
            ).withTop(views.dp(4)),
        )

        addKeyboardRow("1234567890".map { it.toString() to it.code })
            .withTop(views.dp(12))
            .also(::addView)
        addKeyboardRow("qwertyuiop".map { it.uppercase() to it.code })
            .withTop(views.dp(4))
            .also(::addView)
        addKeyboardRow("asdfghjkl".map { it.uppercase() to it.code }, horizontalInset = 10)
            .withTop(views.dp(4))
            .also(::addView)
        addKeyboardRow("zxcvbnm".map { it.uppercase() to it.code }, horizontalInset = 28)
            .withTop(views.dp(4))
            .also(::addView)
        addKeyboardRow(
            listOf(
                "⇧" to -1,
                "，" to -7,
                "中/英" to -6,
                "." to -8,
                "↵" to 10,
            ),
        ).withTop(views.dp(4)).also(::addView)

        moreKeysContainer.orientation = VERTICAL
        val extraOptions = SkillKeyOptions.all.filter { option ->
            option.keyCode != null && option.keyCode !in primaryKeyCodes
        }
        extraOptions.chunked(4).forEachIndexed { index, row ->
            row.mapNotNullTo(extraKeyCodes) { it.keyCode }
            moreKeysContainer.addView(
                addKeyboardRow(
                    row.map { compactLabel(it) to checkNotNull(it.keyCode) },
                ).withTop(if (index > 0) views.dp(4) else 0),
            )
        }
        moreKeysContainer.visibility = GONE
        moreKeysToggle.apply {
            text = activity.getString(R.string.skills_binding_more_keys)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            minimumHeight = views.dp(44)
            setTextColor(activity.getColor(R.color.sense_accent))
            isClickable = true
            isFocusable = true
            background = views.selectableItemBackground()
            setOnClickListener { setMoreKeysVisible(!moreKeysVisible) }
        }
        addView(moreKeysToggle.withTop(views.dp(4)))
        addView(moreKeysContainer)

        selectionSummary.apply {
            textSize = 13f
            setTextColor(activity.getColor(R.color.sense_primary))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            minimumHeight = views.dp(44)
            setPadding(views.dp(10), views.dp(8), views.dp(10), views.dp(8))
            background = views.rounded(
                activity.getColor(R.color.sense_background),
                views.dp(12).toFloat(),
            )
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        addView(selectionSummary.withTop(views.dp(10)))

        addView(
            label(
                activity.getString(R.string.skills_binding_direction_title),
                12f,
                Typeface.BOLD,
                R.color.sense_secondary,
            ).withTop(views.dp(10)),
        )
        addView(directionRow().withTop(views.dp(5)))

        clearSelectionButton.apply {
            text = activity.getString(R.string.skills_binding_clear_selection)
            textSize = 13f
            gravity = Gravity.CENTER
            minimumHeight = views.dp(44)
            setTextColor(activity.getColor(R.color.sense_secondary))
            isClickable = true
            isFocusable = true
            background = views.selectableItemBackground()
            setOnClickListener { setSelection(null, notify = true) }
        }
        addView(clearSelectionButton.withTop(views.dp(4)))
        render()
    }

    fun setOnSelectionChangedListener(listener: (AgentSkillSlot?) -> Unit) {
        selectionListener = listener
    }

    fun selectedSlot(): AgentSkillSlot? =
        selectedKeyCode?.let { AgentSkillSlot(it, selectedDirection) }

    fun setSelection(
        slot: AgentSkillSlot?,
        notify: Boolean = false,
    ) {
        selectedKeyCode = slot?.keyCode
        selectedDirection = slot?.direction ?: AgentSkillDirection.UP
        if (slot != null && slot.keyCode in extraKeyCodes) setMoreKeysVisible(true)
        render()
        if (notify) selectionListener?.invoke(selectedSlot())
    }

    fun renderCatalog(
        catalog: AgentSkillCatalog?,
        selectedSkillId: String?,
    ) {
        this.catalog = catalog
        this.selectedSkillId = selectedSkillId
        render()
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        keyButtons.values.forEach { it.isEnabled = enabled }
        directionButtons.values.forEach { button ->
            button.isEnabled = enabled && directionSelectable(button.tag as AgentSkillDirection)
        }
        moreKeysToggle.isEnabled = enabled
        clearSelectionButton.isEnabled = enabled
    }

    private fun addKeyboardRow(
        keys: List<Pair<String, Int>>,
        horizontalInset: Int = 0,
    ): LinearLayout = LinearLayout(activity).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        if (horizontalInset > 0) setPadding(views.dp(horizontalInset), 0, views.dp(horizontalInset), 0)
        keys.forEachIndexed { index, (label, keyCode) ->
            primaryKeyCodes += keyCode
            val button = keyButton(label, keyCode)
            addView(
                button,
                LayoutParams(0, views.dp(42), 1f).apply {
                    if (index > 0) marginStart = views.dp(3)
                },
            )
            keyButtons[keyCode] = button
        }
    }

    private fun keyButton(
        label: String,
        keyCode: Int,
    ): TextView = TextView(activity).apply {
        text = label
        textSize = if (label.length > 2) 10f else 12f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        setPadding(views.dp(2), 0, views.dp(2), 0)
        setOnClickListener {
            selectedKeyCode = keyCode
            if (!directionSelectable(selectedDirection)) {
                selectedDirection = AgentSkillDirection.UP
            }
            render()
            selectionListener?.invoke(selectedSlot())
        }
    }

    private fun directionRow(): LinearLayout = LinearLayout(activity).apply {
        orientation = HORIZONTAL
        AgentSkillDirection.entries.forEachIndexed { index, direction ->
            val button = TextView(activity).apply {
                tag = direction
                text = directionButtonLabel(direction)
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                minimumHeight = views.dp(48)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (!directionSelectable(direction)) return@setOnClickListener
                    selectedDirection = direction
                    render()
                    selectionListener?.invoke(selectedSlot())
                }
            }
            addView(
                button,
                LayoutParams(0, views.dp(48), 1f).apply {
                    if (index > 0) marginStart = views.dp(4)
                },
            )
            directionButtons[direction] = button
        }
    }

    private fun render() {
        keyButtons.forEach { (keyCode, button) -> renderKey(keyCode, button) }
        directionButtons.forEach { (direction, button) ->
            val selectable = directionSelectable(direction)
            button.isEnabled = isEnabled && selectable
            button.alpha = if (selectable) 1f else 0.38f
            val selected = selectedKeyCode != null && direction == selectedDirection
            button.setTextColor(
                if (selected) Color.WHITE else activity.getColor(R.color.sense_primary),
            )
            button.background = views.rounded(
                if (selected) {
                    activity.getColor(R.color.sense_accent)
                } else {
                    activity.getColor(R.color.sense_background)
                },
                views.dp(12).toFloat(),
            )
            button.contentDescription = when {
                !selectable -> activity.getString(
                    R.string.skills_binding_reserved_direction,
                    directionLabel(direction),
                    reservedCommand(direction).orEmpty(),
                )
                selected -> "${directionLabel(direction)}，已选择"
                else -> directionLabel(direction)
            }
        }
        selectionSummary.text = selectionText()
    }

    private fun renderKey(
        keyCode: Int,
        button: TextView,
    ) {
        val selected = selectedKeyCode == keyCode
        val bindings = catalog?.bindings.orEmpty().filter { it.slot.keyCode == keyCode }
        val ownedCount = bindings.count { it.skillId == selectedSkillId }
        val occupiedCount = bindings.size
        button.setTextColor(
            if (selected) Color.WHITE else activity.getColor(R.color.sense_primary),
        )
        button.background = views.rounded(
            fill = if (selected) {
                activity.getColor(R.color.sense_accent)
            } else {
                activity.getColor(R.color.sense_background)
            },
            radius = views.dp(9).toFloat(),
            stroke = when {
                selected -> null
                ownedCount > 0 -> activity.getColor(R.color.sense_accent)
                occupiedCount > 0 -> activity.getColor(R.color.sense_secondary)
                else -> null
            },
        )
        val label = SkillKeyOptions.labelOf(keyCode)
        button.contentDescription = buildString {
            append(label)
            when {
                ownedCount > 0 -> append("，当前 Skill 已绑定 $ownedCount 个方向")
                occupiedCount > 0 -> append("，已有 $occupiedCount 个方向绑定")
                else -> append("，未绑定")
            }
            if (selected) append("，已选择")
        }
    }

    private fun selectionText(): String {
        val slot = selectedSlot()
            ?: return activity.getString(R.string.skills_slot_not_selected)
        val reserved = SkillBindingSlotPolicy.reservedCommand(slot)
        if (reserved != null) {
            return activity.getString(
                R.string.skills_binding_reserved_slot,
                SkillKeyOptions.labelOf(slot.keyCode),
                directionLabel(slot.direction),
                reserved,
            )
        }
        return activity.getString(
            R.string.skills_binding_selected_slot,
            SkillKeyOptions.labelOf(slot.keyCode),
            directionLabel(slot.direction),
        )
    }

    private fun directionSelectable(direction: AgentSkillDirection): Boolean =
        reservedCommand(direction) == null

    private fun reservedCommand(direction: AgentSkillDirection): String? =
        selectedKeyCode?.let { keyCode ->
            SkillBindingSlotPolicy.reservedCommand(AgentSkillSlot(keyCode, direction))
        }

    private fun setMoreKeysVisible(visible: Boolean) {
        moreKeysVisible = visible
        moreKeysContainer.visibility = if (visible) VISIBLE else GONE
        moreKeysToggle.text = activity.getString(
            if (visible) R.string.skills_binding_less_keys else R.string.skills_binding_more_keys,
        )
    }

    private fun label(
        value: String,
        size: Float,
        style: Int,
        colorRes: Int,
    ): TextView = TextView(activity).apply {
        text = value
        textSize = size
        typeface = Typeface.create(Typeface.DEFAULT, style)
        setTextColor(activity.getColor(colorRes))
        setLineSpacing(0f, 1.15f)
    }

    private fun compactLabel(option: SkillKeyOption): String = when (option.keyCode) {
        -2 -> "符号"
        -3 -> "数字"
        -11 -> "字母"
        -9 -> "输入法"
        -19 -> "工具"
        -10 -> "剪贴板"
        -12 -> "Emoji"
        -13 -> "编辑"
        -14 -> "语音"
        -15 -> "隐藏"
        -20 -> "设置"
        else -> option.label.take(6)
    }

    private fun directionButtonLabel(direction: AgentSkillDirection): String = when (direction) {
        AgentSkillDirection.UP -> "↑ 上"
        AgentSkillDirection.RIGHT -> "→ 右"
        AgentSkillDirection.DOWN -> "↓ 下"
        AgentSkillDirection.LEFT -> "← 左"
    }

    private fun directionLabel(direction: AgentSkillDirection): String = when (direction) {
        AgentSkillDirection.UP -> "上滑"
        AgentSkillDirection.RIGHT -> "右滑"
        AgentSkillDirection.DOWN -> "下滑"
        AgentSkillDirection.LEFT -> "左滑"
    }
}
