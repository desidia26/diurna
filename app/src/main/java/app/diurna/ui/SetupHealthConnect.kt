package app.diurna.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.diurna.data.health.FeatureState
import app.diurna.data.health.HealthConnect
import app.diurna.data.health.HealthInventoryReader
import app.diurna.data.health.HealthSnapshot
import app.diurna.domain.HealthInventory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val Newest: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE HH:mm")

/**
 * Setup → Data → Health Connect: what's there for sleep, read only when asked and never stored.
 * It's how to check what a ring or watch writes before sleep import relies on it.
 */
@Composable
fun HealthConnectCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    val availability = remember(refresh) { HealthConnect.availability(context) }
    val reader = remember { HealthInventoryReader(context) }
    var granted by remember { mutableStateOf<Set<String>?>(null) }
    var snapshot by remember { mutableStateOf<HealthSnapshot?>(null) }
    var reading by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(availability, refresh) {
        granted = if (availability == HealthConnect.Availability.AVAILABLE) runCatching { reader.granted() }.getOrNull() else null
    }
    val ask = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted = it }

    SectionCard("Health Connect") {
        Text("See which apps write sleep, heart rate and HRV to Health Connect, before sleep import uses them.")
        when (availability) {
            HealthConnect.Availability.MISSING -> {
                Text("Health Connect isn't on this phone.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@SectionCard
            }
            HealthConnect.Availability.NEEDS_UPDATE -> {
                Text("Install or update Health Connect from the Play Store first.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@SectionCard
            }
            HealthConnect.Availability.AVAILABLE -> Unit
        }
        val allowed = granted?.intersect(HealthConnect.readPermissions).orEmpty()
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Reading allowed", fontWeight = FontWeight.SemiBold)
                Hint("${allowed.size} of ${HealthConnect.readPermissions.size} types. Only sleep is needed; the rest add detail.")
            }
            if (allowed.size < HealthConnect.readPermissions.size) {
                OutlinedButton(onClick = { ask.launch(HealthConnect.readPermissions) }) { Text("Allow") }
            } else Chip("All", Tone.Good)
        }
        Button(
            enabled = !reading && allowed.isNotEmpty(),
            onClick = {
                reading = true; problem = null; copied = false
                scope.launch {
                    try { snapshot = reader.read() }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { problem = "Couldn't read Health Connect. Open it once, then try again." }
                    finally { reading = false }
                }
            },
        ) { Text(if (reading) "Reading…" else if (snapshot == null) "Read last 7 days" else "Read again") }
        problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        snapshot?.let { s -> SnapshotList(s) }
        snapshot?.let { s ->
            OutlinedButton(onClick = { copied = copy(context, s) }) { Text(if (copied) "Copied" else "Copy as text") }
        }
        Hint("Read on this phone only, when you tap Read. Nothing is kept or uploaded. The list has counts and times, no readings.")
    }
}

@Composable
private fun SnapshotList(s: HealthSnapshot) {
    val zone = ZoneId.systemDefault()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (s.rows.isEmpty()) Text("Nothing in the last 7 days.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        s.rows.forEachIndexed { i, r ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(r.kind.label, fontWeight = FontWeight.SemiBold)
                    Hint(HealthInventory.sourceName(r.origin) + HealthInventory.detail(r).let { if (it.isEmpty()) "" else " · $it" })
                }
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.widthIn(min = 64.dp)) {
                    Text(HealthInventory.countLabel(r), fontWeight = FontWeight.SemiBold)
                    Hint(Newest.format(r.newest.atZone(zone)))
                }
            }
        }
        if (s.empty.isNotEmpty()) Hint("None: " + s.empty.joinToString(", ") { it.label })
        if (s.noPermission.isNotEmpty()) Hint("Not allowed: " + s.noPermission.joinToString(", ") { it.label })
        if (s.failed.isNotEmpty()) Hint("Couldn't read: " + s.failed.joinToString(", ") { it.label })
        Hint(
            "Background reads: " + feature(s.backgroundReads) + " · Older than 30 days: " + feature(s.history),
        )
    }
}

private fun feature(f: FeatureState) = if (f == FeatureState.AVAILABLE) "supported" else "not supported"

private fun copy(context: Context, s: HealthSnapshot): Boolean = runCatching {
    val text = HealthInventory.text(s.rows, s.empty, s.noPermission, ZoneId.systemDefault(), s.failed) +
        "\nBackground reads: ${feature(s.backgroundReads)}\nOlder than 30 days: ${feature(s.history)}"
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Health Connect", text))
}.isSuccess
