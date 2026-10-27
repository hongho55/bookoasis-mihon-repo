package eu.kanade.tachiyomi.ui.reader.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ReaderSettingsViewModel(
    readerState: StateFlow<ReaderViewModel.State>,
    val onChangeReadingMode: (ReadingMode) -> Unit,
    val onChangeOrientation: (ReaderOrientation) -> Unit,
    private val onTextSettingsPreview: () -> Unit,
    private val onTextSettingsCommit: () -> Unit,
    val preferences: ReaderPreferences,
) : ViewModel() {

    val viewerFlow = readerState
        .map { it.viewer }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    val mangaFlow = readerState
        .map { it.manga }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    val textRepaginationState = readerState
        .map { it.isTextRepaginating to it.textRepaginationFailed }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Lazily, false to false)

    private var textSettingsReloadJob: Job? = null
    private var textAppearanceReloadJob: Job? = null
    private var textSettingsDirty = false

    /**
     * Layout-affecting settings preview immediately on the current page, then debounce
     * the heavier rebuild that replaces its page boundaries.
     */
    fun requestTextSettingsReload() {
        onTextSettingsPreview()
        textSettingsDirty = true
        textSettingsReloadJob?.cancel()
        textSettingsReloadJob = viewModelScope.launch {
            delay(250)
            textSettingsDirty = false
            onTextSettingsCommit()
            textSettingsReloadJob = null
        }
    }

    fun previewAndRequestTextSettingsReload() {
        requestTextSettingsReload()
    }

    /**
     * Theme-only changes do not affect page boundaries, so avoid an unnecessary
     * full repagination.
     */
    fun requestTextAppearanceReload() {
        textAppearanceReloadJob?.cancel()
        textAppearanceReloadJob = viewModelScope.launch {
            delay(50)
            onTextSettingsPreview()
            textAppearanceReloadJob = null
        }
    }

    fun flushTextSettingsReload() {
        val hadPendingAppearanceReload = textAppearanceReloadJob != null
        textAppearanceReloadJob?.cancel()
        textAppearanceReloadJob = null
        if (!textSettingsDirty) {
            if (hadPendingAppearanceReload) {
                onTextSettingsPreview()
            }
            return
        }
        textSettingsReloadJob?.cancel()
        textSettingsReloadJob = null
        textSettingsDirty = false
        onTextSettingsCommit()
    }
}
