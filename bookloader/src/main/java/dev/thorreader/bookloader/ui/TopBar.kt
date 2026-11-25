package dev.thorreader.bookloader.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dev.thorreader.bookloader.BookloaderApp
import dev.thorreader.bookloader.LocalHost
import dev.thorreader.bookloader.Screen
import dev.thorreader.bookloader.work.Jobs

/**
 * Thor Reader's top bar, for every Bookloader screen: a status badge on the left (connection,
 * downloads), the screen's own centre, and on the right the same pill as Thor Reader's library -
 * Queue | + | gear - so the gear is always in the same place.
 */
@Composable
fun TopBar(center: @Composable () -> Unit) {
    val app = BookloaderApp.instance
    val ctx = LocalContext.current
    val flow = remember { WorkManager.getInstance(ctx).getWorkInfosByTagFlow(Jobs.TAG_JOB) }
    val jobs by flow.collectAsStateSafe()
    val running = jobs.count { it.state == WorkInfo.State.RUNNING }
    val waiting = jobs.count { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
    Box(Modifier.fillMaxWidth().height(48.dp)) {
        Column(Modifier.align(Alignment.CenterStart).widthIn(max = 230.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            app.net.badge?.let { HouseBadge("Protected: $it", dot = House.Ok) }
            if (running + waiting > 0) {
                HouseBadge(
                    (if (running > 0) "Downloading" else "Waiting") + (if (waiting > 0) " · $waiting queued" else ""),
                    dot = House.Accent,
                )
            }
        }
        Box(Modifier.align(Alignment.Center)) { center() }
        Box(Modifier.align(Alignment.CenterEnd)) { Pill(running + waiting) }
    }
}

@Composable
private fun Pill(active: Int) {
    val host = LocalHost.current
    Row(
        Modifier
            .clip(House.Pill)
            .background(House.Round)
            .border(1.dp, House.Hairline, House.Pill),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PillButton({ host.go(Screen.Queue) }) {
            Box {
                QueueIcon()
                if (active > 0) {
                    Text(
                        active.toString(),
                        color = House.OnAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.TopEnd).offset(x = 9.dp, y = (-6).dp)
                            .clip(CircleShape).background(House.Accent).padding(horizontal = 5.dp),
                    )
                }
            }
        }
        PillDivider()
        PillButton({ host.openSettings(addFeed = true) }) { Text("+", color = Color.White, fontSize = 24.sp) }
        PillDivider()
        PillButton({ host.openSettings(addFeed = false) }) { Text("⚙", color = Color.White, fontSize = 20.sp) }
    }
}

@Composable
private fun PillDivider() = Box(Modifier.width(1.dp).height(26.dp).background(House.Hairline))

/** Thor Reader's pill button: 52 × 48 dp, the accent showing while pressed. */
@Composable
private fun PillButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Box(
        Modifier
            .size(52.dp, 48.dp)
            .background(if (pressed) House.Accent.copy(alpha = 0.35f) else Color.Transparent)
            .thorClick(House.Pill, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Arrow into a tray: "downloads". */
@Composable
fun QueueIcon(color: Color = Color.White) {
    Canvas(Modifier.size(20.dp)) {
        val w = size.width
        val s = w * 0.1f
        drawLine(color, Offset(w / 2, w * 0.08f), Offset(w / 2, w * 0.62f), s, StrokeCap.Round)
        drawLine(color, Offset(w * 0.28f, w * 0.42f), Offset(w / 2, w * 0.64f), s, StrokeCap.Round)
        drawLine(color, Offset(w * 0.72f, w * 0.42f), Offset(w / 2, w * 0.64f), s, StrokeCap.Round)
        drawLine(color, Offset(w * 0.1f, w * 0.7f), Offset(w * 0.1f, w * 0.9f), s, StrokeCap.Round)
        drawLine(color, Offset(w * 0.1f, w * 0.9f), Offset(w * 0.9f, w * 0.9f), s, StrokeCap.Round)
        drawLine(color, Offset(w * 0.9f, w * 0.9f), Offset(w * 0.9f, w * 0.7f), s, StrokeCap.Round)
    }
}

/** Thor Reader's drawn magnifier. */
@Composable
fun MagnifierIcon() {
    Canvas(Modifier.size(20.dp)) {
        val r = size.minDimension * 0.32f
        val c = Offset(size.width * 0.42f, size.height * 0.42f)
        drawCircle(Color.White, r, c, style = Stroke(width = size.minDimension * 0.1f))
        val d = r * 0.72f
        drawLine(Color.White, Offset(c.x + d, c.y + d), Offset(size.width * 0.9f, size.height * 0.9f), size.minDimension * 0.12f, StrokeCap.Round)
    }
}

/** A screen title in the top bar's centre (Details, Queue, Settings). */
@Composable
fun BarTitle(t: String) = Text(t, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)

/** Back button for the left of a screen's header row (B / Back do the same). */
@Composable
fun BackButton() {
    val host = LocalHost.current
    RoundIcon({ host.back() }) { Text("←", color = Color.White, fontSize = 20.sp) }
}

