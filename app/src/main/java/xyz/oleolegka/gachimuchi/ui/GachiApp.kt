package xyz.oleolegka.gachimuchi.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.oleolegka.gachimuchi.domain.buildSession
import xyz.oleolegka.gachimuchi.ui.screens.CalendarScreen
import xyz.oleolegka.gachimuchi.ui.screens.LogScreen
import xyz.oleolegka.gachimuchi.ui.screens.OverviewScreen
import xyz.oleolegka.gachimuchi.ui.screens.TodayScreen

/**
 * Three tabs in the bottom bar (§12-C: Today is a tab of its own), plus the logging
 * screen on top of them.
 *
 * Navigation is still plain state, without navigation-compose. The logging screen is not
 * a route but a MODE: it takes over the whole window, has nothing to navigate to (the
 * exercise picker is a sheet), and leaving it is a single action. A back stack library
 * would buy nothing here and would cost a dependency plus saved-state plumbing.
 */
private enum class Tab(val title: String, val icon: ImageVector) {
    TODAY("Today", Icons.Filled.Star),
    OVERVIEW("Overview", Icons.AutoMirrored.Filled.List),
    CALENDAR("Calendar", Icons.Filled.DateRange),
}

@Composable
fun GachiApp(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val activeExerciseId by viewModel.activeExerciseId.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.TODAY) }
    var logging by rememberSaveable { mutableStateOf(false) }
    val today = remember { viewModel.today }
    val iso = today.toString()

    // "start" or "continue" is decided by the journal, not by a flag: a session is simply
    // everything recorded today, so a crash or a closed app never loses one
    val session = remember(state.events, iso) { buildSession(state.events, iso) }

    if (logging) {
        LogScreen(
            state = state,
            today = today,
            activeExerciseId = activeExerciseId,
            onSelectExercise = viewModel::selectExercise,
            onCreateExercise = viewModel::createExercise,
            onAddSet = viewModel::addSet,
            onUndoSet = viewModel::undoSet,
            onClose = { logging = false },
        )
        return
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = t.title) },
                        label = { Text(t.title) },
                    )
                }
            }
        },
        floatingActionButton = {
            if (tab == Tab.TODAY) {
                ExtendedFloatingActionButton(
                    onClick = {
                        // point the entry card at the exercise the workout left off on
                        if (activeExerciseId == null) {
                            viewModel.selectExercise(session.groups.lastOrNull()?.exerciseId)
                        }
                        logging = true
                    },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(if (session.isEmpty) "Start workout" else "Continue workout") },
                )
            }
        },
    ) { padding ->
        val inner = Modifier.padding(padding)
        when (tab) {
            Tab.TODAY -> TodayScreen(state, today, inner, onReseed = viewModel::reseed)
            Tab.OVERVIEW -> OverviewScreen(state, today, inner)
            Tab.CALENDAR -> CalendarScreen(state, today, inner)
        }
    }
}
