package app.daybook.ui

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.daybook.alarm.ArmedState
import app.daybook.scan.QrScanner
import app.daybook.scan.qrBitmap
import app.daybook.ui.theme.LocalStatusColors
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private data class Check(val label: String, val detail: String, val ok: Boolean, val fix: () -> Unit)

/** Setup, plus the Library's Meals and Exercises pages, which open over it with a way back. */
@Composable
fun SetupScreen(vm: SetupViewModel, library: LibraryViewModel, contentPadding: PaddingValues) {
    var page by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = page != null) { page = null }
    when (page) {
        "meals" -> MealsPage(library) { page = null }
        "exercises" -> ExercisesPage(library) { page = null }
        else -> SetupMain(vm, library, contentPadding, onOpen = { page = it })
    }
}

@Composable
private fun SetupMain(vm: SetupViewModel, library: LibraryViewModel, contentPadding: PaddingValues, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++; vm.refreshAlarm() }
    val requestPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }

    val secret by vm.porchSecret.collectAsStateWithLifecycle()
    val confirmed by vm.porchConfirmed.collectAsStateWithLifecycle()
    val armed by vm.armed.collectAsStateWithLifecycle()
    var scanning by remember { mutableStateOf(false) }
    var confirmReplace by remember { mutableStateOf(false) }

    val checks = remember(refresh) { permissionChecks(context) { requestPermission.launch(it) } }

    LazyColumn(
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp, top = contentPadding.calculateTopPadding() + 16.dp,
            bottom = contentPadding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("Setup", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
        item {
            SectionCard("Alarm status") {
                Text(
                    when (val a = armed) {
                        is ArmedState.Armed -> "Next alarm: ${a.next.date.format(WeekdayShort)} ${a.next.date.short()}, ${a.next.time}"
                        ArmedState.NoneUpcoming -> "No alarm in the next week."
                        ArmedState.NeedsPorchCode -> "Not armed: scan your printed porch code once below."
                        ArmedState.NotAllowed -> "Not armed: exact alarms aren't allowed. Fix it in the checklist."
                    }
                )
                TestAlarmControls(vm)
            }
        }
        item {
            val meals by library.meals.collectAsStateWithLifecycle()
            val catalog by library.catalog.collectAsStateWithLifecycle()
            LibraryCard(meals.size, catalog.size, onMeals = { onOpen("meals") }, onExercises = { onOpen("exercises") })
        }
        item { TargetsCard(vm) }
        item { FoodEstimatesCard(vm) }
        item {
            val lockOn by vm.lockWallpaper.collectAsStateWithLifecycle()
            SectionCard("Lock screen") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Show today on lock screen", fontWeight = FontWeight.SemiBold)
                        Hint("Replaces your lock screen wallpaper with a dark summary of your day. Turning it off goes back to your home screen wallpaper.")
                    }
                    Switch(checked = lockOn, onCheckedChange = vm::setLockWallpaper)
                }
            }
        }
        item {
            SectionCard("Permissions") {
                checks.forEach { c ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(c.label, fontWeight = FontWeight.SemiBold)
                            Hint(c.detail)
                        }
                        if (c.ok) Chip("Done", Tone.Good) else TextButton(onClick = c.fix) { Text("Fix") }
                    }
                }
            }
        }
        item {
            SectionCard("Porch code", chip = { if (confirmed) Chip("Confirmed", Tone.Good) else Chip("Not scanned yet", Tone.Warn) }) {
                val code = secret
                if (code != null) {
                    val bitmap = remember(code) { qrBitmap(code).asImageBitmap() }
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Image(bitmap, contentDescription = "Your porch QR code", Modifier.fillMaxWidth(0.8f).aspectRatio(1f))
                    }
                }
                Hint("Screenshot or print this, and put it somewhere outside, like the porch. Don't keep a copy by your bed.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { scanning = true }) { Text(if (confirmed) "Test scan" else "Scan printed code") }
                    TextButton(onClick = { confirmReplace = true }) { Text("Replace code") }
                }
            }
        }
    }

    if (scanning) PorchScanDialog(vm, onDone = { scanning = false })
    if (confirmReplace) {
        ConfirmDialog(
            "Replace porch code?",
            "The printed code stops working, and alarms stay off until you print the new one and scan it.",
            "Replace",
            onDismiss = { confirmReplace = false },
        ) { vm.replacePorchCode(); confirmReplace = false }
    }
}

