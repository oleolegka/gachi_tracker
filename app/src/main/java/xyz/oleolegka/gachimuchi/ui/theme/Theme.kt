package xyz.oleolegka.gachimuchi.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import xyz.oleolegka.gachimuchi.domain.DayState
import xyz.oleolegka.gachimuchi.domain.ExerciseForm

/**
 * The app theme: Material3 plus our own roles from `research_visual.md` §6.
 *
 * Dynamic color (Material You) is NOT enabled: the palette is tuned for data
 * visualisation (axis contrast, a sequential ramp for heatmaps), and the user's
 * wallpaper would wreck it. The typeface is the system one, as the research requires;
 * no bundled ttf files.
 */

/** Roles missing from the M3 ColorScheme: grid, axis, muted text, statuses, forms. */
data class GachiColors(
    val plane: Color,
    val recessed: Color,
    val border: Color,
    val grid: Color,
    val axis: Color,
    val inkSecondary: Color,
    val inkMuted: Color,
    val accent: Color,
    val sequential: List<Color>,
    val heatmap: List<Color>,
    val categorical: List<Color>,
    val good: Color,
    val goodText: Color,
    val warning: Color,
    val serious: Color,
    val critical: Color,
) {
    /** Colour of an activity form (categorical scale, one fixed slot per form). */
    fun forForm(form: ExerciseForm): Color = categorical[(form.code - 1).coerceIn(categorical.indices)]

    /** Fill of a heatmap cell at an intensity level; 0 is "nothing happened". */
    fun forHeatmapLevel(level: Int): Color = heatmap[level.coerceIn(heatmap.indices)]

    /** Colour of a calendar day status (§12-B). Always paired with a label, never colour alone. */
    fun forDayState(state: DayState): Color = when (state) {
        DayState.DONE -> good
        DayState.MISS -> critical
        DayState.PLAN -> accent
        DayState.EXTRA -> inkMuted
        DayState.EMPTY -> grid
    }
}

private val LightGachiColors = GachiColors(
    plane = PlaneLight, recessed = SurfaceRecessedLight, border = BorderLight,
    grid = GridLight, axis = AxisLight,
    inkSecondary = InkSecondaryLight, inkMuted = InkMuted, accent = AccentLight,
    sequential = Sequential, heatmap = HeatmapLight, categorical = CategoricalLight,
    good = StatusGood, goodText = GoodTextLight,
    warning = StatusWarning, serious = StatusSerious, critical = StatusCritical,
)

private val DarkGachiColors = GachiColors(
    plane = PlaneDark, recessed = SurfaceRecessedDark, border = BorderDark,
    grid = GridDark, axis = AxisDark,
    inkSecondary = InkSecondaryDark, inkMuted = InkMuted, accent = AccentDark,
    sequential = Sequential, heatmap = HeatmapDark, categorical = CategoricalDark,
    good = StatusGood, goodText = GoodTextDark,
    warning = StatusWarning, serious = StatusSerious, critical = StatusCritical,
)

val LocalGachiColors = staticCompositionLocalOf { LightGachiColors }

private val LightScheme = lightColorScheme(
    primary = AccentLight,
    onPrimary = Color.White,
    background = PlaneLight,
    onBackground = InkLight,
    surface = SurfaceLight,
    onSurface = InkLight,
    surfaceVariant = PlaneLight,
    onSurfaceVariant = InkSecondaryLight,
    outline = GridLight,
    outlineVariant = GridLight,
    error = StatusCritical,
)

private val DarkScheme = darkColorScheme(
    primary = AccentDark,
    onPrimary = Color(0xFF06121F),
    background = PlaneDark,
    onBackground = InkDark,
    surface = SurfaceDark,
    onSurface = InkDark,
    surfaceVariant = SurfaceDark,
    onSurfaceVariant = InkSecondaryDark,
    outline = GridDark,
    outlineVariant = GridDark,
    error = StatusCritical,
)

/**
 * Typography: the system sans only (FontFamily.Default = the device typeface).
 * Hierarchy comes from size — the headline number large, captions small and in the
 * secondary colour (§6).
 */
private val GachiTypography = Typography().let { base ->
    base.copy(
        displaySmall = base.displaySmall.copy(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold),
        headlineSmall = base.headlineSmall.copy(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium),
        bodyMedium = base.bodyMedium.copy(fontFamily = FontFamily.Default),
        labelSmall = base.labelSmall.copy(fontFamily = FontFamily.Default, fontSize = 11.sp),
    )
}

@Composable
fun GachimuchiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val extended = if (darkTheme) DarkGachiColors else LightGachiColors
    CompositionLocalProvider(LocalGachiColors provides extended) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            typography = GachiTypography,
            content = content,
        )
    }
}
