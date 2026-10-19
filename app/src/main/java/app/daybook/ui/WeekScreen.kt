package app.daybook.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.daybook.domain.AlarmRules
import app.daybook.ui.theme.LocalStatusColors
import java.time.LocalDate

@Composable
fun WeekScreen(vm: WeekViewModel, contentPadding: PaddingValues, onOpenDay: (LocalDate) -> Unit) {
    val ui by vm.week.collectAsStateWithLifecycle()
    val w = ui ?: return
    LazyColumn(
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp, top = contentPadding.calculateTopPadding() + 16.dp,
            bottom = contentPadding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            DateHeader("Week of", "${w.start.short()} – ${w.start.plusDays(6).short()}", onPrev = { vm.shift(-7) }, onNext = { vm.shift(7) })
        }
        item {
            Hint(
                "${w.onTime}/${w.scoredCount} on-time mornings so far. Days with the alarm off aren't counted.",
                Modifier.fillMaxWidth().padding(bottom = 4.dp),
            )
        }
        items(w.days, key = { it.date.toString() }) { d -> WeekRow(d, today = w.today) { onOpenDay(d.date) } }
    }
}

@Composable
private fun WeekRow(d: WeekDayUi, today: LocalDate, onClick: () -> Unit) {
    val isToday = d.date == today
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.width(44.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(d.date.format(WeekdayShort).uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${d.date.dayOfMonth}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            }
            FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (d.scannedAt != null && d.morning.status != AlarmRules.Morning.OFF) Chip(d.scannedAt)
                MorningChip(d.morning)
                when {
                    d.workoutName != null -> Chip("✓ ${d.workoutName}", Tone.Sky)
                    d.plannedName != null -> Chip(d.plannedName, if (d.date < today) Tone.Warn else Tone.Neutral) // planned but not logged
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                d.steps?.let {
                    Text(
                        if (it >= 1000) "%.1fk".format(it / 1000.0) else "$it",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (it >= d.stepGoal) LocalStatusColors.current.good else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                d.kcal?.let { Hint("%,d kcal".format(it)) }
            }
        }
    }
}
