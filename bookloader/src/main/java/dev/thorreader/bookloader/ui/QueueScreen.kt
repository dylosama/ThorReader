package dev.thorreader.bookloader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dev.thorreader.bookloader.BookloaderApp
import dev.thorreader.bookloader.LocalHost
import dev.thorreader.bookloader.work.JobAction
import dev.thorreader.bookloader.work.JobStore
import dev.thorreader.bookloader.work.Jobs

/** Download & import queue: progress, results, and what to do when an import can't happen. */
@Composable
fun QueueScreen() {
    val host = LocalHost.current
    val ctx = LocalContext.current
    val wm = remember { WorkManager.getInstance(ctx) }
    val flow = remember { wm.getWorkInfosByTagFlow(Jobs.TAG_JOB) }
    val infos by flow.collectAsStateSafe()
    val rows = infos.mapNotNull { w -> JobStore.get(w.id.toString())?.let { w to it } }.sortedByDescending { it.second.createdAt }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TopBar {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                BackButton()
                BarTitle("Queue")
                Chip("Clear finished", enabled = rows.any { it.first.state.isFinished }) {
                    rows.filter { it.first.state.isFinished }.forEach { JobStore.remove(it.first.id.toString()) }
                    wm.pruneWork()
                }
            }
        }
        if (rows.isEmpty()) Message("Nothing queued. Pick a book, chapters or issues and choose Import or Download.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(rows, key = { it.first.id.toString() }) { (w, spec) ->
                val out = w.outputData
                val status = out.getString("status")
                val message = out.getString("message").orEmpty()
                val localPath = out.getString("localPath")
                Panel(Modifier.fillMaxWidth(), PaddingValues(12.dp), plain = true) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(spec.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(if (spec.action == JobAction.IMPORT) "→ Thor Reader" else "→ storage", color = House.Faint, fontSize = 13.sp)
                    }
                    val line = when (w.state) {
                        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> "Waiting" + if (spec.localPath == null) " (needs a network connection)" else ""
                        WorkInfo.State.RUNNING -> {
                            val p = w.progress.getFloat("p", 0f)
                            (w.progress.getString("stage") ?: "Working") + if (w.progress.getString("stage") == "Downloading") " ${(p * 100).toInt()} %" else "…"
                        }
                        WorkInfo.State.CANCELLED -> "Cancelled"
                        else -> statusText(status, message)
                    }
                    val good = status == "IMPORTED" || status == "SAVED" || status == "DUPLICATE"
                    Text(line, color = if (w.state.isFinished && !good && w.state != WorkInfo.State.CANCELLED) House.Warning else if (good) House.Ok else House.Subtle, fontSize = 14.sp)
                    if (w.state == WorkInfo.State.RUNNING && w.progress.getString("stage") == "Downloading") {
                        Bar(w.progress.getFloat("p", 0f))
                    }
                    // --- what can be done ---
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!w.state.isFinished) Chip("Cancel") { wm.cancelWorkById(w.id) }
                        if (w.state.isFinished && !good) {
                            val reuse = spec.copy(localPath = localPath, createdAt = System.currentTimeMillis())
                            when (status) {
                                "NEEDS_FOLDER" -> {
                                    Chip("Choose folder in Thor Reader", selected = true) { host.chooseThorFolder() }
                                    Chip("Retry") { JobStore.remove(w.id.toString()); Jobs.enqueue(ctx, reuse) }
                                }
                                "NOT_INSTALLED", "NO_PERMISSION" -> Unit
                                else -> Chip("Retry") { JobStore.remove(w.id.toString()); Jobs.enqueue(ctx, reuse) }
                            }
                            if (spec.action == JobAction.IMPORT) {
                                Chip("Save to storage instead", selected = status == "NOT_INSTALLED" || status == "NO_PERMISSION") {
                                    val save = reuse.copy(action = JobAction.SAVE)
                                    val go = { JobStore.remove(w.id.toString()); Jobs.enqueue(ctx, save); Unit }
                                    if (BookloaderApp.instance.downloadFolder == null) host.pickDownloadFolder(go) else go()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun statusText(status: String?, message: String) = when (status) {
    "IMPORTED" -> "Added to Thor Reader"
    "DUPLICATE" -> "Already in Thor Reader"
    "SAVED" -> message.ifEmpty { "Saved" }
    "NEEDS_FOLDER" -> "Thor Reader needs its Imported books folder first. Choose it, then Retry."
    "NOT_INSTALLED" -> "Thor Reader isn't installed. You can save the file to storage instead."
    "NO_PERMISSION" -> message.ifEmpty { "Thor Reader didn't allow the import." }
    "UNSUPPORTED" -> "Thor Reader can't open this file" + if (message.isNotEmpty()) " ($message)" else ""
    else -> "Failed" + if (message.isNotEmpty()) ": $message" else ""
}

@Composable
private fun Bar(p: Float) {
    Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(House.Chip)) {
        Box(Modifier.fillMaxWidth(p.coerceIn(0f, 1f)).height(4.dp).background(House.Accent))
    }
}
