package xyz.oleolegka.gachimuchi.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The palette comes from `design/research_visual.md` §6 (it passed the validator for
 * CVD ΔE and for contrast in both themes). The values are copied AS IS; changing them
 * means redoing the research.
 *
 * These are roles, not "the colour of the button": screens are written against roles
 * ([GachiColors]), so swapping the palette does not require touching them.
 */

// surfaces and "ink"
val SurfaceLight = Color(0xFFFCFCFB)
val SurfaceDark = Color(0xFF1A1A19)
val PlaneLight = Color(0xFFF9F9F7)
val PlaneDark = Color(0xFF0D0D0D)
val InkLight = Color(0xFF0B0B0B)
val InkDark = Color(0xFFFFFFFF)
val InkSecondaryLight = Color(0xFF52514E)
val InkSecondaryDark = Color(0xFFC3C2B7)

/**
 * Recessed surface (`--surface-2`): the track of a segment control, a chip, an empty
 * heatmap cell. It sits BELOW the card rather than above it, which is what makes a
 * selected segment look raised without needing a shadow to say so.
 *
 * This role is not in `research_visual.md` §6 — it comes from the agreed design system
 * (`design-system/foundations/tokens.html`), which the §6 table predates.
 */
val SurfaceRecessedLight = Color(0xFFF0EFEC)
val SurfaceRecessedDark = Color(0xFF242422)


/** Hairline ring around cards (`--border` of §6): ink at 10 %, so it works on any surface. */
val BorderLight = Color(0x1A0B0B0B)
val BorderDark = Color(0x1AFFFFFF)

/**
 * Text weight of "good" (`--good-text`). Darker than [StatusGood] in the light theme
 * because the fill colour of a badge is not legible as 10 sp type on white; in the dark
 * theme the two coincide.
 */
val GoodTextLight = Color(0xFF006300)
val GoodTextDark = Color(0xFF0CA30C)

/** Muted (axes, captions) — identical in both themes, as prescribed by the research. */
val InkMuted = Color(0xFF898781)
val GridLight = Color(0xFFE1E0D9)
val GridDark = Color(0xFF2C2C2A)

/**
 * The two tones of a calendar day cell (§18.14): today and everything ahead of it in
 * [CalendarAheadLight]/[CalendarAheadDark], everything already gone in
 * [CalendarGoneLight]/[CalendarGoneDark]. The cell carries no other meaning — the dots do.
 *
 * ── Why these are palette entries and not a tint composited at the call site ─────
 * They used to be `surface` and `surface` with five percent black over it, which is a
 * DIFFERENCE OF FIVE PERCENT OF THE SURFACE'S OWN BRIGHTNESS — about thirteen levels out
 * of 255 in the light theme and ONE AND A HALF in the dark one, where the surface is
 * already near black. Reported from the phone as "the two tones are indistinguishable"
 * (2026-08-11), and in the dark theme they very nearly were the same colour.
 *
 * A fixed alpha cannot fix that, because the same alpha means a different amount of
 * contrast on a light surface and on a dark one. So the two tones are stated per theme
 * instead, each pair straddling its own surface — ahead a step lighter, gone a step
 * darker — which keeps "ahead is lighter" true in BOTH themes (the trap the old comment
 * warned about: `recessed` swaps direction between them) and keeps the difference worth
 * about a fifth of the cell's brightness either way, rather than a twentieth of it.
 *
 * Neither tone comes near `accent`, which is what a SELECTED cell is filled with, so the
 * selection still wins over both at a glance.
 *
 * ── The light pair is stated against the CARD, not against white ────────────────
 * Stating them per theme was only half the fix. In the light theme "ahead" was pure white on
 * a #FCFCFB card — three levels out of 255, so the whole future half of the grid had no
 * visible cells at all, and the redraw found it before a phone did
 * (`design-system/app-next/calendar.html`, change 1). The tones are now two existing tokens
 * one step apart each: `surface-2` (#F0EFEC, the recessed fill everything else in the app
 * uses for an empty slot) for what is still to come, and `grid` (#E1E0D9) for what is gone.
 * Twelve levels from the card, fifteen more to the past — the whole trio is separated rather
 * than only one pair of it, and no new colour was invented to do it.
 *
 * The DARK pair is deliberately left as it was: #262625 / #0F0F0E already straddle the
 * #1A1A19 card by a comfortable margin, and the bug was never there.
 */
val CalendarAheadLight = SurfaceRecessedLight
val CalendarGoneLight = GridLight
val CalendarAheadDark = Color(0xFF262625)
val CalendarGoneDark = Color(0xFF0F0F0E)
val AxisLight = Color(0xFFC3C2B7)
val AxisDark = Color(0xFF383835)

// sequential (single hue, blue, light -> dark): the full range for a heatmap
val Sequential = listOf(
    Color(0xFFCDE2FB), Color(0xFF86B6EF), Color(0xFF3987E5), Color(0xFF2A78D6),
    Color(0xFF256ABF), Color(0xFF1C5CAB), Color(0xFF184F95), Color(0xFF0D366B),
)

/**
 * The heatmap ramp: an empty day plus four intensity buckets.
 *
 * Four steps of [Sequential], not the whole eight — a legend nobody can tell apart is
 * decoration. The DARK RAMP IS REVERSED on purpose: on a near-black plane the dark end of
 * a blue ramp is the step that disappears, so there "brighter" has to mean "more" while
 * in the light theme "darker" means "more". Both read as one hue getting stronger, which
 * is the only thing a sequential scale has to promise.
 */
val HeatmapLight = listOf(
    Color(0xFFEEF0EC), Sequential[1], Sequential[2], Sequential[4], Sequential[6],
)
val HeatmapDark = listOf(
    Color(0xFF242422), Sequential[6], Sequential[4], Sequential[2], Sequential[1],
)

/** A single trend line or accent: the mid-dark step. */
val AccentLight = Color(0xFF2A78D6)
val AccentDark = Color(0xFF3987E5)

// categorical (fixed order, never cycled; the first three are reliably distinguishable)
val CategoricalLight = listOf(
    Color(0xFF2A78D6), Color(0xFFEB6834), Color(0xFF1BAF7A),
    Color(0xFFEDA100), Color(0xFFE87BA4), Color(0xFF008300),
)
val CategoricalDark = listOf(
    Color(0xFF3987E5), Color(0xFFD95926), Color(0xFF199E70),
    Color(0xFFC98500), Color(0xFFD55181), Color(0xFF008300),
)

// status (fixed, never "series N"; in the UI ALWAYS paired with a label, never colour alone)
val StatusGood = Color(0xFF0CA30C)
val StatusWarning = Color(0xFFFAB219)
val StatusSerious = Color(0xFFEC835A)
val StatusCritical = Color(0xFFD03B3B)
