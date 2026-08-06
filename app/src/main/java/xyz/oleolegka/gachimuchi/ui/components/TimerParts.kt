package xyz.oleolegka.gachimuchi.ui.components

import android.Manifest
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import xyz.oleolegka.gachimuchi.domain.RunSnapshot
import xyz.oleolegka.gachimuchi.domain.StepKind
import xyz.oleolegka.gachimuchi.domain.TimerSettings

/**
 * The pieces the timer screens share: a clock that ticks for the UI only, and the
 * notification-permission conversation.
 */

/**
 * A recomposing reading of the monotonic clock, four times a second while [active].
 *
 * This is for DRAWING ONLY. Nothing about the run depends on it: the state holds the
 * moment the step ends and the remaining time is computed from the clock, so a dropped
 * frame or a screen that never redraws cannot make the timer wrong — it can only make it
 * look stale for a moment. Four times a second rather than once so the displayed second
 * changes within a frame or two of actually changing, instead of drifting up to a full
 * second behind.
 */
@Composable
fun rememberTickingNow(active: Boolean): Long {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(active) {
        while (active) {
            now = SystemClock.elapsedRealtime()
            delay(250)
        }
    }
    return if (active) now else SystemClock.elapsedRealtime()
}

/** Everything the screens need to know about the timer, in one value. */
data class TimerUiState(
    val enabled: Boolean,
    val run: RunSnapshot?,
    val settings: TimerSettings,
    val speechAvailable: Boolean,
    /** How long a rest for the current exercise would be, and where that number came from. */
    val restSec: Int,
    val restSource: String,
)

/** The commands a screen can issue. Grouped so a screen takes one parameter, not ten. */
data class TimerActions(
    val enable: () -> Unit,
    val startRest: () -> Unit,
    val pause: () -> Unit,
    val resume: () -> Unit,
    val skip: () -> Unit,
    val previous: () -> Unit,
    val nudge: (Int) -> Unit,
    val stop: () -> Unit,
)

/** The colour role a step should be drawn in; work and rest must not look alike. */
fun StepKind.isEffort(): Boolean = this == StepKind.WORK

/**
 * Turns the timer on, asking for the notification permission at that exact moment.
 *
 * ── Why the request lives here and not at launch ────────────────────────────────
 * Android 13 and later require POST_NOTIFICATIONS, and a permission dialog on first
 * launch, before the user has seen what the app does, is the reliable way to get a
 * permanent refusal. So nothing is asked until the timer is deliberately switched on, and
 * the reason is stated in a sentence first — on the phone this app is built for, an
 * unexplained permission prompt is a reason to uninstall.
 *
 * ── Refusal is not fatal, and is not treated as fatal ───────────────────────────
 * The timer still runs and still signals without the permission; what is lost is the
 * countdown in the shade and its buttons. The app therefore enables the timer either way
 * and says what was lost, rather than holding the feature hostage. It does not ask twice
 * and it does not send anyone to the system settings.
 */
@Composable
fun rememberTimerEnabler(onEnabled: () -> Unit): () -> Unit {
    var explaining by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { onEnabled() }

    if (explaining) {
        AlertDialog(
            onDismissRequest = { explaining = false },
            title = { Text("Let the timer show a notification") },
            text = {
                Text(
                    "The rest and workout timer keeps counting with the screen off and the " +
                        "phone in a pocket. A notification is how it shows the time left and " +
                        "gives you pause, skip and stop without unlocking.\n\n" +
                        "You can say no. The timer will still count and still buzz - it just " +
                        "will not appear in the notification shade."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    explaining = false
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        onEnabled()
                    }
                }) { Text("Continue") }
            },
            dismissButton = {
                TextButton(onClick = {
                    explaining = false
                    onEnabled()
                }) { Text("Not now") }
            },
        )
    }

    return { explaining = true }
}
