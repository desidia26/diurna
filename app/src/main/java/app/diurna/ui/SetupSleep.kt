package app.diurna.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.diurna.data.SettingsStore
import app.diurna.data.SleepPeriodEntity
import app.diurna.data.health.HealthConnect
import app.diurna.data.health.sleepPermissions
import app.diurna.domain.HealthInventory
import app.diurna.domain.HealthInventory.Kind
import app.diurna.domain.SleepRules
import app.diurna.domain.SleepView
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val ReadAt: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE HH:mm")

/** Setup's one-line status for the Sleep row. */
fun sleepSummary(on: Boolean, origin: String?, status: SettingsStore.SleepStatus?): String = when {
    !on -> "Off"
    status?.problem != null -> "On · " + status.problem
    else -> "On" + (origin?.let { " · " + HealthInventory.sourceName(it) } ?: "") +
        (status?.let { " · read " + ReadAt.format(Instant.ofEpochMilli(it.at).atZone(ZoneId.systemDefault())) } ?: "")
}

@Composable
fun SleepCard(vm: SetupViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val on by vm.sleepOn.collectAsStateWithLifecycle()
    val chosen by vm.sleepOrigin.collectAsStateWithLifecycle()
    val status by vm.sleepStatus.collectAsStateWithLifecycle()
    val origins by vm.sleepOrigins.collectAsStateWithLifecycle()
    val recent by vm.recentSleep.collectAsStateWithLifecycle()
    val reading by vm.sleepReading.collectAsStateWithLifecycle()

    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    val availability = remember(refresh) { HealthConnect.availability(context) }
    var granted by remember { mutableStateOf<Set<String>>(emptySet()) }
    var backgroundSupported by remember { mutableStateOf(false) }
    var refused by remember { mutableStateOf(false) }
    LaunchedEffect(availability, refresh) {
        if (availability != HealthConnect.Availability.AVAILABLE) return@LaunchedEffect
        runCatching {
            val client = HealthConnectClient.getOrCreate(context)
            granted = client.permissionController.getGrantedPermissions()
            backgroundSupported = client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND) ==
                HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
        }
    }
    val sleepAllowed = HealthConnect.permission(Kind.SLEEP) in granted
    val ask = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { result ->
        granted = result
        val ok = HealthConnect.permission(Kind.SLEEP) in result
        refused = !ok
        if (ok && !on) vm.setSleep(true)
    }
    fun askPermissions() = scope.launch { runCatching { ask.launch(sleepPermissions(context)) } }

    SectionCard("Sleep") {
        when (availability) {
            HealthConnect.Availability.MISSING, HealthConnect.Availability.NEEDS_UPDATE -> {
                Text("Sleep is read from Health Connect, which isn't on this phone or needs an update from the Play Store.")
                return@SectionCard
            }
            HealthConnect.Availability.AVAILABLE -> Unit
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Read sleep from Health Connect", fontWeight = FontWeight.SemiBold)
                Hint(statusLine(on, status))
            }
            Switch(
                checked = on,
                onCheckedChange = { want -> if (!want) vm.setSleep(false) else if (sleepAllowed) vm.setSleep(true) else askPermissions() },
                modifier = Modifier.semantics { contentDescription = "Read sleep from Health Connect" },
            )
        }
        if (refused) Text("Sleep needs read access to sleep in Health Connect. Tap the switch to ask again.", color = MaterialTheme.colorScheme.error)
        if (!on) {
            Hint("Shows last night next to your alarm, porch scan and workouts. Oura, and most rings and watches, write sleep to Health Connect.")
            return@SectionCard
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        val picked = SleepRules.pickOrigin(chosen, origins)
        SourcePicker(chosen, picked, origins.map { it.first }, vm::setSleepOrigin)
        LastNight(recent.filter { it.origin == picked && it.type == SleepRules.Type.MAIN.key }.maxByOrNull { it.date })
        if (origins.isEmpty() && status != null && status?.problem == null) {
            Hint("No sleep found in the last 30 days. In your ring or watch app, turn on Health Connect, then open that app after waking. The night you connect may never arrive; the next one will.")
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Text("Access", fontWeight = FontWeight.SemiBold)
        PermissionRow("Sleep", HealthConnect.permission(Kind.SLEEP) in granted, needed = true)
        PermissionRow("Heart rate", HealthConnect.permission(Kind.HEART_RATE) in granted)
        PermissionRow("Heart rate variability", HealthConnect.permission(Kind.HRV) in granted)
        PermissionRow("Breathing rate and SpO2", HealthConnect.permission(Kind.BREATHING) in granted && HealthConnect.permission(Kind.SPO2) in granted)
        if (backgroundSupported) {
            PermissionRow("Read in the background", HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND in granted)
        } else {
            Hint("This phone's Health Connect can't read in the background, so sleep is read when you open Diurna.")
        }
        val missing = missingAccess(granted, backgroundSupported)
        if (missing) OutlinedButton(onClick = { askPermissions() }) { Text("Allow more") }
        Hint("Oura doesn't share breathing rate or SpO2 with Health Connect; they're read when another device writes them.")

        Button(onClick = vm::readSleepNow, enabled = !reading && sleepAllowed) { Text(if (reading) "Reading…" else "Read now") }
        Hint("Read on this phone only and kept with your other Diurna data and backups. Nothing is uploaded.")
    }
}

private fun missingAccess(granted: Set<String>, background: Boolean): Boolean =
    !granted.containsAll(HealthConnect.readPermissions) ||
        (background && HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND !in granted)

private fun statusLine(on: Boolean, status: SettingsStore.SleepStatus?): String = when {
    !on -> "Off by default."
    status == null -> "Not read yet."
    else -> {
        val at = ReadAt.format(Instant.ofEpochMilli(status.at).atZone(ZoneId.systemDefault()))
        if (status.problem != null) "Last tried $at: ${status.problem}." else "Last read $at."
    }
}

@Composable
private fun SourcePicker(chosen: String?, picked: String?, seen: List<String>, onPick: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text("Source", fontWeight = FontWeight.SemiBold)
            Hint(
                (picked?.let { HealthInventory.sourceName(it) } ?: "None yet") +
                    if (chosen == null) " (automatic)" else "",
            )
        }
        Box {
            OutlinedButton(onClick = { open = true }, enabled = seen.isNotEmpty() || chosen != null) { Text("Change") }
            DropdownMenu(open, onDismissRequest = { open = false }) {
                DropdownMenuItem(text = { Text("Automatic") }, onClick = { onPick(null); open = false })
                (seen + listOfNotNull(chosen)).distinct().forEach { o ->
                    DropdownMenuItem(text = { Text(HealthInventory.sourceName(o)) }, onClick = { onPick(o); open = false })
                }
            }
        }
    }
    Hint("Only sleep from this app is used, so two devices never count a night twice.")
}

@Composable
private fun LastNight(n: SleepPeriodEntity?) {
    if (n == null) return
    val asleep = SleepView.asleep(n)
    val times = n.bedStart.takeLast(5) + " → " + (n.wokeAt?.let { "woke " + it.takeLast(5) + " · up " } ?: "") + n.bedEnd.takeLast(5)
    Column {
        Text("Night to ${n.date}: $asleep", fontWeight = FontWeight.SemiBold)
        Hint(times)
    }
}

@Composable
private fun PermissionRow(label: String, ok: Boolean, needed: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        when {
            ok -> Chip("Allowed", Tone.Good)
            needed -> Chip("Needed", Tone.Warn)
            else -> Chip("Off")
        }
    }
}
