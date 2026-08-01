package io.github.ethanbird.senseime

import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView

/**
 * All strong references to the Skills page View tree.
 *
 * The Activity drops this object when the section is detached, allowing the large document editor
 * and every listener/adapter in that page to be collected while the draft remains in the controller.
 */
internal class SkillSettingsViewBinding(
    val root: LinearLayout,
    val selector: Spinner,
    val id: EditText,
    val name: EditText,
    val description: EditText,
    val content: EditText,
    val intent: Spinner,
    val bindingPicker: SkillKeyboardBindingPicker,
    val manageSection: LinearLayout,
    val templateSection: LinearLayout,
    val templateButtons: List<View>,
    val creationProgress: TextView,
    val historySection: LinearLayout,
    val historyContent: LinearLayout,
    val historyToggleButton: Button,
    val revisionSelector: Spinner,
    val historyPreview: TextView,
    val restoreRevisionButton: Button,
    val viewRevisionButton: Button,
    val createButton: Button,
    val discardButton: Button,
    val saveButton: Button,
    val bindButton: Button,
    val unbindSlotButton: Button,
    val unbindAllButton: Button,
    val slotOccupancy: TextView,
    val bindingSummary: TextView,
    val status: TextView,
) {
    var creating: Boolean = false

    val editableFields: List<EditText> = listOf(id, name, description, content)

    val editorControls: List<View> = listOf(
        selector,
        name,
        description,
        content,
        intent,
        bindingPicker,
        *templateButtons.toTypedArray(),
        revisionSelector,
        createButton,
        discardButton,
        historyToggleButton,
        viewRevisionButton,
        unbindSlotButton,
        unbindAllButton,
    )
}
