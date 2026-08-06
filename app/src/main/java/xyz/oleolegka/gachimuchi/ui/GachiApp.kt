package xyz.oleolegka.gachimuchi.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Star
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
import xyz.oleolegka.gachimuchi.ui.screens.CalendarScreen
import xyz.oleolegka.gachimuchi.ui.screens.OverviewScreen
import xyz.oleolegka.gachimuchi.ui.screens.TodayScreen

/**
 * Three screens in the bottom bar (§12-C: Today is a tab of its own).
 *
 * Navigation is plain state, without navigation-compose: there are exactly three tabs,
 * no links between them and no need for deep links. Once a workout logging screen with
 * its own back stack appears, that is the time to pull in the library.
 */
private enum class Tab(val title: String, val icon: ImageVector) {
    TODAY("Today", Icons.Filled.Star),
    OVERVIEW("Overview", Icons.AutoMirrored.Filled.List),
    CALENDAR("Calendar", Icons.Filled.DateRange),
}

@Composable
fun GachiApp(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.TODAY) }
    val today = remember { viewModel.today }

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
    ) { padding ->
        val inner = Modifier.padding(padding)
        when (tab) {
            Tab.TODAY -> TodayScreen(state, today, inner, onReseed = viewModel::reseed)
            Tab.OVERVIEW -> OverviewScreen(state, today, inner)
            Tab.CALENDAR -> CalendarScreen(state, today, inner)
        }
    }
}
