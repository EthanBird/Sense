package io.github.ethanbird.senseime.brain.api

/** Deterministic Skill path that never opens a model/provider request. */
enum class ActionSkillExecutionMode {
    DIRECT_ZERO_MODEL_TOKEN,
}

enum class ActionSkillAuthMode {
    NONE,
    BEARER,
    API_KEY_HEADER,
}

data class ActionSkillDescriptor(
    val id: String,
    val displayName: String,
    val description: String,
    val executionMode: ActionSkillExecutionMode =
        ActionSkillExecutionMode.DIRECT_ZERO_MODEL_TOKEN,
    val authMode: ActionSkillAuthMode = ActionSkillAuthMode.NONE,
    val credentialHandle: String? = null,
    val credentialHeaderName: String? = null,
) {
    init {
        require(id.matches(ID_PATTERN))
        require(displayName.isNotBlank() && displayName.length <= 80)
        require(description.isNotBlank() && description.length <= 240)
        require((authMode == ActionSkillAuthMode.NONE) == (credentialHandle == null))
        credentialHandle?.let { require(it.matches(ID_PATTERN)) }
        credentialHeaderName?.let {
            require(it.matches(Regex("[A-Za-z][A-Za-z0-9-]{0,63}")))
        }
        require(authMode != ActionSkillAuthMode.API_KEY_HEADER || credentialHeaderName != null)
        require(authMode == ActionSkillAuthMode.API_KEY_HEADER || credentialHeaderName == null)
    }

    companion object {
        val ID_PATTERN = Regex("[a-z][a-z0-9._-]{2,63}")
    }
}

data class ActionSkillInvocation(
    val requestId: String,
    val skillId: String,
    val arguments: Map<String, String> = emptyMap(),
) {
    init {
        require(requestId.isNotBlank() && requestId.length <= 128)
        require(skillId.matches(ActionSkillDescriptor.ID_PATTERN))
        require(arguments.size <= 16)
        require(arguments.all { (key, value) ->
            key.matches(Regex("[a-z][a-z0-9_]{0,31}")) && value.length <= 1_024
        })
    }
}

data class ActionSkillResult(
    val requestId: String,
    val skillId: String,
    val title: String,
    val primaryValue: String,
    val secondaryValue: String,
    val insertText: String,
    val sourceLabel: String,
    val sourceUrl: String,
    val observedAtEpochMs: Long,
    val attributes: Map<String, String> = emptyMap(),
    /** Complete connector response retained as raw Action evidence; never sent to a model here. */
    val rawPayload: String = "",
) {
    init {
        require(requestId.isNotBlank())
        require(skillId.matches(ActionSkillDescriptor.ID_PATTERN))
        require(title.isNotBlank() && title.length <= 120)
        require(primaryValue.isNotBlank() && primaryValue.length <= 120)
        require(secondaryValue.length <= 240)
        require(insertText.isNotBlank() && insertText.length <= 2_000)
        require(sourceLabel.isNotBlank() && sourceLabel.length <= 120)
        require(sourceUrl.startsWith("https://"))
        require(observedAtEpochMs > 0L)
        require(attributes.size <= 16)
        require(rawPayload.length <= 131_072)
    }
}

data class ActionCredentialRef(
    val handle: String,
    val authMode: ActionSkillAuthMode,
    val headerName: String? = null,
) {
    init {
        require(handle.matches(ActionSkillDescriptor.ID_PATTERN))
        require(authMode != ActionSkillAuthMode.NONE)
        require(headerName == null || headerName.matches(Regex("[A-Za-z][A-Za-z0-9-]{0,63}")))
        require(authMode != ActionSkillAuthMode.API_KEY_HEADER || headerName != null)
    }
}
