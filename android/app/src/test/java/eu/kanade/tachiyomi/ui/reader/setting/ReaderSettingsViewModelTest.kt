package eu.kanade.tachiyomi.ui.reader.setting

import androidx.lifecycle.ViewModelStore
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderSettingsViewModelTest {

    @Test
    fun `settings preview immediately and newest request commits after debounce`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val previews = mutableListOf<String>()
        var commits = 0
        val store = ViewModelStore()
        try {
            val viewModel = ReaderSettingsViewModel(
                readerState = MutableStateFlow(ReaderViewModel.State()),
                onChangeReadingMode = {},
                onChangeOrientation = {},
                onTextSettingsPreview = { previews += "preview" },
                onTextSettingsCommit = { commits++ },
                preferences = mockk(relaxed = true),
            )
            store.put("settings", viewModel)

            viewModel.requestTextSettingsReload()
            assertEquals(listOf("preview"), previews)
            advanceTimeBy(200)
            runCurrent()

            viewModel.requestTextSettingsReload()
            assertEquals(listOf("preview", "preview"), previews)
            advanceTimeBy(249)
            runCurrent()
            assertEquals(0, commits)

            advanceTimeBy(1)
            runCurrent()
            assertEquals(1, commits)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }
}
