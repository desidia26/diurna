package app.diurna.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.diurna.wallpaper.FingerprintZone
import app.diurna.wallpaper.LockStyle
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun LockScreenCard(vm: SetupViewModel) {
    val lockOn by vm.lockWallpaper.collectAsStateWithLifecycle()
    val saved by vm.lockStyle.collectAsStateWithLifecycle()
    // Follows the sliders while dragging; saved (which redraws the wallpaper) when let go.
    var style by remember(saved) { mutableStateOf(saved) }
    SectionCard("Lock screen", chip = { if (lockOn) Chip(if (saved.isAuto) "Auto" else "Adjusted", Tone.Accent) }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Show today on lock screen", fontWeight = FontWeight.SemiBold)
                Hint("Replaces your lock screen wallpaper with a dark summary of your day. Turning it off goes back to your home screen wallpaper.")
            }
            Switch(checked = lockOn, onCheckedChange = vm::setLockWallpaper)
        }
        if (!lockOn) return@SectionCard

        Hint("The day's lines sit just above the fingerprint sensor and the week's dots below it. If notifications or the clock still cover them on this phone, adjust them here.")
        val raised = (style.raise * 100).roundToInt()
        Text(if (raised == 0) "Position: Auto (just above the sensor)" else "Position: raised $raised%", fontWeight = FontWeight.SemiBold)
        Slider(
            value = style.raise, onValueChange = { style = style.copy(raise = it) },
            valueRange = 0f..LockStyle.MAX_RAISE, steps = 14,
            onValueChangeFinished = { vm.setLockStyle(style) },
        )
        Text("Text size: ${(style.textScale * 100).roundToInt()}%", fontWeight = FontWeight.SemiBold)
        Slider(
            value = style.textScale, onValueChange = { style = style.copy(textScale = it) },
            valueRange = LockStyle.MIN_TEXT..LockStyle.MAX_TEXT, steps = 8,
            onValueChangeFinished = { vm.setLockStyle(style) },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Hint("Auto can't go lower: below this is the sensor.", Modifier.weight(1f))
            TextButton(onClick = { style = LockStyle.AUTO; vm.setLockStyle(LockStyle.AUTO) }, enabled = !style.isAuto) { Text("Reset to Auto") }
        }
        LockPreview(vm, style)
    }
}

/** Today's lock screen drawn by the wallpaper's own code, with the sensor marked where it's assumed to be. */
@Composable
private fun LockPreview(vm: SetupViewModel, style: LockStyle) {
    var preview by remember { mutableStateOf<Pair<Bitmap, FingerprintZone>?>(null) }
    LaunchedEffect(style) {
        delay(80) // a drag fires many values; draw the one it settles on
        preview = vm.lockPreview(style)
    }
    val (bitmap, zone) = preview ?: return
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val outline = MaterialTheme.colorScheme.outline
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            Modifier.fillMaxWidth(0.5f).aspectRatio(bitmap.width.toFloat() / bitmap.height)
                .clip(RoundedCornerShape(16.dp)).border(1.dp, outline, RoundedCornerShape(16.dp)),
        ) {
            Image(image, contentDescription = "Preview of the lock screen", Modifier.matchParentSize())
            Canvas(Modifier.matchParentSize()) {
                val cy = size.height * (zone.top + zone.bottom) / 2
                val r = size.height * (zone.bottom - zone.top) / 2 * 0.7f
                drawCircle(Color(0x33ECECF1), r, Offset(size.width / 2, cy))
                drawCircle(Color(0x66ECECF1), r, Offset(size.width / 2, cy), style = Stroke(1.dp.toPx()))
            }
        }
    }
}
