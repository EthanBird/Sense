package io.github.ethanbird.senseime.agent.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.findViewTreeViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/** Lazy Android View boundary so the ordinary IME path does not construct a composition. */
class AgentComposeHostView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    private var state by mutableStateOf(AgentUiState())
    private var actions by mutableStateOf(AgentUiActions())
    private val composeView = ComposeView(context)
    private val owners = AgentViewTreeOwners()
    private var renderingRequested = false
    private var released = false
    private var ownedWindowRoot: View? = null
    private var ownsWindowLifecycle = false
    private var ownsWindowSavedState = false
    private var ownsWindowViewModels = false

    init {
        // InputMethodService does not provide Activity-owned ViewTree owners. Install one stable
        // owner on this Android View boundary before Compose content is created, so every child
        // resolves the same lifecycle/saved-state tree while the IME swaps keyboard pages.
        owners.moveTo(Lifecycle.State.CREATED)
        setViewTreeLifecycleOwner(owners)
        setViewTreeSavedStateRegistryOwner(owners)
        setViewTreeViewModelStoreOwner(owners)
        composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        addView(
            composeView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        composeView.setContent {
            ImeAgentSurface(state = state, actions = actions)
        }
    }

    fun render(state: AgentUiState, actions: AgentUiActions) {
        this.state = state
        this.actions = actions
    }

    fun setRenderingActive(active: Boolean) {
        if (released) return
        renderingRequested = active
        owners.moveTo(
            if (active && isAttachedToWindow) Lifecycle.State.RESUMED else Lifecycle.State.CREATED,
        )
    }

    fun release() {
        if (released) return
        released = true
        renderingRequested = false
        composeView.disposeComposition()
        clearOwnedWindowRootOwners()
        owners.destroy()
    }

    override fun onAttachedToWindow() {
        // Compose obtains its WindowRecomposer from the *window root*, not from the nearest
        // parent of ComposeView. An IME window has no Activity/Fragment owners, so installing
        // owners only on this host still crashes on first attach with
        // "ViewTreeLifecycleOwner not found". Fill the missing root owners before ViewGroup
        // dispatches attachment to the child ComposeView.
        installMissingWindowRootOwners()
        super.onAttachedToWindow()
        if (!released && renderingRequested && visibility == VISIBLE) {
            owners.moveTo(Lifecycle.State.RESUMED)
        }
    }

    override fun onDetachedFromWindow() {
        if (!released) owners.moveTo(Lifecycle.State.CREATED)
        super.onDetachedFromWindow()
        clearOwnedWindowRootOwners()
    }

    private fun installMissingWindowRootOwners() {
        val root = rootView
        ownedWindowRoot = root
        if (root.findViewTreeLifecycleOwner() == null) {
            root.setViewTreeLifecycleOwner(owners)
            ownsWindowLifecycle = true
        }
        if (root.findViewTreeSavedStateRegistryOwner() == null) {
            root.setViewTreeSavedStateRegistryOwner(owners)
            ownsWindowSavedState = true
        }
        if (root.findViewTreeViewModelStoreOwner() == null) {
            root.setViewTreeViewModelStoreOwner(owners)
            ownsWindowViewModels = true
        }
    }

    private fun clearOwnedWindowRootOwners() {
        val root = ownedWindowRoot ?: return
        if (ownsWindowLifecycle && root.findViewTreeLifecycleOwner() === owners) {
            root.setViewTreeLifecycleOwner(null)
        }
        if (ownsWindowSavedState && root.findViewTreeSavedStateRegistryOwner() === owners) {
            root.setViewTreeSavedStateRegistryOwner(null)
        }
        if (ownsWindowViewModels && root.findViewTreeViewModelStoreOwner() === owners) {
            root.setViewTreeViewModelStoreOwner(null)
        }
        ownedWindowRoot = null
        ownsWindowLifecycle = false
        ownsWindowSavedState = false
        ownsWindowViewModels = false
    }

    private class AgentViewTreeOwners :
        LifecycleOwner,
        SavedStateRegistryOwner,
        ViewModelStoreOwner {
        private val lifecycleRegistry = LifecycleRegistry(this)
        private val savedStateController = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle = lifecycleRegistry
        override val savedStateRegistry: SavedStateRegistry
            get() = savedStateController.savedStateRegistry
        override val viewModelStore = ViewModelStore()

        init {
            savedStateController.performAttach()
            savedStateController.performRestore(null)
        }

        fun moveTo(state: Lifecycle.State) {
            lifecycleRegistry.currentState = state
        }

        fun destroy() {
            lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
            viewModelStore.clear()
        }
    }

}
