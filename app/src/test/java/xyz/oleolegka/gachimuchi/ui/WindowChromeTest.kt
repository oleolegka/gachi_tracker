package xyz.oleolegka.gachimuchi.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.oleolegka.gachimuchi.MainActivity
import xyz.oleolegka.gachimuchi.R

/**
 * The window the app is drawn into: no action bar, and the back gesture handed to the app.
 *
 * Both are single lines in a resource file and in the manifest, which is exactly why they
 * are worth a test. The action bar came free with the DeviceDefault parent theme, nobody
 * added it on purpose, and it took a strip off the top of every screen to print the name of
 * the app the user had just tapped — on the timer tab, whose list starts flush against the
 * top, it clipped the first card. Changing the parent theme later, or copying it into a new
 * one, would quietly bring the bar back, and nothing else in the build would notice.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WindowChromeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `the app theme has no action bar and no window title`() {
        val theme = context.resources.newTheme()
        theme.applyStyle(R.style.Theme_Gachimuchi, true)

        val attrs = theme.obtainStyledAttributes(
            intArrayOf(android.R.attr.windowActionBar, android.R.attr.windowNoTitle)
        )
        try {
            // defaults chosen so that a theme which simply forgot to say anything fails
            assertFalse("windowActionBar must be off", attrs.getBoolean(0, true))
            assertTrue("windowNoTitle must be on", attrs.getBoolean(1, false))
        } finally {
            attrs.recycle()
        }
    }

    /**
     * The other half of the same defect, which the test above cannot see.
     *
     * A theme with the bar switched off is worth nothing if the window the app is actually
     * drawn into is a different one. The activity's theme is resolved the way the platform
     * resolves it — off the manifest entry, through the application's default — so both ways
     * of losing it are covered: an activity pointed at some other style, and an application
     * whose default theme changed under an activity that never named one.
     */
    @Test
    fun `the window the app really opens in is the one without the bar`() {
        val activity = context.packageManager.getActivityInfo(
            android.content.ComponentName(context, MainActivity::class.java), 0,
        )
        val themeRes = activity.themeResource.takeIf { it != 0 }
            ?: activity.applicationInfo.theme
        assertTrue("MainActivity must resolve to a theme at all", themeRes != 0)

        val theme = context.resources.newTheme()
        theme.applyStyle(themeRes, true)
        val attrs = theme.obtainStyledAttributes(
            intArrayOf(android.R.attr.windowActionBar, android.R.attr.windowNoTitle)
        )
        try {
            // the bar took a strip off the top of every screen and clipped the first card of
            // the timer tab, whose list starts flush against the top of the content area
            assertFalse("the activity's own window must have no action bar", attrs.getBoolean(0, true))
            assertTrue("the activity's own window must have no title", attrs.getBoolean(1, false))
        } finally {
            attrs.recycle()
        }
    }

    @Test
    fun `the label the removed bar used to show is still the launcher label`() {
        // removing the bar must not remove the app's NAME: it is what the launcher and the
        // task switcher show, and it is the only place the name still belongs
        val label = context.packageManager
            .getApplicationInfo(context.packageName, 0)
            .loadLabel(context.packageManager)
            .toString()
        assertTrue(label.isNotBlank())
    }
}
