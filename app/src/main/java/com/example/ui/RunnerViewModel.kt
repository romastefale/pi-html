package com.example.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.local.SavedWebAppEntity
import com.example.data.repository.WebAppRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RunnerUiState(
    val savedApps: List<SavedWebAppEntity> = emptyList(),
    val activeAppId: Long? = null,
    val isSidebarOpen: Boolean = false,
    val urlInput: String = "",
    val isDarkTheme: Boolean = false,
    val toastMessage: String? = null
) {
    val activeApp: SavedWebAppEntity?
        get() = savedApps.firstOrNull { it.id == activeAppId }
}

private data class InternalControlState(
    val activeAppId: Long? = null,
    val isSidebarOpen: Boolean = false,
    val urlInput: String = "",
    val isDarkTheme: Boolean = false,
    val toastMessage: String? = null
)

class RunnerViewModel(
    private val repository: WebAppRepository
) : ViewModel() {

    private val controlState = MutableStateFlow(
        InternalControlState(
            activeAppId = null,
            isSidebarOpen = false,
            urlInput = "",
            isDarkTheme = repository.isDarkThemeSaved(),
            toastMessage = null
        )
    )

    private var toastJob: Job? = null

    val uiState: StateFlow<RunnerUiState> = combine(
        repository.allApps,
        controlState
    ) { apps, ctrl ->
        val validActiveId = if (ctrl.activeAppId != null && apps.any { it.id == ctrl.activeAppId }) {
            ctrl.activeAppId
        } else {
            ctrl.activeAppId
        }
        RunnerUiState(
            savedApps = apps,
            activeAppId = validActiveId,
            isSidebarOpen = ctrl.isSidebarOpen,
            urlInput = ctrl.urlInput,
            isDarkTheme = ctrl.isDarkTheme,
            toastMessage = ctrl.toastMessage
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = RunnerUiState(isDarkTheme = repository.isDarkThemeSaved())
    )

    fun onUrlInputChange(newValue: String) {
        controlState.update { it.copy(urlInput = newValue) }
    }

    fun openSidebar() {
        controlState.update { it.copy(isSidebarOpen = true) }
    }

    fun closeSidebar() {
        controlState.update { it.copy(isSidebarOpen = false) }
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
                isSidebarOpen = false
            )
        }
    }

    fun selectSavedApp(appId: Long) {
        viewModelScope.launch {
            repository.touchLastOpened(appId)
            repository.setLastActiveAppId(appId)
            controlState.update {
                it.copy(
                    activeAppId = appId,
                    isSidebarOpen = false
                )
            }
        }
    }

    fun submitUrl() {
        val rawUrl = controlState.value.urlInput.trim()
        if (rawUrl.isEmpty()) return

        viewModelScope.launch {
            val created = repository.createOrOpenUrlApp(rawUrl)
            repository.setLastActiveAppId(created.id)
            controlState.update {
                it.copy(
                    activeAppId = created.id,
                    urlInput = "",
                    isSidebarOpen = false
                )
            }
        }
    }

    fun importHtmlUri(
        uri: Uri,
        onErrorToast: String
    ) {
        viewModelScope.launch {
            val result = repository.importHtmlFromUri(uri)
            result.onSuccess { entity ->
                repository.setLastActiveAppId(entity.id)
                controlState.update {
                    it.copy(
                        activeAppId = entity.id,
                        isSidebarOpen = false
                    )
                }
            }.onFailure {
                showToast(onErrorToast)
            }
        }
    }

    fun importRawHtmlForTestingOrIntent(rawHtml: String, fallbackTitle: String) {
        viewModelScope.launch {
            val entity = repository.importHtmlRawContent(rawHtml, fallbackTitle)
            repository.setLastActiveAppId(entity.id)
            controlState.update {
                it.copy(
                    activeAppId = entity.id,
                    isSidebarOpen = false
                )
            }
        }
    }

    fun deleteSavedApp(appId: Long, onDeletedCallback: (Long) -> Unit) {
        viewModelScope.launch {
            repository.deleteApp(appId)
            onDeletedCallback(appId)
            controlState.update { current ->
                val nextActiveId = if (current.activeAppId == appId) null else current.activeAppId
                repository.setLastActiveAppId(nextActiveId)
                current.copy(activeAppId = nextActiveId)
            }
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
