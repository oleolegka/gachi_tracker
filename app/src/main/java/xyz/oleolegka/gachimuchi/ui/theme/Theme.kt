package xyz.oleolegka.gachimuchi.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import xyz.oleolegka.gachimuchi.domain.ExerciseForm
import xyz.oleolegka.gachimuchi.domain.SlotState

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
    /**
     * Fill of a calendar day that is today or still to come — see [calendarGone] for the
     * other half of the pair and `ui/theme/Color.kt` for why there are two named tones
     * rather than one tint composited over the surface.
     */
    val calendarAhead: Color,
    /** Fill of a calendar day already gone. Always a step darker than [calendarAhead]. */
    val calendarGone: Color,
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

    /**
     * Colour of a calendar dot (§12-B rework, 2026-08-10). Always paired with the legend's
     * words, never colour alone — the same rule the day cell used to follow when it carried
     * this mapping instead of the dots.
     */
    fun forSlotState(state: SlotState): Color = when (state) {
        SlotState.DONE -> good
        SlotState.MISS -> critical
        SlotState.PLAN -> accent
    }
}

private val LightGachiColors = GachiColors(
    plane = PlaneLight, recessed = SurfaceRecessedLight, border = BorderLight,
    grid = GridLight, axis = AxisLight,
    inkSecondary = InkSecondaryLight, inkMuted = InkMuted, accent = AccentLight,
    calendarAhead = CalendarAheadLight, calendarGone = CalendarGoneLight,
    sequential = Sequential, heatmap = HeatmapLight, categorical = CategoricalLight,
    good = StatusGood, goodText = GoodTextLight,
    warning = StatusWarning, serious = StatusSerious, critical = StatusCritical,
)

private val DarkGachiColors = GachiColors(
    plane = PlaneDark, recessed = SurfaceRecessedDark, border = BorderDark,
    grid = GridDark, axis = AxisDark,
    inkSecondary = InkSecondaryDark, inkMuted = InkMuted, accent = AccentDark,
    calendarAhead = CalendarAheadDark, calendarGone = CalendarGoneDark,
    sequential = Sequential, heatmap = HeatmapDark, categorical = CategoricalDark,
    good = StatusGood, goodText = GoodTextDark,
    warning = StatusWarning, serious = StatusSerious, critical = StatusCritical,
)

val LocalGachiColors = staticCompositionLocalOf { LightGachiColors }

/*
 * ── The container ladder ────────────────────────────────────────────────────────
 * Material paints a whole family of chrome from the `surfaceContainer*` roles rather than
 * from `surface`, and an unset role does NOT fall back to `surface`: it falls back to the
 * baseline Material lavender. That is what the bottom tab bar, every AlertDialog, every
 * dropdown menu, every modal sheet and the track of LinearProgressIndicator were painted
 * with — a purple nobody chose, next to a palette that had been checked for contrast.
 *
 * Who reads what (Material 3, the components this app actually uses):
 *   surfaceContainer         tab bar (NavigationBar), dropdown menu
 *   surfaceContainerHigh     AlertDialog
 *   surfaceContainerLow      modal bottom sheet
 *   surfaceContainerHighest  the track of a progress indicator, a filled text field
 *   surfaceContainerLowest / surfaceBright / surfaceDim   nothing today; set so that the
 *                            next component to reach for one does not find lavender
 *   inverseSurface / inverseOnSurface   a snackbar, a plain tooltip
 *   scrim                    the dim behind a modal sheet
 *
 * Values come from `design-system/app-next/calendar.html` section F. Two deliberate
 * departures, both recorded there or forced by it:
 *
 * 1. The dark theme has ONE tone above the card (#242422), so sheet, menu, dialog and tab
 *    bar all get it. Section F says as much: the palette is closed, and the levels are told
 *    apart by border and shadow instead of by fill.
 * 2. `scrim` is our INK, not a translucent black. Section F states the composited result
 *    (black at 32 %), but Material's `scrim` role is the base colour to which the component
 *    applies its own 0.32 alpha — handing it a colour that is already 32 % would dim the
 *    plane by ten per cent instead of thirty-two.
 *
 * Section F contradicts itself once, and the role names win over its prose: it calls the
 * dialog `surface` (#FCFCFB) in one line and maps `surfaceContainerHigh` — the role an
 * AlertDialog actually reads — to #F0EFEC in the next. Dialogs therefore come out on the
 * recessed tone, a hair darker than a card, which is also what the lavender it replaces was.
 */
