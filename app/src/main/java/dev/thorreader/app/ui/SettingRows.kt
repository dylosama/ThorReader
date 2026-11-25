package dev.thorreader.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.thorreader.app.AnimSpeed
import dev.thorreader.app.PageTheme
import dev.thorreader.app.Settings
import dev.thorreader.app.ThorApp
import dev.thorreader.app.engine.TurnSpeed
import dev.thorreader.app.engine.TurnStyle

/*
 * Setting rows shared by the main Settings screen and the reader menu, so a setting that
 * appears in both has the same name, the same controls and the same relative order.
 */

/** A small grey section title. */
@Composable
fun SectionTitle(title: String) {
    Text(title.uppercase(), color = Color(0xFF888888), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
}

/** "Label ........ [On] [Off]". */
@Composable
fun OnOffRow(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Chip("On", on) { onChange(true) }
        Chip("Off", !on) { onChange(false) }
    }
}

/** "Label" then a row of chips, one of them selected. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChoiceRow(label: String, choices: List<T>, selected: T?, name: (T) -> String, onSelect: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = Color.White, fontSize = 14.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            choices.forEach { c -> Chip(name(c), c == selected) { onSelect(c) } }
        }
    }
}

/**
 * A small segmented "Fast · Normal · Slow" control with the duration in ms under the
 * selected one.
 */
@Composable
fun SpeedSegments(labels: List<String>, ms: List<Int>, selected: Int, enabled: Boolean = true, onSelect: (Int) -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0xFF1E1E1E))
            .border(1.dp, HouseStyle.Hairline, RoundedCornerShape(50))
            .padding(3.dp),
    ) {
        labels.forEachIndexed { i, l ->
            val on = i == selected
            Column(
                Modifier
                    .width(78.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (on && enabled) Accent else Color.Transparent)
                    .focusClick(RoundedCornerShape(50)) { if (enabled) onSelect(i) }
                    .padding(vertical = 5.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val c = when {
                    !enabled -> Color(0xFF666666)
                    on -> OnAccent
                    else -> Color.White
                }
                Text(l, color = c, fontSize = 13.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
                if (on) Text("${ms[i]} ms", color = c.copy(alpha = 0.8f), fontSize = 10.sp)
            }
        }
    }
}

/** "Speed" + segments, indented under its toggle. */
@Composable
fun SpeedRow(labels: List<String>, ms: List<Int>, selected: Int, enabled: Boolean = true, onSelect: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Speed", color = if (enabled) SubtleText else Color(0xFF666666), fontSize = 13.sp, modifier = Modifier.weight(1f))
        SpeedSegments(labels, ms, selected, enabled, onSelect)
    }
}

// ---- the shared settings ----------------------------------------------------------------------

private val settings get() = ThorApp.instance.settings

@Composable
fun GridModeIconsRow() = OnOffRow("Mode icons on grid covers", settings.gridModeIcons) { settings.updateGridModeIcons(it) }

@Composable
fun HeaderRow() = OnOffRow("Header & page number", settings.showHeader) { settings.updateShowHeader(it) }

@Composable
fun HoldToMenuRow() = ChoiceRow(
    "Hold to open the menu", Settings.HOLD_CHOICES_MS, settings.menuHoldMs,
    { ms -> if (ms % 1000 == 0) "${ms / 1000} s" else "${ms / 1000.0} s" },
) { settings.updateMenuHold(it) }

@Composable
fun SelectButtonRow() = OnOffRow("Select button changes mode", settings.selectCyclesMode) { settings.updateSelectCyclesMode(it) }

@Composable
fun SwipeSwapRow() = OnOffRow("Swap screens (comics): swipe up", settings.swipeSwap) { settings.updateSwipeSwap(it) }

@Composable
fun PageTurnStyleRow() = ChoiceRow("Page turn", TurnStyle.entries, settings.turnStyle, { it.label }) { settings.updateTurnStyle(it) }

@Composable
fun PageTurnSpeedRow() = SpeedRow(
    TurnSpeed.entries.map { it.label }, TurnSpeed.entries.map { it.ms }, TurnSpeed.entries.indexOf(settings.turnSpeed),
    enabled = settings.turnStyle != TurnStyle.NONE,
) { settings.updateTurnSpeed(TurnSpeed.entries[it]) }

@Composable
fun ButtonTurnCornerRow() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Button turn corner", color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Chip("Bottom", settings.buttonTurnCornerBottom) { settings.updateButtonTurnCornerBottom(true) }
        Chip("Top", !settings.buttonTurnCornerBottom) { settings.updateButtonTurnCornerBottom(false) }
    }
}

@Composable
fun BookAnimRows() {
    OnOffRow("Open & close book", settings.bookAnim) { settings.updateBookAnim(it) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Style", color = if (settings.bookAnim) SubtleText else Color(0xFF666666), fontSize = 13.sp, modifier = Modifier.weight(1f))
        dev.thorreader.app.BookAnimStyle.entries.forEach { st ->
            Chip(st.label, settings.bookAnimStyle == st) { if (settings.bookAnim) settings.updateBookAnimStyle(st) }
        }
    }
    val hinge = settings.bookAnimStyle == dev.thorreader.app.BookAnimStyle.HINGE
    SpeedRow(
        AnimSpeed.entries.map { it.label }, AnimSpeed.entries.map { if (hinge) it.hingeMs else it.bookMs }, AnimSpeed.entries.indexOf(settings.bookAnimSpeed),
        enabled = settings.bookAnim,
    ) { settings.updateBookAnimSpeed(AnimSpeed.entries[it]) }
    Text(
        if (hinge) "Hinge (Modes 1 and 2): the cover turns sideways, moves to the hinge and opens across both screens. " +
            "Modes 3 and 4 open as Classic (${settings.bookAnimSpeed.bookMs} ms)."
        else "Classic: the cover lifts out and swings open in place.",
        color = SubtleText, fontSize = 12.sp,
    )
}

@Composable
fun SwapAnimRows() {
    OnOffRow("Screen swap (comics)", settings.swapAnim) { settings.updateSwapAnim(it) }
    SpeedRow(
        AnimSpeed.entries.map { it.label }, AnimSpeed.entries.map { it.swapMs }, AnimSpeed.entries.indexOf(settings.swapAnimSpeed),
        enabled = settings.swapAnim,
    ) { settings.updateSwapAnimSpeed(AnimSpeed.entries[it]) }
}

@Composable
fun EdgeShadowRow() = OnOffRow("Edge shadow at first/last page", settings.edgeShadow) { settings.updateEdgeShadow(it) }

@Composable
fun LowBatteryRow() = OnOffRow("Simpler animation on low battery", settings.simplerTurnOnLowBattery) { settings.updateSimplerTurnOnLowBattery(it) }

/** A tappable section header that folds its content ("▸ More" / "▾ More"). */
@Composable
fun FoldHeader(title: String, open: Boolean, onToggle: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(8.dp)).focusClick(RoundedCornerShape(8.dp), onToggle).padding(vertical = 6.dp, horizontal = 2.dp)) {
        Text(
            (if (open) "▾  " else "▸  ") + title.uppercase(),
            color = Color(0xFF888888), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
        )
    }
}
