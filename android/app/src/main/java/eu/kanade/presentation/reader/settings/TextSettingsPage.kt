package eu.kanade.presentation.reader.settings

import android.content.Intent
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState as collectFlowAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsViewModel
import eu.kanade.tachiyomi.ui.reader.tts.ReaderTtsController
import eu.kanade.tachiyomi.ui.reader.tts.ReaderTtsService
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.SettingsChipRow
import tachiyomi.presentation.core.components.SliderItem
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

private val fontFamilies = listOf(
    MR.strings.pref_text_reader_font_system to ReaderPreferences.TextFontFamily.SYSTEM,
    MR.strings.pref_text_reader_font_sans to ReaderPreferences.TextFontFamily.SANS_SERIF,
    MR.strings.pref_text_reader_font_serif to ReaderPreferences.TextFontFamily.SERIF,
    MR.strings.pref_text_reader_font_mono to ReaderPreferences.TextFontFamily.MONOSPACE,
    MR.strings.pref_text_reader_font_maru_buri to ReaderPreferences.TextFontFamily.MARU_BURI,
)
private val lineBreakModes = listOf(
    MR.strings.pref_text_reader_line_break_word to ReaderPreferences.TextLineBreakMode.WORD,
    MR.strings.pref_text_reader_line_break_character to ReaderPreferences.TextLineBreakMode.CHARACTER,
)
private enum class MainTextControl { FONT_SIZE, LINE_SPACING, PARAGRAPH_GAP }

