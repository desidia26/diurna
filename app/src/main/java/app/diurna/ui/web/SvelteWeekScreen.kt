package app.diurna.ui.web

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import app.diurna.container
import app.diurna.ui.WeekScreen
import app.diurna.ui.WeekViewModel
import kotlinx.coroutines.delay
import java.time.LocalDate

@Composable
fun SvelteWeekScreen(vm: WeekViewModel, padding: PaddingValues, onDay: (LocalDate) -> Unit) {
    var failed by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    val week by vm.week.collectAsState()
    val context = LocalContext.current
    val bridge = remember(vm) { WeekBridge({ vm.week.value }, context.container.actions,
        ready = { ready = true }, failed = { failed = true }) }
    LaunchedEffect(Unit) { delay(8000); if (!ready) failed = true }
    if (failed) WeekScreen(vm, padding, onDay)
    else Column(Modifier.fillMaxSize().padding(padding)) {
        Row {
            TextButton(onClick = { vm.shift(-7) }) { Text("Previous") }
            TextButton(onClick = { vm.shift(7) }) { Text("Next") }
            TextButton(onClick = { failed = true }) { Text("Use native Week") }
        }
        key(week?.start) {
            AndroidView(modifier = Modifier.weight(1f).fillMaxWidth(), factory = { ctx ->
                try { createWeekWebView(ctx, bridge) { failed = true }.apply { loadUrl(WeekBridge.URL) } }
                catch (_: Exception) { failed = true; android.view.View(ctx) }
            }, onRelease = { view -> if (view is android.webkit.WebView) { view.stopLoading(); view.destroy() } })
        }
    }
}