@Composable
private fun TestAlarmControls(vm: SetupViewModel) {
    val at by vm.testAlarmAt.collectAsStateWithLifecycle()
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(at) {
        while (at != null && at != TEST_FAILED) {
            now = LocalDateTime.now()
            delay(1000)
        }
    }
    val secondsLeft = at?.takeIf { it != TEST_FAILED }?.let { Duration.between(now, it).seconds }
    val pending = secondsLeft != null && secondsLeft > 0

    OutlinedButton(onClick = vm::testAlarm, enabled = !pending) {
        Text(if (pending) "Test alarm set" else "Test alarm in 1 minute")
    }
    when {
        at == TEST_FAILED -> Text(
            "Couldn't set the test alarm. Check that Exact alarms is allowed in the checklist below.",
            color = LocalStatusColors.current.warn,
        )
        pending -> Text(
            "Rings at ${at?.format(TimeOfDay)}, in ${secondsLeft}s. Lock your phone now to see the full-screen alarm.",
            color = MaterialTheme.colorScheme.primary,
        )
        else -> Hint("The test rings like the real thing and has a stop button. Lock your phone to see the full-screen version.")
    }
}

private val TimeOfDay: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

@Composable
private fun PorchScanDialog(vm: SetupViewModel, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf("Point the camera at your printed code.") }
    val hasCamera = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    AlertDialog(
        onDismissRequest = onDone,
        confirmButton = { TextButton(onClick = onDone) { Text("Close") } },
        title = { Text("Scan porch code") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (hasCamera) {
                    QrScanner(
                        onCode = { code ->
                            scope.launch {
                                if (vm.isPorchCode(code)) {
                                    vm.confirmPorchCode()
                                    onDone()
                                } else {
                                    message = "That's a different code. Scan the one from this screen."
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f),
                    )
                    Text(message)
                } else {
                    Text("Grant camera permission in the checklist first.")
                }
            }
        },
    )
}

private fun permissionChecks(context: Context, request: (String) -> Unit): List<Check> {
    val pkg = Uri.parse("package:${context.packageName}")
    fun open(action: String, data: Uri? = pkg) = {
        context.startActivity(Intent(action).apply { if (data != null) setData(data) }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    val list = mutableListOf<Check>()
    if (Build.VERSION.SDK_INT >= 33) {
        list += Check("Notifications", "Needed to ring and show the alarm.", granted(Manifest.permission.POST_NOTIFICATIONS)) {
            request(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    if (Build.VERSION.SDK_INT >= 34) {
        val nm = context.getSystemService(NotificationManager::class.java)
        list += Check("Full-screen alarm", "Shows the alarm over the lock screen.", nm.canUseFullScreenIntent(),
            open(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT))
    }
    if (Build.VERSION.SDK_INT >= 31) {
        val am = context.getSystemService(AlarmManager::class.java)
        list += Check("Exact alarms", "Lets the alarm fire at the exact minute.", am.canScheduleExactAlarms(),
            open(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
    }
    list += Check("Camera", "Scans the porch code.", granted(Manifest.permission.CAMERA)) { request(Manifest.permission.CAMERA) }
    val pm = context.getSystemService(PowerManager::class.java)
    list += Check(
        "Battery optimization off", "Keeps the phone from delaying the alarm.",
        pm.isIgnoringBatteryOptimizations(context.packageName),
        open(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS),
    )
    return list
}
