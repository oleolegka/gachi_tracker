package xyz.oleolegka.gachimuchi.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import xyz.oleolegka.gachimuchi.data.ActivityRepository
import xyz.oleolegka.gachimuchi.data.db.AppDatabase
import xyz.oleolegka.gachimuchi.data.db.ExerciseEntity
import xyz.oleolegka.gachimuchi.timer.TimerController
import xyz.oleolegka.gachimuchi.ui.screens.NewExercise

/**
 * Taking a catalog exercise out of the pickers, removing one for good, or adding one that is
 * not about any workout at all.
 *
 * ── Correcting one is not here any more ────────────────────────────────────────
 * It was, as a dialog raised by this very composable. It is now a screen of its own —
 * `ui/screens/EditExerciseScreen.kt`, reached as a MODE from `ui/GachiApp.kt` the same way
 * the program editor is — because what it holds (a field, a picture, a switch and four
 * facts) is not a question with two answers, and as a dialog it did not fit on a phone and
 * had no scroll. See that file's own KDoc for the rest of the reasoning.
 *
 * What is left here is the three actions that need no form at all: each is one call and no
 * screen. They stay a bundle rather than three parameters because both callers take all
 * three or none.
 *
 * ── Why it writes through a repository of its own ──────────────────────────────
 * Every other action in this app arrives as a callback assembled in `ui/GachiApp.kt`, and
 * the screens that host this are reached from there and nowhere else. So the editor opens
 * the process-wide database itself — the same singleton the ViewModel's repository is built
 * on, so a write here reaches the catalog flow the screens observe, and the change appears
 * everywhere without anything being told to refresh.
 *
 * That is a deviation and is written down as one: when `GachiApp` is next open for editing,
 * these should become callbacks like everything else. The precedent it follows is the
 * settings tab, which reaches for its stores the same way.
 *
 * ── Why [create] does not go through `MainViewModel.createExercise` ─────────────
 * [xyz.oleolegka.gachimuchi.ui.MainViewModel.createExercise] always points the entry card at
 * the row it just made — right for every existing caller, which is either logging or planning
 * and has somewhere for the new row to go. A row added on its own has nowhere to go: the next
 * "Add" on Today would find that exercise sitting in `MainViewModel.activeExerciseId` and open
 * the entry card on it, which is exactly the workout-shaped side effect a plain catalog entry
 * must not have. [create] calls `ensureExercise` directly, the same way [toggleHidden] and
 * [delete] already reach past the ViewModel for their own writes.
 */
class ExerciseEditor internal constructor(
    /** Hides it from the pickers, or brings it back. */
    val toggleHidden: (ExerciseEntity) -> Unit,
    /**
     * Removes it from everywhere — the catalog and its own history — see
     * [xyz.oleolegka.gachimuchi.data.ActivityRepository.deleteExercise]. The caller is the one
     * that knows how many entries are about to go and confirms with the person before this is
     * reached; there is no confirmation in here to keep in step with a second one.
     */
    val delete: (ExerciseEntity) -> Unit,
    /**
     * Adds a catalog row and stops there — no active exercise, no navigation, nothing logged.
     * Goes through [xyz.oleolegka.gachimuchi.data.ActivityRepository.ensureExercise], the same
     * find-or-create used by every other caller, so a name that already exists is quietly
     * reused rather than duplicated.
     */
    val create: (new: NewExercise) -> Unit,
)

@Composable
fun rememberExerciseEditor(): ExerciseEditor {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember(context) { ActivityRepository(AppDatabase.get(context)) }
    // the one the whole process shares — the same object the workout screen's deletes reach
    val timer = remember(context) { TimerController.get(context) }

    return remember(repo, timer) {
        ExerciseEditor(
            toggleHidden = { exercise ->
                scope.launch { repo.setHidden(exercise.id, !exercise.hidden) }
            },
            delete = { exercise ->
                /*
                 * THE COUNTDOWNS FIRST, and outside the coroutine.
                 *
                 * Deleting a catalog row takes the exercise off every screen, and that used to be
                 * the whole of this. It is not: a rest under its card and a protocol run started
                 * from it both outlive the screen they began on — they are a foreground service, a
                 * notification and an exact alarm — and neither of them ever asks whether the row
                 * still exists. The exercise then disappears from the app while the phone goes on
                 * counting, and eventually speaking, for it. That is what "I deleted them ages ago
                 * and their timer is still going in the background" describes, and no amount of
                 * work inside `deleteExercise` could have fixed it: the timer is not in the
                 * database.
                 *
                 * Both cards of it, and the conductor whichever hand it was counting — the whole
                 * exercise is going, so there is no card left that a countdown could belong to.
                 */
                timer.floors.dismissAllOf(exercise.id)
                timer.stopFor(exercise.id)
                scope.launch { repo.deleteExercise(exercise) }
            },
            create = { new ->
                scope.launch {
                    repo.ensureExercise(
                        name = new.name,
                        form = new.form,
                        workSec = new.workSec,
                        restSec = new.restSec,
                        oneSided = new.oneSided,
                        protocolProgramId = new.protocolProgramId,
                    )
                }
            },
        )
    }
}
