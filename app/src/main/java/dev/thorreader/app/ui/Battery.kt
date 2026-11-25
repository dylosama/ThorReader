package dev.thorreader.app.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Battery level 0..100 and whether it's charging. */
data class BatteryState(val percent: Int, val charging: Boolean) {
    /** Below 20% and not charging: shown in the warning colour. */
    val low: Boolean get() = percent < LOW_PERCENT && !charging

    companion object {
        const val LOW_PERCENT = 20

        fun from(i: Intent?): BatteryState? {
            i ?: return null
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level < 0 || scale <= 0) return null
            val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL ||
                i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            return BatteryState((100 * level / scale).coerceIn(0, 100), charging)
        }
    }
}

/**
 * The battery level, read only while this is on screen (it lives in the reader menu): the
 * sticky ACTION_BATTERY_CHANGED intent gives the current value at once, and the receiver
 * hears changes until the menu closes. No polling, nothing registered while reading.
 */
@Composable
fun rememberBattery(): BatteryState? {
    val context = LocalContext.current.applicationContext
    var state by remember { mutableStateOf<BatteryState?>(null) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                BatteryState.from(i)?.let { state = it }
            }
        }
        // System broadcast; registering returns the sticky (current) value straight away.
        val sticky = context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_NOT_EXPORTED)
        BatteryState.from(sticky)?.let { state = it }
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    return state
}

/**
 * "▭ 84%": an outline battery whose fill shows the charge (a lightning bolt while charging),
 * then the percentage, a little smaller and lighter than the clock. Muted colour; the warning
 * colour below 20% when not charging.
 */
@Composable
fun BatteryIndicator(b: BatteryState, modifier: Modifier = Modifier) {
    val color = if (b.low) HouseStyle.Warning else SubtleText
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.size(30.dp, 16.dp)) {
            val stroke = 1.6.dp.toPx()
            val nubW = 2.5.dp.toPx()
            val bodyW = size.width - nubW - stroke / 2
            val h = size.height - stroke
            val r = CornerRadius(3.dp.toPx())
            // Outline body + the little nub on the right.
            drawRoundRect(color, Offset(stroke / 2, stroke / 2), Size(bodyW - stroke / 2, h), r, style = Stroke(stroke))
            drawRoundRect(color, Offset(bodyW + stroke / 2, size.height * 0.3f), Size(nubW, size.height * 0.4f), CornerRadius(1.dp.toPx()))
            // Fill: the charge level.
            val pad = stroke * 1.6f
            val fillMax = bodyW - stroke / 2 - 2 * pad + stroke / 2
            val fillW = (fillMax * b.percent / 100f).coerceAtLeast(if (b.percent > 0) stroke else 0f)
            drawRoundRect(color.copy(alpha = 0.75f), Offset(stroke / 2 + pad, stroke / 2 + pad), Size(fillW, h - 2 * pad), CornerRadius(1.5.dp.toPx()))
            if (b.charging) {
                // A small lightning bolt in the middle, dark-edged so it shows over the fill.
                val cx = (stroke / 2 + bodyW) / 2
                val cy = size.height / 2
                val u = size.height * 0.36f
                val bolt = Path().apply {
                    moveTo(cx + u * 0.15f, cy - u)
                    lineTo(cx - u * 0.55f, cy + u * 0.15f)
                    lineTo(cx - u * 0.05f, cy + u * 0.15f)
                    lineTo(cx - u * 0.2f, cy + u)
                    lineTo(cx + u * 0.55f, cy - u * 0.15f)
                    lineTo(cx + u * 0.05f, cy - u * 0.15f)
                    close()
                }
                drawPath(bolt, Color(0xFF141414), style = Stroke(stroke * 1.4f))
                drawPath(bolt, Accent)
            }
        }
        Text("${b.percent}%", color = color, fontSize = 17.sp, fontWeight = FontWeight.Light)
    }
}