@Composable
internal fun ColumnScope.TextSettingsPage(viewModel: ReaderSettingsViewModel) {
    val context = LocalContext.current
    val state by viewModel.textRepaginationState.collectFlowAsState()
    val controlTitles = listOf("글자 크기", "줄 간격", "문단 간격")
    var activeControl by remember { mutableStateOf(MainTextControl.FONT_SIZE) }
    var detailsExpanded by remember { mutableStateOf(false) }
    var ttsExpanded by remember { mutableStateOf(false) }

    // Reserve the same busy-status space in all states so the controls below never jump.
    Column(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 24.dp, vertical = 4.dp)) {
        when {
            state.first -> {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("페이지 재배치 중…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            state.second -> Text("페이지 재배치에 실패했습니다. 설정을 다시 변경해 재시도하세요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }

    SettingsChipRow(MR.strings.pref_category_text_reader) {
        MainTextControl.entries.forEachIndexed { index, control ->
            FilterChip(selected = activeControl == control, onClick = { activeControl = control }, label = { Text(controlTitles[index]) })
        }
    }
    when (activeControl) {
        MainTextControl.FONT_SIZE -> {
            val pref = viewModel.preferences.textFontSize
            val value by pref.collectAsState()
            SliderItem(value = value, valueRange = 10..30, label = stringResource(MR.strings.pref_text_reader_font_size), steps = 19, valueString = "$value sp", onChange = { pref.set(it); viewModel.requestTextSettingsReload() }, pillColor = MaterialTheme.colorScheme.surfaceContainerHighest)
        }
        MainTextControl.LINE_SPACING -> {
            val pref = viewModel.preferences.textLineSpacing
            val value by pref.collectAsState()
            SliderItem(value = value, valueRange = 90..180, label = stringResource(MR.strings.pref_text_reader_line_spacing), steps = 17, valueString = String.format("%.2fx", value / 100f), onChange = { pref.set(it); viewModel.requestTextSettingsReload() }, pillColor = MaterialTheme.colorScheme.surfaceContainerHighest)
        }
        MainTextControl.PARAGRAPH_GAP -> {
            val pref = viewModel.preferences.textParagraphSpacing
            val value by pref.collectAsState()
            SliderItem(value = value, valueRange = 0..16, label = stringResource(MR.strings.pref_text_reader_paragraph_spacing), steps = 15, valueString = "$value dp", onChange = { pref.set(it); viewModel.requestTextSettingsReload() }, pillColor = MaterialTheme.colorScheme.surfaceContainerHighest)
        }
    }

    TextButton(onClick = { detailsExpanded = !detailsExpanded }, modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(if (detailsExpanded) "세부 설정 닫기" else "글꼴·여백·줄바꿈 세부 설정")
    }
    if (detailsExpanded) {
        val horizontalPref = viewModel.preferences.textHorizontalMargin
        val horizontal by horizontalPref.collectAsState()
        SliderItem(value = horizontal, valueRange = 0..64, label = stringResource(MR.strings.pref_text_reader_horizontal_margin), steps = 15, valueString = "$horizontal dp", onChange = { horizontalPref.set(it); viewModel.previewAndRequestTextSettingsReload() }, pillColor = MaterialTheme.colorScheme.surfaceContainerHighest)
        val verticalPref = viewModel.preferences.textVerticalMargin
        val vertical by verticalPref.collectAsState()
        SliderItem(value = vertical, valueRange = 0..96, label = stringResource(MR.strings.pref_text_reader_vertical_margin), steps = 23, valueString = "$vertical dp", onChange = { verticalPref.set(it); viewModel.previewAndRequestTextSettingsReload() }, pillColor = MaterialTheme.colorScheme.surfaceContainerHighest)
        val breakPref = viewModel.preferences.textLineBreakMode
        val breakMode by breakPref.collectAsState()
        SettingsChipRow(MR.strings.pref_text_reader_line_break_mode) {
            lineBreakModes.forEach { (label, value) -> FilterChip(breakMode == value, { breakPref.set(value); viewModel.previewAndRequestTextSettingsReload() }, label = { Text(stringResource(label)) }) }
        }
        val familyPref = viewModel.preferences.textFontFamily
        val family by familyPref.collectAsState()
        SettingsChipRow(MR.strings.pref_text_reader_font_family) {
            fontFamilies.forEach { (label, value) -> FilterChip(family == value, { familyPref.set(value); viewModel.requestTextSettingsReload() }, label = { Text(stringResource(label)) }) }
        }
    }

    TextButton(onClick = { ttsExpanded = !ttsExpanded }, modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(if (ttsExpanded) "음성 읽기 설정 닫기" else "음성 읽기 설정")
    }
    if (ttsExpanded) TtsSettings(viewModel)
    Text(stringResource(MR.strings.pref_text_reader_pdf_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
}

@Composable
private fun TtsSettings(viewModel: ReaderSettingsViewModel) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        ReaderTtsController.refreshVoiceSettings(context, viewModel.preferences.textTtsEnginePackage.get())
    }
    val speedPref = viewModel.preferences.textTtsSpeedStep
    val speed by speedPref.collectAsState()
    SliderItem(value = speed, valueRange = 6..15, label = stringResource(MR.strings.pref_text_reader_tts_speed), steps = 8, valueString = String.format("%.1fx", speed / 5f), onChange = { speedPref.set(it); ReaderTtsController.setSpeed(context, it / 5f) }, pillColor = MaterialTheme.colorScheme.surfaceContainerHighest)
    val enginePref = viewModel.preferences.textTtsEnginePackage
    val selected by enginePref.collectAsState()
    val engines = remember {
        @Suppress("DEPRECATION")
        context.packageManager.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
            .mapNotNull { info -> info.serviceInfo?.packageName?.let { it to info.loadLabel(context.packageManager).toString() } }
            .distinctBy { it.first }.sortedBy { it.second.lowercase() }
    }
    SettingsChipRow(MR.strings.pref_text_reader_tts_engine) {
        FilterChip(selected == ReaderTtsService.AUTO_ENGINE, { enginePref.set(ReaderTtsService.AUTO_ENGINE); ReaderTtsController.setEngine(context, ReaderTtsService.AUTO_ENGINE) }, label = { Text(stringResource(MR.strings.pref_text_reader_tts_engine_auto)) })
        FilterChip(selected == ReaderTtsService.SYSTEM_DEFAULT_ENGINE, { enginePref.set(ReaderTtsService.SYSTEM_DEFAULT_ENGINE); ReaderTtsController.setEngine(context, ReaderTtsService.SYSTEM_DEFAULT_ENGINE) }, label = { Text(stringResource(MR.strings.pref_text_reader_tts_engine_system)) })
        engines.forEach { (packageName, label) -> FilterChip(selected == packageName, { enginePref.set(packageName); ReaderTtsController.setEngine(context, packageName) }, label = { Text(label) }) }
    }
    TextButton(onClick = { runCatching { launcher.launch(Intent("com.android.settings.TTS_SETTINGS")) } }, modifier = Modifier.padding(horizontal = 16.dp)) { Text(stringResource(MR.strings.pref_text_reader_tts_voice_settings)) }
}
