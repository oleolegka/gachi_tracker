package xyz.oleolegka.gachimuchi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import xyz.oleolegka.gachimuchi.data.ActivityRepository
import xyz.oleolegka.gachimuchi.data.GalleryStore
import xyz.oleolegka.gachimuchi.data.ProgramRepository
import xyz.oleolegka.gachimuchi.data.db.AppDatabase
import xyz.oleolegka.gachimuchi.timer.TimerController
import xyz.oleolegka.gachimuchi.timer.TimerNotifications
import xyz.oleolegka.gachimuchi.ui.GachiApp
import xyz.oleolegka.gachimuchi.ui.MainViewModel
import xyz.oleolegka.gachimuchi.ui.celebrate.CelebrationHost
import xyz.oleolegka.gachimuchi.ui.screens.PictureOnboardingScreen
import xyz.oleolegka.gachimuchi.ui.theme.GachimuchiTheme

/**
 * The single Activity. Dependencies are wired by hand (database -> repository ->
 * ViewModel): a DI framework for three objects is dead weight, and Hilt would also drag
 * code generation into the build.
 *
 * There are NO Google Play Services and no Firebase in this project, and there will not
 * be any: the phone runs GrapheneOS, and the app is installed from GitHub releases via
 * Obtainium.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val db = AppDatabase.get(applicationContext)
        val repo = ActivityRepository(db)
        val programs = ProgramRepository(db)

        /*
         * The timer controller is process-wide rather than owned by this Activity: a run
         * has to survive the Activity being destroyed, the app being closed and the
         * process being killed and rebuilt by the alarm receiver. Fetching it here just
         * makes sure the notification channels exist before anything tries to post.
         */
        val timer = TimerController.get(applicationContext)
        TimerNotifications.ensureChannels(applicationContext)

        setContent {
            GachimuchiTheme {
                val vm: MainViewModel = viewModel(
                    factory = MainViewModel.Factory(repo, programs, timer)
                )
                /*
                 * First launch writes the two STARTER PROGRAMS and nothing else.
                 *
                 * It used to write ninety days of invented training as well, so that no
                 * screen would ever be seen empty. That was the wrong trade in an app whose
                 * only claim is that its journal is true: a new user was handed somebody
                 * else's history as if it were theirs, and on one occasion the seed wrote
                 * sets into exercises the user had made. Empty screens now say what to do
                 * instead, and there is no demo left to offer anywhere.
                 *
                 * The starter programs stay because they are not history. They are two real
                 * protocols in a list the user can delete, they claim nothing about what
                 * anyone has done, and the program editor is much easier to understand from
                 * an example than from a blank form.
                 */
                androidx.compose.runtime.LaunchedEffect(Unit) { vm.seedProgramsIfEmpty() }

                /*
                 * The celebration wraps the whole app rather than sitting on a screen: a
                 * set can be logged from more than one place and the picture has to appear
                 * over whatever is in front (see ui/celebrate/CelebrationHost.kt). Before
                 * any of it, once, the offer to add pictures at all.
                 */
                val gallery = androidx.compose.runtime.remember { GalleryStore.get(applicationContext) }
                val onboarded by gallery.onboardingDone.collectAsStateWithLifecycle()
                if (!onboarded) {
                    PictureOnboardingScreen(onDone = gallery::completeOnboarding)
                } else {
                    CelebrationHost(vm.celebrations) { GachiApp(vm) }
                }
            }
        }
    }
}
