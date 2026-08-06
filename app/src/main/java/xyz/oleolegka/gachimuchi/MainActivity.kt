package xyz.oleolegka.gachimuchi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import xyz.oleolegka.gachimuchi.data.ActivityRepository
import xyz.oleolegka.gachimuchi.data.ProgramRepository
import xyz.oleolegka.gachimuchi.data.db.AppDatabase
import xyz.oleolegka.gachimuchi.timer.TimerController
import xyz.oleolegka.gachimuchi.timer.TimerNotifications
import xyz.oleolegka.gachimuchi.ui.GachiApp
import xyz.oleolegka.gachimuchi.ui.MainViewModel
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
                // first launch: write the demo history and the starter programs, so the
                // screens are not empty
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    vm.seedIfEmpty()
                    vm.seedProgramsIfEmpty()
                }
                GachiApp(vm)
            }
        }
    }
}
