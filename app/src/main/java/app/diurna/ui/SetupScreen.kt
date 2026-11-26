package app.diurna.ui

import app.diurna.container

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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import app.diurna.domain.ArmedState
import app.diurna.domain.SetupAttention
import app.diurna.domain.SetupAttention.Group
import app.diurna.scan.QrScanner
import app.diurna.scan.qrBitmap
import app.diurna.ui.theme.LocalStatusColors
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private data class Check(val label: String, val detail: String, val ok: Boolean, val fix: () -> Unit)

/** Group pages by key, with the name their pages' back buttons show. */
private val GroupTitles = mapOf(
    "alarm" to "Alarm", "food" to "Food", "training" to "Training", "checkin" to "Check-in & steps", "data" to "Data",
)

/** Every page key [SetupScreen] opens; a test checks each makes a valid debug event name. */
internal val SetupPages = listOf(
    "alarm", "food", "food/meals", "food/targets", "food/estimates", "food/usda", "food/backfill",
    "training", "training/exercises", "checkin", "checkin/metrics", "data", "data/changes", "data/health",
)

/** Event names allow no "/", so "food/targets" logs as setup.page.food.targets. */
internal fun setupPageEvent(page: String?): app.diurna.debug.Action.Event {
    val inLibrary = page?.endsWith("/meals") == true || page?.endsWith("/exercises") == true
    return app.diurna.debug.Action.Event(
        if (inLibrary) app.diurna.debug.Screen.LIBRARY else app.diurna.debug.Screen.SETUP,
        "setup.page." + (page?.replace('/', '.') ?: "main"),
    )
}

private val BackupDay: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d")

/**
 * Setup: an index of groups, each its own page, and some of those with pages of their own
 * ("food/targets"). Back goes up one level.
 */
@Composable
fun SetupScreen(vm: SetupViewModel, library: LibraryViewModel, contentPadding: PaddingValues) {
    var page by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    LaunchedEffect(page) { context.container.actions.dispatch(setupPageEvent(page)) }
    val parent = page?.substringBeforeLast('/', "")?.ifEmpty { null }
    val up = { page = parent }
    val open = { p: String -> page = p }
    BackHandler(enabled = page != null, onBack = up)

    // Checked here, not on a page, so the index and the Alarm page agree and both refresh on resume.
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++; vm.refreshAlarm() }
    val requestPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    val checks = remember(refresh) { permissionChecks(context) { requestPermission.launch(it) } }

    CompositionLocalProvider(LocalBackLabel provides (parent?.let { GroupTitles[it] } ?: "Setup")) {
        when (page) {
            "alarm" -> AlarmPage(vm, checks, contentPadding, up)
            "food" -> FoodPage(vm, library, contentPadding, open, up)
            "food/meals" -> MealsPage(library, up)
            "food/targets" -> CardPage(contentPadding, up) { TargetsCard(vm) }
            "food/estimates" -> CardPage(contentPadding, up) { FoodEstimatesCard(vm) }
            "food/usda" -> CardPage(contentPadding, up) { UsdaFdcCard(vm) }
            "food/backfill" -> CardPage(contentPadding, up) { DetailBackfillCard(vm) }
            "training" -> TrainingPage(library, contentPadding, open, up)
            "training/exercises" -> ExercisesPage(library, up)
            "checkin" -> GroupPage("Check-in & steps", contentPadding, up) {
                item { CheckInSetupCard(vm) { open("checkin/metrics") } }
                item { StepsCard(vm) }
            }
            "checkin/metrics" -> DailyMetricsPage(vm, up)
            "data" -> GroupPage("Data", contentPadding, up) {
                item { BackupsCard(vm) }
                item { LockScreenCard(vm) }
                item { LinkList(listOf(LinkRow("Health Connect", "What your ring or watch writes there") { open("data/health") })) }
                item { WhatsNewCard(vm.changelog, onAll = { open("data/changes") }) }
                item { app.diurna.debug.DebugCard(context.container.debug) }
            }
            "data/changes" -> ChangesPage(vm.changelog, up)
            "data/health" -> CardPage(contentPadding, up) { HealthConnectCard() }
            else -> SetupMain(vm, library, checks, contentPadding, open)
        }
    }
}

