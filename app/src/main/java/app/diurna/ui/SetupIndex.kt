package app.diurna.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.diurna.R
import app.diurna.ui.theme.LocalStatusColors

/** One thing to fix, with the button that fixes it or opens where it's fixed. */
data class AttentionRow(val title: String, val detail: String, val action: String, val onClick: () -> Unit)

/** A row that opens a page, with a one-line status and an optional chip. */
data class LinkRow(
    val title: String,
    val summary: String,
    val chip: Pair<String, Tone>? = null,
    val onClick: () -> Unit,
)

@Composable
fun NeedsAttentionCard(rows: List<AttentionRow>) {
    if (rows.isEmpty()) return
    val s = LocalStatusColors.current
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = s.warnBg,
        border = BorderStroke(1.dp, s.warn.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text("Needs attention", color = s.warn, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
            rows.forEachIndexed { i, r ->
                if (i > 0) HorizontalDivider(color = s.warn.copy(alpha = 0.2f))
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                        Text(r.title, fontWeight = FontWeight.SemiBold)
                        Hint(r.detail)
                    }
                    TextButton(onClick = r.onClick) { Text(r.action) }
                }
            }
        }
    }
}

/** Rows in one card, each opening a page. */
@Composable
fun LinkList(rows: List<LinkRow>) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            rows.forEachIndexed { i, r ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 64.dp)
                        .clickable(role = Role.Button, onClick = r.onClick)
                        .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(r.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        if (r.summary.isNotEmpty()) Hint(r.summary)
                    }
                    r.chip?.let { (label, tone) -> Chip(label, tone) }
                    Icon(
                        painterResource(R.drawable.ic_chevron_right), contentDescription = null,
                        Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** A heading over a group of cards on a Setup page. */
@Composable
fun SetupGroupLabel(text: String) {
    Text(
        text.uppercase(), style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 4.dp),
    )
}

/** "1 to fix" on an index row, or nothing. */
fun toFixChip(count: Int): Pair<String, Tone>? = if (count > 0) "$count to fix" to Tone.Warn else null
