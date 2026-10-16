package app.daybook

import android.content.Intent
import android.os.Bundle
import app.daybook.data.Backups
import kotlinx.coroutines.launch
import app.daybook.alarm.AlarmActivity
import app.daybook.alarm.AlarmService
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.Icon
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import app.daybook.ui.DayScreen
import app.daybook.ui.DayViewModel
import app.daybook.ui.LibraryViewModel
import app.daybook.ui.RoutineViewModel
import app.daybook.ui.SetupViewModel
import app.daybook.ui.WeekViewModel
import app.daybook.ui.containerViewModel
import app.daybook.ui.RoutineScreen
import app.daybook.ui.SetupScreen
import app.daybook.ui.WeekScreen
import app.daybook.ui.theme.DaybookTheme

private enum class Tab(val label: String, @DrawableRes val icon: Int) {
    Day("Day", R.drawable.ic_day),
    Week("Week", R.drawable.ic_week),
    Routine("Routine", R.drawable.ic_routine),
    Setup("Setup", R.drawable.ic_setup),
}

class MainActivity : ComponentActivity() {
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        backupIfAsked(intent)
    }

    /** scripts/backup-db launches the app with this extra to get a fresh backup to pull. */
    private fun backupIfAsked(intent: Intent?) {
        if (intent?.getBooleanExtra(Backups.EXTRA_BACKUP, false) == true) {
            container.appScope.launch { container.backups.backupNow() }
        }
    }

    /** If the alarm is ringing (say its notification was swiped away), opening the app leads back to it. */
    override fun onResume() {
        super.onResume()
        if (AlarmService.ringing.value != null) {
            startActivity(Intent(this, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is always dark, so keep system bar icons light regardless of the phone's setting.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        val c = container
        backupIfAsked(intent)
        setContent {
            DaybookTheme {
                val dayVm = containerViewModel(c) { DayViewModel(it) }
                val weekVm = containerViewModel(c) { WeekViewModel(it) }
                val routineVm = containerViewModel(c) { RoutineViewModel(it) }
                val setupVm = containerViewModel(c) { SetupViewModel(it) }
                val libraryVm = containerViewModel(c) { LibraryViewModel(it) }
                var tab by rememberSaveable { mutableStateOf(Tab.Day) }
                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    bottomBar = {
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                            Tab.entries.forEach { t ->
                                NavigationBarItem(
                                    selected = tab == t,
                                    onClick = { tab = t },
                                    icon = { Icon(painterResource(t.icon), contentDescription = null) },
                                    label = { Text(t.label) },
                                )
                            }
                        }
                    },
                ) { padding ->
                    // Screens end at the top of the tab bar, or of the keyboard when it's taller.
                    // The padding is applied here rather than inside each list: as list content
                    // padding, the tab bar's space stayed reserved even with the keyboard covering
                    // it, so lists stopped scrolling one bar-height short of the keyboard.
                    Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
                        val inner = PaddingValues()
                        when (tab) {
                            Tab.Day -> DayScreen(dayVm, inner)
                            Tab.Week -> WeekScreen(weekVm, inner) { weekVm.goTo(it); tab = Tab.Day }
                            Tab.Routine -> RoutineScreen(routineVm, inner)
                            Tab.Setup -> SetupScreen(setupVm, libraryVm, inner)
                        }
                    }
                }
            }
        }
    }
}
