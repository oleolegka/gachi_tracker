package xyz.oleolegka.gachimuchi.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.oleolegka.gachimuchi.ui.ScreenTest

/**
 * The colour roles the app hands Material, checked against the palette they are supposed to
 * come from.
 *
 * ── The defect this exists for ──────────────────────────────────────────────────
 * Material fills a floating button from `primaryContainer` and a selected chip from
 * `secondaryContainer`. Leaving a role unset does NOT fall back to `primary`: it falls back
 * to the baseline Material lavender, which is off-palette and, on this app's near-white
 * plane, barely reads as a control at all. That is what shipped, it was found on a phone,
 * and nothing in the build noticed — the app compiled perfectly and every screen test that
 * did not exist yet would have passed anyway.
 *
 * ── What is checked, and what is left open ──────────────────────────────────────
 * The roles the screens actually paint with are required to be OURS. The roles nobody
 * paints with are pinned as still-Material, which is not an endorsement: it is a ledger.
 * The day a new Material component reads `tertiaryContainer`, the lavender comes back and
 * the first two tests here will not notice — the third will at least have said out loud
 * that the role was never claimed.
 *
 * The scheme is read through [MaterialTheme] rather than off the private `val` in
 * `Theme.kt`, because what matters is what a composable inside the theme ends up with.
 */
class ColorRolesTest : ScreenTest() {

    /**
     * Every colour named in `ui/theme/Color.kt`, which is the palette that passed the
     * research's CVD and contrast validator. Adding a colour there and using it in a scheme
     * means adding it here too, which is a deliberate speed bump: a colour that is in no
     * list is how the palette stopped being a palette last time.
     */
    private val palette: Set<Color> = buildSet {
        addAll(
            listOf(
                SurfaceLight, SurfaceDark, PlaneLight, PlaneDark, InkLight, InkDark,
                InkSecondaryLight, InkSecondaryDark, SurfaceRecessedLight, SurfaceRecessedDark,
                BorderLight, BorderDark, GoodTextLight, GoodTextDark, InkMuted,
                GridLight, GridDark, AxisLight, AxisDark, AccentLight, AccentDark,
                StatusGood, StatusWarning, StatusSerious, StatusCritical,
            )
        )
        addAll(Sequential)
        addAll(HeatmapLight)
        addAll(HeatmapDark)
        addAll(CategoricalLight)
        addAll(CategoricalDark)
        // the two one-offs declared in Theme.kt itself rather than in the palette file:
        // white text on the light accent, and a near-black for text on the dark one
        add(Color.White)
        add(Color(0xFF06121F))
    }

    /**
     * The roles this app's own components read, directly or through a Material control it
     * uses. These are the ones a wrong value is visible in.
     */
    private val painted = setOf(
        "Primary", "OnPrimary", "PrimaryContainer", "OnPrimaryContainer",
        "SecondaryContainer", "OnSecondaryContainer",
        "Background", "OnBackground", "Surface", "OnSurface",
        "SurfaceVariant", "OnSurfaceVariant", "Outline", "OutlineVariant", "Error",
    )

    /**
     * Both schemes, captured in ONE composition — the rule allows a single `setContent`, and
     * what is wanted here is the value a composable inside the theme ends up reading.
     */
    private fun schemes(): Map<Boolean, ColorScheme> {
        lateinit var light: ColorScheme
        lateinit var dark: ColorScheme
        screen {
            GachimuchiTheme(darkTheme = false) { light = MaterialTheme.colorScheme }
            GachimuchiTheme(darkTheme = true) { dark = MaterialTheme.colorScheme }
        }
        return mapOf(false to light, true to dark)
    }