@Composable
private fun SetupList(contentPadding: PaddingValues, content: LazyListScope.() -> Unit) {
    LazyColumn(
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp, top = contentPadding.calculateTopPadding() + 16.dp,
            bottom = contentPadding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
private fun GroupPage(title: String, contentPadding: PaddingValues, onBack: () -> Unit, content: LazyListScope.() -> Unit) {
    SetupList(contentPadding) {
        item { PageHeader(title, onBack) }
        content()
    }
}

/** A page holding one card, whose own title heads the page. */
@Composable
private fun CardPage(contentPadding: PaddingValues, onBack: () -> Unit, card: @Composable () -> Unit) {
    SetupList(contentPadding) {
        item { PageHeader(null, onBack) }
        item { card() }
    }
}

@Composable
private fun SetupMain(vm: SetupViewModel, library: LibraryViewModel, checks: List<Check>, contentPadding: PaddingValues, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val confirmed by vm.porchConfirmedKnown.collectAsStateWithLifecycle()
    val armed by vm.armed.collectAsStateWithLifecycle()
    val lastBackup by vm.lastBackup.collectAsStateWithLifecycle()
    val targets by vm.todayTargets.collectAsStateWithLifecycle()
    val meals by library.meals.collectAsStateWithLifecycle()
    val catalog by library.catalog.collectAsStateWithLifecycle()
    val cardio by library.cardio.collectAsStateWithLifecycle()
    val provider by vm.aiProvider.collectAsStateWithLifecycle()
    val gateway by vm.gateway.collectAsStateWithLifecycle()
    val aiLastFour by remember(provider) { vm.apiKeyLastFour(provider) }.collectAsStateWithLifecycle()
    val metrics by vm.dailyMetrics.collectAsStateWithLifecycle()
    val stepGoal by vm.stepGoal.collectAsStateWithLifecycle()
    val lockOn by vm.lockWallpaper.collectAsStateWithLifecycle()

    val attention = SetupAttention.items(
        porchConfirmed = confirmed ?: true,
        missingPermissions = checks.filterNot { it.ok }.map { it.label },
        lastBackup = lastBackup,
        now = LocalDateTime.now(),
    )
    val attentionRows = attention.map { item ->
        when (item) {
            SetupAttention.Item.PorchCode ->
                AttentionRow("Scan your porch code", "Alarms stay off until you do.", "Open") { onOpen("alarm") }
            is SetupAttention.Item.Permission -> {
                val c = checks.first { it.label == item.label }
                AttentionRow(c.label, c.detail, "Fix", c.fix)
            }
            is SetupAttention.Item.StaleBackup ->
                AttentionRow("No backup since ${item.last.format(BackupDay)}", "Daily backups have stopped. Try one now.", "Back up", vm::backupNow)
        }
    }
    fun fix(group: Group) = toFixChip(SetupAttention.count(attention, group))

    val alarmSummary = when (val a = armed) {
        is ArmedState.Armed -> "Armed · next ${a.next.date.format(WeekdayShort)} ${a.next.time}"
        ArmedState.NoneUpcoming -> "No alarm in the next week"
        ArmedState.NeedsPorchCode -> "Not armed: porch code not scanned"
        ArmedState.NotAllowed -> "Not armed: exact alarms not allowed"
    }
    val (aiLabel, _) = aiStatus(provider, gateway.openAiBaseUrl, gateway.openAiModel, aiLastFour)
    val foodSummary = listOfNotNull(
        targets?.let { "%,d kcal".format(it.kcal) },
        plural(meals.size, "meal"),
        "${provider.label}: ${aiLabel.lowercase()}",
    ).joinToString(" · ")
    val active = metrics.count { !it.def.archived }

    SetupList(contentPadding) {
        item { Text("Setup", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
        if (attentionRows.isNotEmpty()) item { NeedsAttentionCard(attentionRows) }
        item {
            LinkList(listOf(
                LinkRow("Alarm", alarmSummary, fix(Group.ALARM)) { onOpen("alarm") },
                LinkRow("Food", foodSummary, fix(Group.FOOD)) { onOpen("food") },
                LinkRow("Training", plural(catalog.size, "exercise") + " · cardio " + if (cardio) "on" else "off", fix(Group.TRAINING)) { onOpen("training") },
                LinkRow("Check-in & steps", plural(active, "metric") + " · goal %,d steps".format(stepGoal), fix(Group.CHECK_IN)) { onOpen("checkin") },
                LinkRow(
                    "Data",
                    (lastBackup?.let { "Last backup ${it.format(BackupDay)}" } ?: "No backup yet") + " · lock screen " + if (lockOn) "on" else "off",
                    fix(Group.DATA),
                ) { onOpen("data") },
            ))
        }
    }
}

private fun plural(n: Int, word: String) = "$n $word" + if (n == 1) "" else "s"

@Composable
private fun AlarmPage(vm: SetupViewModel, checks: List<Check>, contentPadding: PaddingValues, onBack: () -> Unit) {
    val secret by vm.porchSecret.collectAsStateWithLifecycle()
    val confirmed by vm.porchConfirmed.collectAsStateWithLifecycle()
    val armed by vm.armed.collectAsStateWithLifecycle()
    var scanning by remember { mutableStateOf(false) }
    var confirmReplace by remember { mutableStateOf(false) }
    var showCode by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current

    LaunchedEffect(scanning, confirmReplace) {
        context.container.actions.dispatch(app.diurna.debug.Action.Event(app.diurna.debug.Screen.SETUP,
            if (scanning) "setup.dialog.porch_scan" else if (confirmReplace) "setup.dialog.replace_code" else "setup.dialog.closed"))
    }

    GroupPage("Alarm", contentPadding, onBack) {
        item {
            SectionCard("Status") {
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
            val missing = checks.count { !it.ok }
            SectionCard("Permissions", chip = { toFixChip(missing)?.let { (l, t) -> Chip(l, t) } }) {
                // What still needs fixing goes first, so it isn't below four "Done" rows.
                checks.sortedBy { it.ok }.forEach { c ->
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
                // Once scanned, the big code only gets in the way; it's still a tap away for reprinting.
                if (code != null && (!confirmed || showCode)) {
                    val bitmap = remember(code) { qrBitmap(code).asImageBitmap() }
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Image(bitmap, contentDescription = "Your porch QR code", Modifier.fillMaxWidth(0.8f).aspectRatio(1f))
                    }
                    Hint("Screenshot or print this, and put it somewhere outside, like the porch. Don't keep a copy by your bed.")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { scanning = true }) { Text(if (confirmed) "Test scan" else "Scan printed code") }
                    if (confirmed) TextButton(onClick = { showCode = !showCode }) { Text(if (showCode) "Hide code" else "Show code") }
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
private fun FoodPage(vm: SetupViewModel, library: LibraryViewModel, contentPadding: PaddingValues, onOpen: (String) -> Unit, onBack: () -> Unit) {
    val meals by library.meals.collectAsStateWithLifecycle()
    val detailed by library.detailedNutrition.collectAsStateWithLifecycle()
    val dvBasis by library.dvBasis.collectAsStateWithLifecycle()
    val hasProfile by library.hasProfile.collectAsStateWithLifecycle()
    val targets by vm.todayTargets.collectAsStateWithLifecycle()
    val provider by vm.aiProvider.collectAsStateWithLifecycle()
    val gateway by vm.gateway.collectAsStateWithLifecycle()
    val aiLastFour by remember(provider) { vm.apiKeyLastFour(provider) }.collectAsStateWithLifecycle()
    val usdaLastFour by vm.usdaKeyLastFour.collectAsStateWithLifecycle()

    GroupPage("Food", contentPadding, onBack) {
        item { SetupGroupLabel("What you eat") }
        item {
            LinkList(listOf(
                LinkRow("Meals", plural(meals.size, "saved meal") + " · logged exactly, no AI call") { onOpen("food/meals") },
                LinkRow(
                    "Targets",
                    targets?.let { "%,d kcal · %d g protein".format(it.kcal, it.proteinG) } ?: "Not set up",
                ) { onOpen("food/targets") },
            ))
        }
        item {
            DetailedNutritionCard(
                detailed, onDetailedNutrition = library::setDetailedNutrition,
                dvBasis = dvBasis, hasProfile = hasProfile, onDvBasis = library::setDvBasis,
                onBackfill = { onOpen("food/backfill") },
            )
        }
        item { SetupGroupLabel("Sources") }
        item {
            LinkList(listOf(
                LinkRow("Food estimates", provider.label, aiStatus(provider, gateway.openAiBaseUrl, gateway.openAiModel, aiLastFour)) {
                    onOpen("food/estimates")
                },
                LinkRow(
                    "USDA FoodData Central", "Optional, for amino acids",
                    if (usdaLastFour != null) "Key set" to Tone.Good else "No key" to Tone.Neutral,
                ) { onOpen("food/usda") },
            ))
        }
    }
}

@Composable
private fun TrainingPage(library: LibraryViewModel, contentPadding: PaddingValues, onOpen: (String) -> Unit, onBack: () -> Unit) {
    val catalog by library.catalog.collectAsStateWithLifecycle()
    val cardio by library.cardio.collectAsStateWithLifecycle()
    GroupPage("Training", contentPadding, onBack) {
        item {
            LinkList(listOf(
                LinkRow("Exercises", plural(catalog.size, "exercise") + " · what workouts and suggestions draw from") { onOpen("training/exercises") },
            ))
        }
        item { CardioCard(cardio, onCardio = library::setCardio) }
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
