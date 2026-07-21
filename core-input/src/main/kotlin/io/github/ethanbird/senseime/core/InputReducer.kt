package io.github.ethanbird.senseime.core

class InputReducer {
    fun reduce(state: InputState, action: InputAction): InputState = when (action) {
        is InputAction.Type -> {
            val next = state.composing + action.character.lowercaseChar()
            state.copy(composing = next, revision = state.revision + 1)
        }

        InputAction.Backspace -> {
            if (state.composing.isEmpty()) {
                state
            } else {
                state.copy(
                    composing = state.composing.dropLast(1),
                    revision = state.revision + 1,
                )
            }
        }

        is InputAction.Commit -> state.copy(
            composing = "",
            revision = state.revision + 1,
            committed = state.committed + action.text,
        )

        InputAction.Reset -> InputState(revision = state.revision + 1)
    }
}