    /**
     * Every role of a scheme, by name.
     *
     * Through Java reflection because `ColorScheme` has some forty roles and listing them by
     * hand would mean the list going stale the next time Material adds one — which is
     * exactly the moment this test is supposed to speak up. `Color` is a value class, so its
     * getters come back as primitive longs.
     */
    private fun rolesOf(scheme: ColorScheme): Map<String, Color> =
        ColorScheme::class.java.methods
            .filter { it.parameterCount == 0 && it.returnType == Long::class.javaPrimitiveType }
            .filter { it.name.startsWith("get") }
            .associate { method ->
                // the bits are the value class's own; toULong reinterprets, it does not convert
                method.name.removePrefix("get").substringBefore('-') to
                    Color((method.invoke(scheme) as Long).toULong())
            }

    @Test
    fun `every role the screens paint with is a colour from this app's palette`() {
        val schemes = schemes()
        for ((dark, scheme) in schemes) {
            val roles = rolesOf(scheme)
            val strays = painted.sorted().mapNotNull { name ->
                val colour = roles[name] ?: return@mapNotNull "$name (no such role any more)"
                if (colour in palette) null else "$name = $colour"
            }
            assertEquals(
                "off-palette roles in the ${if (dark) "dark" else "light"} scheme",
                emptyList<String>(), strays,
            )
        }
    }

    @Test
    fun `no painted role has been left to fall back to the Material baseline`() {
        val schemes = schemes()
        for ((dark, scheme) in schemes) {
            val ours = rolesOf(scheme)
            val baseline = rolesOf(if (dark) darkColorScheme() else lightColorScheme())
            /*
             * White text on the light accent, which is what this app wants and also what
             * Material's default happens to be. A check that works by comparing against the
             * baseline cannot tell "chosen" from "forgotten" when the two agree, so this one
             * role is beyond it — the membership test above is what covers it instead.
             */
            val forgotten = painted.filter { ours[it] == baseline[it] }
                .filterNot { !dark && it == "OnPrimary" }
                .sorted()
            assertEquals(
                "roles still at their Material default in the ${if (dark) "dark" else "light"} scheme",
                emptyList<String>(), forgotten,
            )
        }
    }

    /**
     * The ledger. These roles are NOT ours: any Material control that reads one draws in
     * baseline lavender, on both themes, exactly as the floating button once did.
     *
     * The list is pinned so that it can only change on purpose. Claiming a role means taking
     * its name out of here; a Material release that adds a role puts a new name in, and the
     * failure is the notice that something new is reachable and unpainted.
     */
    @Test
    fun `the roles this app has never claimed are pinned, so the gap stays visible`() {
        val ours = rolesOf(schemes().getValue(false))
        val baseline = rolesOf(lightColorScheme())
        val unclaimed = ours.keys.filter { ours[it] == baseline[it] }.sorted()

        assertEquals("the list of roles nobody has painted has changed", UNCLAIMED_ROLES, unclaimed)
    }

    private companion object {
        /**
         * Thirty-three roles this app has never set. Every one of them is baseline Material
         * lavender in both themes, and any control that reads one draws in it — which is
         * exactly what the floating button did.
         *
         * `OnPrimary` is on the list by coincidence rather than by omission: the light scheme
         * sets it to white deliberately, and Material's own default is white too, so no test
         * that works by comparing against the baseline can tell the two apart. Said out loud
         * because it is the one entry here that is NOT a gap.
         */
        val UNCLAIMED_ROLES = listOf(
            "ErrorContainer", "InverseOnSurface", "InversePrimary", "InverseSurface",
            "OnError", "OnErrorContainer", "OnPrimary", "OnPrimaryFixed",
            "OnPrimaryFixedVariant", "OnSecondary", "OnSecondaryFixed",
            "OnSecondaryFixedVariant", "OnTertiary", "OnTertiaryContainer", "OnTertiaryFixed",
            "OnTertiaryFixedVariant", "PrimaryFixed", "PrimaryFixedDim", "Scrim", "Secondary",
            "SecondaryFixed", "SecondaryFixedDim", "SurfaceBright", "SurfaceContainer",
            "SurfaceContainerHigh", "SurfaceContainerHighest", "SurfaceContainerLow",
            "SurfaceContainerLowest", "SurfaceDim", "Tertiary", "TertiaryContainer",
            "TertiaryFixed", "TertiaryFixedDim",
        )
    }
}
