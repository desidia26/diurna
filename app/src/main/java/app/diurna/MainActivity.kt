package app.diurna

import android.content.Intent
import android.os.Bundle
import app.diurna.data.Backups
import app.diurna.checkin.CheckInReminder
import androidx.compose.runtime.LaunchedEffect
import java.time.LocalDate
import kotlinx.coroutines.launch
import app.diurna.alarm.AlarmActivity
import app.diurna.alarm.AlarmService
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.diurna.ui.DayScreen
import app.diurna.ui.DayViewModel
import app.diurna.ui.LibraryViewModel
import app.diurna.ui.RoutineViewModel
import app.diurna.ui.SetupViewModel
import app.diurna.ui.WeekViewModel
import app.diurna.ui.WhatsNewDialog
import app.diurna.ui.WhatsNewViewModel
import app.diurna.ui.MetricsScreen
import app.diurna.ui.MetricsViewModel
import app.diurna.ui.containerViewModel
import app.diurna.ui.RoutineScreen
import app.diurna.ui.SetupScreen
import app.diurna.ui.WeekScreen
import app.diurna.ui.theme.DiurnaTheme

private enum class Tab(val label: String, @DrawableRes val icon: Int) {
    Day("Day", R.drawable.ic_day),
    Week("Week", R.drawable.ic_week),
    Metrics("Metrics", R.drawable.ic_metrics),
    Routine("Routine", R.drawable.ic_routine),
    Setup("Setup", R.drawable.ic_setup),
}

class MainActivity : ComponentActivity() {
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        backupIfAsked(intent)
        openCheckInIfAsked(intent)
    }

    /** Bumped when the check-in notification asks for the Day tab. */
    private var showDayRequests by mutableStateOf(0)

    /** The check-in notification opens the day it was about, on the Day tab. */
    private fun openCheckInIfAsked(intent: Intent?) {
        val date = intent?.getStringExtra(CheckInReminder.EXTRA_DATE) ?: return
        container.selectedDate.goTo(LocalDate.parse(date))
        showDayRequests++
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
        if (savedInstanceState == null) openCheckInIfAsked(intent)
        // Only an open from the launcher shows what's new, not the check-in notification or a script.
        val launcherOpen = savedInstanceState == null && intent?.action == Intent.ACTION_MAIN
        val freshInstall = packageManager.getPackageInfo(packageName, 0).let { it.firstInstallTime == it.lastUpdateTime }
        setContent {
            DiurnaTheme {
                val dayVm = containerViewModel(c) { DayViewModel(it) }
                val weekVm = containerViewModel(c) { WeekViewModel(it) }
                val metricsVm = containerViewModel(c) { MetricsViewModel(it) }
                val routineVm = containerViewModel(c) { RoutineViewModel(it) }
                val setupVm = containerViewModel(c) { SetupViewModel(it) }
                val libraryVm = containerViewModel(c) { LibraryViewModel(it) }
                val whatsNewVm = containerViewModel(c) { WhatsNewViewModel(it, freshInstall) }
                var showWhatsNew by rememberSaveable { mutableStateOf(launcherOpen) }
                if (showWhatsNew) WhatsNewDialog(whatsNewVm) { showWhatsNew = false }
                var tab by rememberSaveable { mutableStateOf(Tab.Day) }
                LaunchedEffect(showDayRequests) { if (showDayRequests > 0) tab = Tab.Day }
                // An exercise opened from Day's menu goes back to Day, not to the Metrics list.
                var backToDay by rememberSaveable { mutableStateOf(false) }
                val tracked by metricsVm.tracked.collectAsStateWithLifecycle()
                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    bottomBar = {
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                            Tab.entries.forEach { t ->
                                NavigationBarItem(
                                    selected = tab == t,
                                    onClick = { tab = t; backToDay = false },
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
                            Tab.Day -> DayScreen(dayVm, inner, tracked) { id, cardio ->
                                if (cardio) metricsVm.openCardio(id) else metricsVm.openExercise(id)
                                backToDay = true
                                tab = Tab.Metrics
                            }
                            Tab.Week -> WeekScreen(weekVm, inner) { weekVm.goTo(it); tab = Tab.Day }
                            Tab.Metrics -> MetricsScreen(metricsVm, inner) {
                                metricsVm.closeExercise()
                                if (backToDay) { backToDay = false; tab = Tab.Day }
                            }
                            Tab.Routine -> RoutineScreen(routineVm, inner)
                            Tab.Setup -> SetupScreen(setupVm, libraryVm, inner)
                        }
                    }
                }
            }
        }
    }
}
