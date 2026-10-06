package com.example.ui

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.local.SavedWebAppEntity
import com.example.data.repository.WebAppRepository
import com.example.webview.ViewportBoundaryColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

@Immutable
data class RunnerUiState(
    val savedApps: List<SavedWebAppEntity> = emptyList(),
    val activeAppId: Long? = null,
    val activeApp: SavedWebAppEntity? = null,
    val activeViewportColors: ViewportBoundaryColors? = null,
    val isSidebarOpen: Boolean = false,
    val isOverlayControlsVisible: Boolean = true,
    val isDarkTheme: Boolean = false,
    val toastMessage: String? = null
) {
    val shouldShowTopAndBottomControls: Boolean
        get() = activeApp == null || isOverlayControlsVisible
}

private data class InternalControlState(
    val activeAppId: Long? = null,
    val activeViewportColors: ViewportBoundaryColors? = null,
    val isSidebarOpen: Boolean = false,
    val isOverlayControlsVisible: Boolean = true,
    val isDarkTheme: Boolean = false,
    val toastMessage: String? = null
)

class RunnerViewModel(
    private val repository: WebAppRepository
) : ViewModel() {

    private val initialActiveId = repository.getLastActiveAppId()
    private val initialDark = repository.isDarkThemeSaved()
    private val liveViewportColorsMap = ConcurrentHashMap<Long, ViewportBoundaryColors>()

    private val controlState = MutableStateFlow(
        InternalControlState(
            activeAppId = initialActiveId,
            activeViewportColors = repository.getInitialViewportColors(initialActiveId),
            isSidebarOpen = false,
            isOverlayControlsVisible = initialActiveId == null,
            isDarkTheme = initialDark,
            toastMessage = null
        )
    )

    // Isolated URL input StateFlow so typing in the bottom bar never recomposes RunnerScreen or WebView
    private val _urlInput = MutableStateFlow("")
    val urlInput: StateFlow<String> = _urlInput.asStateFlow()

    private var toastJob: Job? = null

    val uiState: StateFlow<RunnerUiState> = combine(
        repository.allApps,
        controlState
    ) { apps, ctrl ->
        val resolvedActiveApp = if (ctrl.activeAppId != null) {
            apps.firstOrNull { it.id == ctrl.activeAppId }
        } else {
            null
        }
        val validActiveId = resolvedActiveApp?.id ?: if (apps.isEmpty()) null else ctrl.activeAppId
        val resolvedColors = if (resolvedActiveApp != null) {
            ctrl.activeViewportColors
                ?: liveViewportColorsMap[resolvedActiveApp.id]
                ?: repository.getInitialViewportColors(resolvedActiveApp.id)
        } else {
            null
        }

        RunnerUiState(
            savedApps = apps,
            activeAppId = validActiveId,
            activeApp = resolvedActiveApp,
            activeViewportColors = resolvedColors,
            isSidebarOpen = ctrl.isSidebarOpen,
            isOverlayControlsVisible = ctrl.isOverlayControlsVisible,
            isDarkTheme = ctrl.isDarkTheme,
            toastMessage = ctrl.toastMessage
        )
    }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = RunnerUiState(isDarkTheme = initialDark)
        )

    fun onUrlInputChange(newValue: String) {
        _urlInput.value = newValue
    }

    fun openSidebar() {
        controlState.update {
            if (it.isSidebarOpen) it else it.copy(isSidebarOpen = true)
        }
    }

    fun closeSidebar() {
        controlState.update {
            if (!it.isSidebarOpen) it else it.copy(isSidebarOpen = false)
        }
    }

    fun showOverlayControls() {
        controlState.update {
            if (it.isOverlayControlsVisible) it else it.copy(isOverlayControlsVisible = true)
        }
    }

    fun hideOverlayControls() {
        val current = controlState.value
        if (current.activeAppId != null && current.isOverlayControlsVisible) {
            controlState.update { it.copy(isOverlayControlsVisible = false) }
        }
    }

    fun toggleTheme() {
        val nextDark = !controlState.value.isDarkTheme
        repository.setDarkThemeSaved(nextDark)
        controlState.update { it.copy(isDarkTheme = nextDark) }
    }

    fun showLauncherHome() {
        repository.setLastActiveAppId(null)
        controlState.update {
            it.copy(
                activeAppId = null,
                activeViewportColors = null,
                isSidebarOpen = false,
                isOverlayControlsVisible = true
            )
        }
    }

    /**
     * Switches to a saved app in 0ms synchronously on the main thread without blocking on disk/SQLite I/O.
     */
    fun selectSavedApp(appId: Long) {
        repository.setLastActiveAppId(appId)
        val cachedColors = liveViewportColorsMap[appId] ?: repository.getInitialViewportColors(appId)
        controlState.update {
            it.copy(
                activeAppId = appId,
                activeViewportColors = cachedColors,
                isSidebarOpen = false,
                isOverlayControlsVisible = false
            )
        }
    }

    fun onViewportColorsUpdated(appId: Long, topColor: Int, bottomColor: Int) {
        val colors = ViewportBoundaryColors(topColor = topColor, bottomColor = bottomColor)
        liveViewportColorsMap[appId] = colors
        if (controlState.value.activeAppId == appId && controlState.value.activeViewportColors != colors) {
            controlState.update { it.copy(activeViewportColors = colors) }
        }
    }

    fun submitUrl() {
        val rawUrl = _urlInput.value.trim()
        if (rawUrl.isEmpty()) return
        _urlInput.value = ""
        controlState.update {
            it.copy(
                isSidebarOpen = false,
                isOverlayControlsVisible = false
            )
        }

        viewModelScope.launch {
            val created = repository.createOrOpenUrlApp(rawUrl)
            repository.setLastActiveAppId(created.id)
            controlState.update {
                it.copy(
                    activeAppId = created.id,
                    activeViewportColors = liveViewportColorsMap[created.id]
                )
            }
        }
    }

    fun importHtmlUri(
        uri: Uri,
        onErrorToast: String
    ) {
        controlState.update {
            it.copy(
                isSidebarOpen = false,
                isOverlayControlsVisible = false
            )
        }
        viewModelScope.launch {
            val result = repository.importHtmlFromUri(uri)
            result.onSuccess { entity ->
                repository.setLastActiveAppId(entity.id)
                val initialColors = liveViewportColorsMap[entity.id]
                    ?: repository.getInitialViewportColors(entity.id)
                controlState.update {
                    it.copy(
                        activeAppId = entity.id,
                        activeViewportColors = initialColors
                    )
                }
            }.onFailure {
                showToast(onErrorToast)
            }
        }
    }

    fun importRawHtmlForTestingOrIntent(rawHtml: String, fallbackTitle: String) {
        val extractedColors = WebAppRepository.extractInitialHtmlColors(rawHtml)
        controlState.update {
            it.copy(
                activeViewportColors = extractedColors ?: it.activeViewportColors,
                isSidebarOpen = false,
                isOverlayControlsVisible = false
            )
        }
        viewModelScope.launch {
            val entity = repository.importHtmlRawContent(rawHtml, fallbackTitle)
            if (extractedColors != null) {
                liveViewportColorsMap[entity.id] = extractedColors
            }
            repository.setLastActiveAppId(entity.id)
            controlState.update {
                it.copy(
                    activeAppId = entity.id,
                    activeViewportColors = liveViewportColorsMap[entity.id]
                        ?: repository.getInitialViewportColors(entity.id)
                )
            }
        }
    }

    fun deleteSavedApp(appId: Long, onDeletedCallback: (Long) -> Unit) {
        liveViewportColorsMap.remove(appId)
        onDeletedCallback(appId)
        controlState.update { current ->
            val nextActiveId = if (current.activeAppId == appId) null else current.activeAppId
            repository.setLastActiveAppId(nextActiveId)
            current.copy(
                activeAppId = nextActiveId,
                activeViewportColors = if (nextActiveId == null) null else current.activeViewportColors,
                isOverlayControlsVisible = if (nextActiveId == null) true else current.isOverlayControlsVisible
            )
        }
        viewModelScope.launch {
            repository.deleteApp(appId)
        }
    }

    fun onWebViewTitleUpdated(appId: Long, title: String) {
        viewModelScope.launch {
            repository.updateTitleIfMeaningful(appId, title)
        }
    }

    fun onWebViewStateCaptured(
        appId: Long,
        lastVisitedUrl: String?,
        scrollX: Int,
        scrollY: Int,
        webViewStateBase64: String?
    ) {
        viewModelScope.launch {
            repository.updateResumeState(
                id = appId,
                lastVisitedUrl = lastVisitedUrl,
                scrollX = scrollX,
                scrollY = scrollY,
                webViewStateBase64 = webViewStateBase64
            )
        }
    }

    fun onRenderProcessGone(appId: Long) {
        if (controlState.value.activeAppId == appId) {
            selectSavedApp(appId)
        }
    }

    fun showToast(message: String) {
        toastJob?.cancel()
        controlState.update { it.copy(toastMessage = message) }
        toastJob = viewModelScope.launch {
            delay(2200L)
            controlState.update { it.copy(toastMessage = null) }
        }
    }

    class Factory(private val repository: WebAppRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return RunnerViewModel(repository) as T
        }
    }
}
