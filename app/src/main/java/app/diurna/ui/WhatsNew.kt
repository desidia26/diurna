package app.diurna.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.diurna.AppContainer
import app.diurna.domain.Changelog
import java.time.LocalDate
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class WhatsNewViewModel(private val c: AppContainer, freshInstall: Boolean) : ScreenViewModel() {
    init {
        io {
            if (c.settings.changelogSeen.first() == null) {
                val number = c.settings.changelogSeenNumber()
                c.settings.setChangelogSeen(
                    if (number != null) Changelog.seenFromNumber(number) else Changelog.initialSeen(c.changelog, freshInstall),
                )
            }
        }
    }

    /** Entries not shown yet, newest first; empty until a starting point is recorded. */
    val unseen: StateFlow<List<Changelog.Entry>> =
        c.settings.changelogSeen.map { seen -> seen?.let { Changelog.unseen(c.changelog, it) }.orEmpty() }.state(emptyList())

    fun dismiss() = io {
        val seen = c.settings.changelogSeen.first().orEmpty()
        c.settings.setChangelogSeen(seen + c.changelog.map { it.id })
    }
}

/** Shown on a launcher open after an update; tapping outside counts as read, like "Got it". */
@Composable
fun WhatsNewDialog(vm: WhatsNewViewModel, onClose: () -> Unit) {
    val unseen by vm.unseen.collectAsStateWithLifecycle()
    if (unseen.isEmpty()) return
    val close = { vm.dismiss(); onClose() }
    AlertDialog(
        onDismissRequest = close,
        confirmButton = { TextButton(onClick = close) { Text("Got it") } },
        title = { Text("What's new") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                unseen.forEach { ChangelogEntry(it) }
            }
        },
    )
}

@Composable
fun ChangelogEntry(e: Changelog.Entry) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(e.title, fontWeight = FontWeight.SemiBold)
        Text(e.body, style = MaterialTheme.typography.bodyMedium)
        Hint(LocalDate.parse(e.date).short())
    }
}

/** Entries shown in Setup before "All changes". */
private const val RECENT_CHANGES = 3

@Composable
fun WhatsNewCard(entries: List<Changelog.Entry>, onAll: () -> Unit) {
    if (entries.isEmpty()) return
    SectionCard("What's new") {
        entries.take(RECENT_CHANGES).forEach { ChangelogEntry(it) }
        if (entries.size > RECENT_CHANGES) TextButton(onClick = onAll) { Text("All changes") }
    }
}

@Composable
fun ChangesPage(entries: List<Changelog.Entry>, onBack: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { PageHeader("What's new", onBack) }
        items(entries, key = { it.id }) { ChangelogEntry(it) }
    }
}
