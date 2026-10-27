package eu.kanade.tachiyomi.ui.reader.setting

import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.os.Build
import android.util.TypedValue
import androidx.compose.ui.graphics.BlendMode
import dev.icerock.moko.resources.StringResource
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum
import tachiyomi.core.common.preference.getEnumSet
import tachiyomi.i18n.MR
import kotlin.math.roundToInt

@Inject
@SingleIn(AppScope::class)
class ReaderPreferences(
    preferenceStore: PreferenceStore,
) {

    // region General

    val pageTransitions: Preference<Boolean> = preferenceStore.getBoolean("pref_enable_transitions_key", true)

    val flashOnPageChange: Preference<Boolean> = preferenceStore.getBoolean("pref_reader_flash", false)

    val flashDurationMillis: Preference<Int> = preferenceStore.getInt("pref_reader_flash_duration", MILLI_CONVERSION)

    val flashPageInterval: Preference<Int> = preferenceStore.getInt("pref_reader_flash_interval", 1)

    val flashColor: Preference<FlashColor> = preferenceStore.getEnum("pref_reader_flash_mode", FlashColor.BLACK)

    val doubleTapAnimSpeed: Preference<Int> = preferenceStore.getInt("pref_double_tap_anim_speed", 500)

    val showPageNumber: Preference<Boolean> = preferenceStore.getBoolean("pref_show_page_number_key", true)

    val verticalNavigator: Preference<Set<ReadingMode>> = preferenceStore.getEnumSet(
        "pref_vertical_navigator",
        emptySet(),
    )

    val verticalNavigatorOnLeft: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_vertical_navigator_on_left",
        false,
    )

    val verticalNavigatorHeight: Preference<Int> = preferenceStore.getInt(
        "pref_vertical_navigator_height",
        65,
    )

    val showReadingMode: Preference<Boolean> = preferenceStore.getBoolean("pref_show_reading_mode", true)

    val fullscreen: Preference<Boolean> = preferenceStore.getBoolean("fullscreen", true)

    val drawUnderCutout: Preference<Boolean> = preferenceStore.getBoolean("cutout_short", true)

    val keepScreenOn: Preference<Boolean> = preferenceStore.getBoolean("pref_keep_screen_on_key", false)

    val defaultReadingMode: Preference<Int> = preferenceStore.getInt(
        "pref_default_reading_mode_key",
        ReadingMode.RIGHT_TO_LEFT.flagValue,
    )

    val defaultOrientationType: Preference<Int> = preferenceStore.getInt(
        "pref_default_orientation_type_key",
        ReaderOrientation.FREE.flagValue,
    )

    val webtoonDoubleTapZoomEnabled: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_enable_double_tap_zoom_webtoon",
        true,
    )

    val imageScaleType: Preference<Int> = preferenceStore.getInt("pref_image_scale_type_key", 1)

    val zoomStart: Preference<Int> = preferenceStore.getInt("pref_zoom_start_key", 1)

    val readerTheme: Preference<Int> = preferenceStore.getInt("pref_reader_theme_key", 1)

    val alwaysShowChapterTransition: Preference<Boolean> = preferenceStore.getBoolean(
        "always_show_chapter_transition",
        true,
    )

    val cropBorders: Preference<Boolean> = preferenceStore.getBoolean("crop_borders", false)

    val navigateToPan: Preference<Boolean> = preferenceStore.getBoolean("navigate_pan", true)

    val landscapeZoom: Preference<Boolean> = preferenceStore.getBoolean("landscape_zoom", true)

    val cropBordersWebtoon: Preference<Boolean> = preferenceStore.getBoolean("crop_borders_webtoon", false)

    val webtoonSidePadding: Preference<Int> = preferenceStore.getInt("webtoon_side_padding", WEBTOON_PADDING_MIN)

    val readerHideThreshold: Preference<ReaderHideThreshold> = preferenceStore.getEnum(
        "reader_hide_threshold",
        ReaderHideThreshold.LOW,
    )

    val folderPerManga: Preference<Boolean> = preferenceStore.getBoolean("create_folder_per_manga", false)

    val skipRead: Preference<Boolean> = preferenceStore.getBoolean("skip_read", false)

    val skipFiltered: Preference<Boolean> = preferenceStore.getBoolean("skip_filtered", true)

    val skipDupe: Preference<Boolean> = preferenceStore.getBoolean("skip_dupe", false)

    val webtoonDisableZoomOut: Preference<Boolean> = preferenceStore.getBoolean("webtoon_disable_zoom_out", false)

    // region Text reader

    val textFontSize: Preference<Int> = preferenceStore.getInt("reader_text_font_size", 16)

    val textLineSpacing: Preference<Int> = preferenceStore.getInt("reader_text_line_spacing", 115)

    val textParagraphSpacing: Preference<Int> = preferenceStore.getInt("reader_text_paragraph_spacing", 4)

    val textHorizontalMargin: Preference<Int> = preferenceStore.getInt("reader_text_horizontal_margin", 32)

    val textVerticalMargin: Preference<Int> = preferenceStore.getInt("reader_text_vertical_margin", 32)

    val textLineBreakMode: Preference<TextLineBreakMode> = preferenceStore.getEnum(
        "reader_text_line_break_mode",
        TextLineBreakMode.WORD,
    )

    val textFontFamily: Preference<TextFontFamily> = preferenceStore.getEnum(
        "reader_text_font_family",
        TextFontFamily.SYSTEM,
    )

    // TTS speed is stored as fifths: 6 = 1.2x, 15 = 3.0x.
    val textTtsSpeedStep: Preference<Int> = preferenceStore.getInt("reader_text_tts_speed_step", 6)

    // auto: prefer Google TTS when installed, then fall back to the Android system default.
    val textTtsEnginePackage: Preference<String> = preferenceStore.getString("reader_text_tts_engine", "auto")

    private val textUnitsMigrated = preferenceStore.getBoolean("reader_text_units_v2_migrated", false)

    init {
        if (!textUnitsMigrated.get()) {
            val legacyFontSize = textFontSize.get()
            if (legacyFontSize > 30) {
                textFontSize.set((legacyFontSize / 3f).roundToInt().coerceIn(10, 30))
                textParagraphSpacing.set((textParagraphSpacing.get() / 3f).roundToInt().coerceIn(0, 16))
            }
            textUnitsMigrated.set(true)
        }
    }

    fun textReaderStyle(context: Context): TextReaderStyle {
        val displayMetrics = context.resources.displayMetrics
        val rawWidth = displayMetrics.widthPixels.coerceAtLeast(1)
        val rawHeight = displayMetrics.heightPixels.coerceAtLeast(1)

        // Keep the same physical page proportions as the device while capping the
        // raster size so high-resolution phones do not create oversized bitmaps.
        val renderScale = minOf(
            1f,
            TEXT_READER_MAX_RENDER_WIDTH / rawWidth.toFloat(),
            TEXT_READER_MAX_RENDER_HEIGHT / rawHeight.toFloat(),
        )

        return TextReaderStyle(
            fontSize = textFontSize.get(),
            lineSpacingPercent = textLineSpacing.get(),
            paragraphSpacing = textParagraphSpacing.get(),
            horizontalMarginDp = textHorizontalMargin.get(),
            verticalMarginDp = textVerticalMargin.get(),
            lineBreakMode = textLineBreakMode.get(),
            fontFamily = textFontFamily.get(),
            typeface = textFontFamily.get().resolveTypeface(context),
            readerTheme = readerTheme.get(),
            automaticDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES,
            pageWidthPx = (rawWidth * renderScale).roundToInt().coerceAtLeast(1),
            pageHeightPx = (rawHeight * renderScale).roundToInt().coerceAtLeast(1),
            density = displayMetrics.density * renderScale,
            fontSizePx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                textFontSize.get().toFloat(),
                displayMetrics,
            ) * renderScale,
            paragraphSpacingPx = (
                TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP,
                    textParagraphSpacing.get().toFloat(),
                    displayMetrics,
                ) * renderScale
                ).roundToInt(),
        )
    }

    // endregion

    // endregion

    // region Split two-page spread

    val dualPageSplitPaged: Preference<Boolean> = preferenceStore.getBoolean("pref_dual_page_split", false)

    val dualPageInvertPaged: Preference<Boolean> = preferenceStore.getBoolean("pref_dual_page_invert", false)

    val dualPageSplitWebtoon: Preference<Boolean> = preferenceStore.getBoolean("pref_dual_page_split_webtoon", false)

    val dualPageInvertWebtoon: Preference<Boolean> = preferenceStore.getBoolean("pref_dual_page_invert_webtoon", false)

    val dualPageRotateToFit: Preference<Boolean> = preferenceStore.getBoolean("pref_dual_page_rotate", false)

    val dualPageRotateToFitInvert: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_dual_page_rotate_invert",
        false,
    )

    val dualPageRotateToFitWebtoon: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_dual_page_rotate_webtoon",
        false,
    )

    val dualPageRotateToFitInvertWebtoon: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_dual_page_rotate_invert_webtoon",
        false,
    )

    val dualPageView: Preference<DualPageView> = preferenceStore.getEnum(
        "pref_dual_page_view",
        DualPageView.NEVER,
    )

    // endregion

    // region Color filter

    val customBrightness: Preference<Boolean> = preferenceStore.getBoolean("pref_custom_brightness_key", false)

    val customBrightnessValue: Preference<Int> = preferenceStore.getInt("custom_brightness_value", 0)

    val colorFilter: Preference<Boolean> = preferenceStore.getBoolean("pref_color_filter_key", false)

    val colorFilterValue: Preference<Int> = preferenceStore.getInt("color_filter_value", 0)

    val colorFilterMode: Preference<Int> = preferenceStore.getInt("color_filter_mode", 0)

    val grayscale: Preference<Boolean> = preferenceStore.getBoolean("pref_grayscale", false)

    val invertedColors: Preference<Boolean> = preferenceStore.getBoolean("pref_inverted_colors", false)

    // endregion

    // region Controls

    val readWithLongTap: Preference<Boolean> = preferenceStore.getBoolean("reader_long_tap", true)

    val readWithVolumeKeys: Preference<Boolean> = preferenceStore.getBoolean("reader_volume_keys", false)

    val readWithVolumeKeysInverted: Preference<Boolean> = preferenceStore.getBoolean(
        "reader_volume_keys_inverted",
        false,
    )

    val navigationModePager: Preference<Int> = preferenceStore.getInt("reader_navigation_mode_pager", 0)

    val navigationModeWebtoon: Preference<Int> = preferenceStore.getInt("reader_navigation_mode_webtoon", 0)

    val pagerNavInverted: Preference<TappingInvertMode> = preferenceStore.getEnum(
        "reader_tapping_inverted",
        TappingInvertMode.NONE,
    )

    val webtoonNavInverted: Preference<TappingInvertMode> = preferenceStore.getEnum(
        "reader_tapping_inverted_webtoon",
        TappingInvertMode.NONE,
    )

    val showNavigationOverlayNewUser: Preference<Boolean> = preferenceStore.getBoolean(
        "reader_navigation_overlay_new_user",
        true,
    )

    val showNavigationOverlayOnStart: Preference<Boolean> = preferenceStore.getBoolean(
        "reader_navigation_overlay_on_start",
        false,
    )

    // endregion

    // region WebGpu

    val transitionAnimation: Preference<TransitionAnimation> =
        preferenceStore.getEnum("webgpu_transition_animation", TransitionAnimation.BASIC)

    val transitionAnimationDual: Preference<TransitionAnimation> =
        preferenceStore.getEnum("webgpu_dual_transition_animation", TransitionAnimation.BASIC)

    val cutoutMode: Preference<CutoutMode> = preferenceStore.getEnum("webgpu_cutout_mode", CutoutMode.AVOID)

    val cutoutModeDual: Preference<CutoutMode> = preferenceStore.getEnum("webgpu_dual_cutout_mode", CutoutMode.IGNORE)

    val continuousMinWidth: Preference<Int> = preferenceStore.getInt("webgpu_continuous_minwidth", 100)

    val continuousGap: Preference<Int> = preferenceStore.getInt("webgpu_continuous_gap", 10)

    // endregion

    enum class TextFontFamily(
        private val androidFamilyName: String? = null,
        private val assetPath: String? = null,
    ) {
        SYSTEM,
        SANS_SERIF(androidFamilyName = "sans-serif"),
        SERIF(androidFamilyName = "serif"),
        MONOSPACE(androidFamilyName = "monospace"),
        MARU_BURI(assetPath = "fonts/MaruBuri-Regular.ttf"),
        ;

        fun resolveTypeface(context: Context): Typeface = when {
            assetPath != null -> Typeface.createFromAsset(context.assets, assetPath)
            androidFamilyName != null -> Typeface.create(androidFamilyName, Typeface.NORMAL)
            else -> Typeface.DEFAULT
        }
    }

    enum class TextLineBreakMode {
        WORD,
        CHARACTER,
    }

    enum class FlashColor {
        BLACK,
        WHITE,
        WHITE_BLACK,
    }

    enum class TappingInvertMode(
        val titleRes: StringResource,
        val shouldInvertHorizontal: Boolean = false,
        val shouldInvertVertical: Boolean = false,
    ) {
        NONE(MR.strings.tapping_inverted_none),
        HORIZONTAL(
            MR.strings.tapping_inverted_horizontal,
            shouldInvertHorizontal = true,
        ),
        VERTICAL(
            MR.strings.tapping_inverted_vertical,
            shouldInvertVertical = true,
        ),
        BOTH(MR.strings.tapping_inverted_both, shouldInvertHorizontal = true, shouldInvertVertical = true),
    }

    enum class ReaderHideThreshold(val threshold: Int) {
        HIGHEST(5),
        HIGH(13),
        LOW(31),
        LOWEST(47),
    }

    enum class TransitionAnimation(val titleRes: StringResource) {
        BASIC(MR.strings.transition_animation_basic),
        FLIP(MR.strings.transition_animation_flip),
        FLIP_LEFT(MR.strings.transition_animation_flip_left),
        FLIP_RIGHT(
            MR.strings.transition_animation_flip_right,
        ),
        STACK_LEFT(MR.strings.transition_animation_stack_left),
        STACK_RIGHT(MR.strings.transition_animation_stack_right),
        STACK_UP(
            MR.strings.transition_animation_stack_up,
        ),
        STACK_DOWN(MR.strings.transition_animation_stack_down),
        SPHERE(MR.strings.transition_animation_sphere),
        CUBE_INSIDE(
            MR.strings.transition_animation_cube_inside,
        ),
        CUBE_OUTSIDE(MR.strings.transition_animation_cube_outside),
        FADE(MR.strings.transition_animation_fade),
        FADE_WHITE(
            MR.strings.transition_animation_fade_white,
        ),
        NONE(MR.strings.transition_animation_none),
    }

    enum class CutoutMode(val titleRes: StringResource) {
        IGNORE(MR.strings.cutout_mode_ignore),
        AVOID(MR.strings.cutout_mode_avoid),
        SHIFT(MR.strings.cutout_mode_shift),
    }

    enum class DualPageView(val titleRes: StringResource) {
        NEVER(MR.strings.dual_page_view_never),
        ALWAYS(MR.strings.dual_page_view_always),
        WIDE(MR.strings.dual_page_view_wide),
    }

    companion object {
        const val WEBTOON_PADDING_MIN = 0
        const val WEBTOON_PADDING_MAX = 25

        private const val TEXT_READER_MAX_RENDER_WIDTH = 1440
        private const val TEXT_READER_MAX_RENDER_HEIGHT = 2400

        const val MILLI_CONVERSION = 100

        val TapZones = listOf(
            MR.strings.label_default,
            MR.strings.l_nav,
            MR.strings.kindlish_nav,
            MR.strings.edge_nav,
            MR.strings.right_and_left_nav,
            MR.strings.disabled_nav,
        )

        val ImageScaleType = listOf(
            MR.strings.scale_type_fit_screen,
            MR.strings.scale_type_stretch,
            MR.strings.scale_type_fit_width,
            MR.strings.scale_type_fit_height,
            MR.strings.scale_type_original_size,
            MR.strings.scale_type_smart_fit,
        )

        val ImageScaleTypeWebGpuViewer = listOf(
            MR.strings.scale_type_fit_screen,
            MR.strings.scale_type_fit_width,
            MR.strings.scale_type_fit_height,
            MR.strings.scale_type_original_size,
        )

        val ZoomStart = listOf(
            MR.strings.zoom_start_automatic,
            MR.strings.zoom_start_left,
            MR.strings.zoom_start_right,
            MR.strings.zoom_start_center,
        )

        val ColorFilterMode = buildList {
            addAll(
                listOf(
                    MR.strings.label_default to BlendMode.SrcOver,
                    MR.strings.filter_mode_multiply to BlendMode.Modulate,
                    MR.strings.filter_mode_screen to BlendMode.Screen,
                ),
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                addAll(
                    listOf(
                        MR.strings.filter_mode_overlay to BlendMode.Overlay,
                        MR.strings.filter_mode_lighten to BlendMode.Lighten,
                        MR.strings.filter_mode_darken to BlendMode.Darken,
                    ),
                )
            }
        }
    }
}


data class TextReaderStyle(
    val fontSize: Int,
    val lineSpacingPercent: Int,
    val paragraphSpacing: Int,
    val horizontalMarginDp: Int,
    val verticalMarginDp: Int,
    val lineBreakMode: ReaderPreferences.TextLineBreakMode,
    val fontFamily: ReaderPreferences.TextFontFamily,
    val typeface: Typeface,
    val readerTheme: Int,
    val automaticDark: Boolean,
    val pageWidthPx: Int,
    val pageHeightPx: Int,
    val density: Float,
    val fontSizePx: Float,
    val paragraphSpacingPx: Int,
)