private val LightScheme = lightColorScheme(
    primary = AccentLight,
    onPrimary = Color.White,
    // Material fills the FAB from primaryContainer and a selected chip from
    // secondaryContainer. Leaving them unset does not fall back to `primary` — it falls
    // back to the baseline Material lavender, which is off-palette and, on a near-white
    // plane, barely reads as a control at all. Both are tints from our own blue ramp.
    primaryContainer = Sequential[0],
    onPrimaryContainer = Sequential[6],
    secondaryContainer = Sequential[0],
    onSecondaryContainer = Sequential[6],
    background = PlaneLight,
    onBackground = InkLight,
    surface = SurfaceLight,
    onSurface = InkLight,
    surfaceVariant = PlaneLight,
    onSurfaceVariant = InkSecondaryLight,
    outline = GridLight,
    outlineVariant = GridLight,
    error = StatusCritical,
    // The container ladder — see the block comment above for who reads which role.
    surfaceBright = SurfaceLight,
    surfaceContainerLowest = PlaneLight,
    surfaceContainerLow = SurfaceLight,
    surfaceContainer = SurfaceRecessedLight,
    surfaceContainerHigh = SurfaceRecessedLight,
    surfaceContainerHighest = GridLight,
    surfaceDim = GridLight,
    inverseSurface = InkLight,
    inverseOnSurface = SurfaceLight,
    scrim = InkLight,
)

private val DarkScheme = darkColorScheme(
    primary = AccentDark,
    onPrimary = Color(0xFF06121F),
    // Same reasoning as the light scheme, mirrored: a deep step of the ramp carries the
    // fill and a light one carries the text on top of it.
    primaryContainer = Sequential[6],
    onPrimaryContainer = Sequential[0],
    secondaryContainer = Sequential[6],
    onSecondaryContainer = Sequential[0],
    background = PlaneDark,
    onBackground = InkDark,
    surface = SurfaceDark,
    onSurface = InkDark,
    surfaceVariant = SurfaceDark,
    onSurfaceVariant = InkSecondaryDark,
    outline = GridDark,
    outlineVariant = GridDark,
    error = StatusCritical,
    // The container ladder, mirrored: our palette has ONE step above the card in the dark
    // theme, so the sheet, the menu, the dialog and the tab bar all land on it.
    surfaceBright = SurfaceRecessedDark,
    surfaceContainerLowest = PlaneDark,
    surfaceContainerLow = SurfaceRecessedDark,
    surfaceContainer = SurfaceRecessedDark,
    surfaceContainerHigh = SurfaceRecessedDark,
    surfaceContainerHighest = SurfaceRecessedDark,
    surfaceDim = PlaneDark,
    inverseSurface = InkDark,
    inverseOnSurface = SurfaceDark,
    // Not InkDark: the scrim is the dim BEHIND a sheet, and it has to be dark in both themes.
    scrim = InkLight,
)

/**
 * Typography: the system sans only (FontFamily.Default = the device typeface).
 * Hierarchy comes from size — the headline number large, captions small and in the
 * secondary colour (§6).
 *
 * The five sizes of [TextSize] mapped onto the Material slots the app actually asks for.
 * Material's own defaults are a 15-step scale for a different product; left alone they gave
 * this app four sizes nobody chose (16, 14, 14, 24) and no way to tell "title" from
 * "everything else" at a glance. The slots not listed here are unused — a screen that
 * reaches for one gets the Material default, which is why new type goes through the five
 * below rather than through a new slot.
 */
private val GachiTypography = Typography().let { base ->
    base.copy(
        // the crown: the one large number of a screen
        headlineSmall = base.headlineSmall.copy(
            fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold,
            fontSize = TextSize.Figure, lineHeight = TextSize.Figure * 1.25f,
        ),
        // the title of a card
        titleMedium = base.titleMedium.copy(
            fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium,
            fontSize = TextSize.Title, lineHeight = TextSize.Title * 1.35f,
        ),
        // secondary: a sub-heading inside a card, the name of a row
        titleSmall = base.titleSmall.copy(
            fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium,
            fontSize = TextSize.Meta, lineHeight = TextSize.Meta * 1.4f,
        ),
        // body
        bodyLarge = base.bodyLarge.copy(fontFamily = FontFamily.Default, fontSize = TextSize.Body),
        bodyMedium = base.bodyMedium.copy(fontFamily = FontFamily.Default, fontSize = TextSize.Body),
        bodySmall = base.bodySmall.copy(fontFamily = FontFamily.Default, fontSize = TextSize.Meta),
        // labels, down to the floor
        labelLarge = base.labelLarge.copy(fontFamily = FontFamily.Default, fontSize = TextSize.Meta),
        labelMedium = base.labelMedium.copy(fontFamily = FontFamily.Default, fontSize = TextSize.Caption),
        labelSmall = base.labelSmall.copy(fontFamily = FontFamily.Default, fontSize = TextSize.Caption),
    )
}

/**
 * Corner radius, handed to Material so that a Card, a dialog and a menu take theirs from
 * the same three numbers a hand-built shape does — see [Radius].
 *
 * `extraSmall` is a dropdown menu and `extraLarge` is a modal sheet; neither is a fourth
 * size, they are the small one and the dialog one under Material's names.
 */
private val GachiShapes = Shapes(
    extraSmall = RoundedCornerShape(Radius.Small),
    small = RoundedCornerShape(Radius.Small),
    medium = RoundedCornerShape(Radius.Card),
    large = RoundedCornerShape(Radius.Dialog),
    extraLarge = RoundedCornerShape(Radius.Dialog),
)

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
            shapes = GachiShapes,
            content = content,
        )
    }
}
