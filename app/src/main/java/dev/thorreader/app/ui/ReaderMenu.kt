package dev.thorreader.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.thorreader.app.PageTheme
import dev.thorreader.app.ReadingMode
import dev.thorreader.app.Settings
import dev.thorreader.app.ThorApp
import dev.thorreader.app.Screen
import dev.thorreader.app.TutorialStep
import dev.thorreader.app.engine.ChapterTitle
import dev.thorreader.app.engine.ReaderSession
import dev.thorreader.app.engine.BlankUnit
import dev.thorreader.app.engine.leftUnitIndex
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.layout.size
import dev.thorreader.app.importer.formatNumber
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Reader menu, opened by pressing and holding a page (or Start). Big "Return to reading" and
 * "Close book" buttons first, then book info, the four modes and settings.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReaderMenu(session: ReaderSession) {
    val app = ThorApp.instance
    val settings = app.settings
    val book = session.book
    val p = session.pagination

    // Dim background; tapping outside the panel closes the menu.
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x99000000))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { app.menuScreen = null },
        contentAlignment = Alignment.Center,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
            Column(
                Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(HouseStyle.PanelBackground)
                    .border(1.dp, HouseStyle.Hairline, RoundedCornerShape(16.dp))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // 1) Big actions.
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BigButton("Return to reading", primary = true) { app.menuScreen = null }
                    BigButton("Close book", primary = false) { app.closeBookAnimated("Close book button") }
                }

                // 2) The book: cover, title, author, progress; the time and battery.
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    BookCover(book, Modifier.size(64.dp, 92.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(book.title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val byline = listOfNotNull(
                            book.author,
                            book.series?.let { s -> s + (book.seriesNumber?.let { " #" + formatNumber(it) } ?: "") },
                        ).joinToString("  ·  ")
                        if (byline.isNotEmpty()) Text(byline, color = SubtleText, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        // Books: already the chosen chapter name; PDFs: raw outline titles.
                        p?.labelAt(session.viewIndex)?.let { if (session.reflowable) it else ChapterTitle.format(it) }?.let {
                            Text(it, color = Accent, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        val pct = (session.progress * 100).toInt()
                        Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(3.dp).clip(RoundedCornerShape(50)).background(HouseStyle.Hairline)) {
                            Box(Modifier.fillMaxWidth(session.progress.coerceIn(0f, 1f)).height(3.dp).background(Accent))
                        }
                        if (p != null) {
                            val pages = if (p.complete) "Page ${session.viewIndex + 1} of ${p.views.size}" else "Page ${session.viewIndex + 1} (still laying out)"
                            Text("$pages  ·  $pct%", color = SubtleText, fontSize = HouseStyle.TextSize)
                        }
                    }
                    Clock()
                }

                // 3) Bookmark (one per book) + its colour.
                BookmarkControls(session)

                // 4) The four modes on one row.
                if (app.tutorialShows(TutorialStep.CHOOSE_MODE)) {
                    TutorialBadge(TutorialStep.CHOOSE_MODE, "Choose how you hold your Thor", ArrowDir.DOWN, arrowAlign = Alignment.Start)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReadingMode.entries.forEach { m ->
                        ModeTile(m, m == settings.mode) {
                            app.tutorialDone(TutorialStep.CHOOSE_MODE)
                            app.setMode(m)
                        }
                    }
                }
                if (settings.mode == ReadingMode.BOTTOM) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Bottom screen:", color = Color.White, fontSize = 14.sp)
                        Chip("One page", !settings.bottomTwoPage) { settings.updateBottomTwoPage(false) }
                        Chip("Two pages", settings.bottomTwoPage) { settings.updateBottomTwoPage(true) }
                    }
                }

                // 5) Page navigation.
                if (p != null) {
                    if (session.recentJump) {
                        session.backPage?.let { page ->
                            Chip("↩ Back to page $page") {
                                session.jumpBack()
                                app.menuScreen = null
                            }
                        }
                    }
                    ScrubTrack(session, Modifier.fillMaxWidth()) { session.endScrub(true) }
                    // -10 -1 · Go to page · Chapters · +1 +10 (mirrored in right-to-left books).
                    val steps = if (p.config.rtl) listOf(10, 1, -1, -10) else listOf(-10, -1, 1, 10)
                    var panel by remember { mutableStateOf(0) } // 0 none, 1 number pad, 2 chapters
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        steps.take(2).forEach { d -> Chip(if (d > 0) "+$d" else "$d") { session.jumpBy(d) } }
                        Box(Modifier.weight(1f))
                        Chip("Go to page", panel == 1) { panel = if (panel == 1) 0 else 1 }
                        Chip("Chapters", panel == 2) { panel = if (panel == 2) 0 else 2 }
                        Box(Modifier.weight(1f))
                        steps.drop(2).forEach { d -> Chip(if (d > 0) "+$d" else "$d") { session.jumpBy(d) } }
                    }
                    when (panel) {
                        1 -> NumberPad(session) { app.menuScreen = null }
                        2 -> ChapterList(session) { app.menuScreen = null }
                    }
                }

                // 6) Reading.
                if (session.reflowable) {
                    Section("Reading")
                    val mode = settings.mode
                    if (mode.isBook) OnOffRow("Same text size on both screens", settings.sameFont) { settings.updateSameFont(it) }
                    if (mode.isBook && !settings.sameFont) {
                        FontRow("Top screen", Screen.TOP, labelWidth = 110)
                        FontRow("Bottom screen", Screen.BOTTOM, labelWidth = 110)
                    } else {
                        // One control: mode 4 changes the bottom screen's size, the others the top's.
                        FontRow("Text size", if (mode == ReadingMode.BOTTOM) Screen.BOTTOM else Screen.TOP, labelWidth = 110)
                    }
                    ChoiceRow("Margins", Settings.MARGIN_LABELS.indices.toList(), settings.marginLevel, { Settings.MARGIN_LABELS[it] }) { settings.updateMargin(it) }
                    PageThemeRow()
                }

                // 7) More (folded).
                FoldHeader("More", app.menuMoreOpen) { app.menuMoreOpen = !app.menuMoreOpen }
                if (app.menuMoreOpen) {
                    OnOffRow(
                        if (book.series != null) "Right-to-left (manga), whole series" else "Right-to-left (manga)",
                        book.rtl,
                    ) { app.setReadingDirection(book, it) }
                    if (book.rtl) {
                        // Right-to-left: let the easy-to-reach shoulder go forward (saved per series).
                        OnOffRow(
                            "Swap L1/R1 for manga" + if (book.series != null) " (whole series)" else "",
                            settings.mangaShoulderSwap(book.id, book.series),
                        ) { settings.updateMangaShoulderSwap(book.id, book.series, it) }
                    }
                    if (!session.reflowable) {
                        // Which wide pages count as double-page spreads (split across the two screens).
                        ChoiceRow("Split wide pages (this book)", dev.thorreader.app.engine.SplitWide.entries, settings.splitWideFor(book.id), { it.label }) {
                            settings.updateSplitWide(book.id, it)
                        }
                        SwipeSwapRow()
                        Text(
                            "Swap screens: d-pad right or Y in Mode 1, Y in Mode 2 (always on). Double-tap or pinch to zoom.",
                            color = SubtleText, fontSize = 12.sp,
                        )
                    }
                    HeaderRow()
                    PageTurnStyleRow()
                    ChoiceRow(
                        "Page turn (this book)", listOf<dev.thorreader.app.engine.TurnStyle?>(null) + dev.thorreader.app.engine.TurnStyle.entries,
                        settings.turnStyleOverride(book.id), { it?.label ?: "Same as all" },
                    ) { settings.updateTurnStyleOverride(book.id, it) }
                    HoldToMenuRow()
                    SelectButtonRow()
                    Chip("Show tutorial again") { app.restartTutorial() }
                }
            }
        }
    }
}

@Composable
private fun RowScope.BigButton(label: String, primary: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .weight(1f)
            .height(64.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (primary) Accent else Color(0xFF3A3A3A))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (primary) OnAccent else Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * "Bookmark" toggle (on = this view shows the bookmarked page). In two-page views turning it on
 * asks "Left page / Right page". Next to it: the colour swatch that opens the picker, and
 * "Go to bookmark" when the bookmark is on another page.
 */
@Composable
private fun BookmarkControls(session: ReaderSession) {
    val app = ThorApp.instance
    val p = session.pagination ?: return
    val view = p.views.getOrNull(session.viewIndex) ?: return
    val here = session.bookmarkHere
    var asking by remember(session.viewIndex) { mutableStateOf(false) }
    var picker by remember { mutableStateOf(false) }
    DisposableEffect(picker) {
        app.menuBack = if (picker) ({ picker = false; true }) else null
        onDispose { app.menuBack = null }
    }
    val twoPages = view.units.size == 2 && view.units.none { it is BlankUnit }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Chip(if (here) "🔖 Bookmark: On" else "🔖 Bookmark: Off", here) {
                when {
                    here -> session.clearBookmark("menu toggle")
                    twoPages -> asking = !asking
                    else -> session.setBookmark(view.units.indexOfFirst { it !is BlankUnit }.coerceAtLeast(0), "menu toggle")
                }
            }
            ColorSwatch(Color(app.settings.bookmarkColor), selected = picker, size = 40) { picker = !picker }
            if (!here) {
                session.bookmarkPage?.let { page -> Chip("Go to bookmark (page $page)") { session.goToBookmark(); app.menuScreen = null } }
            }
        }
        if (asking && !here) {
            // Which page of the two: physical left / right (mirrored order in right-to-left books).
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Bookmark which page?", color = HouseStyle.Subtle, fontSize = HouseStyle.ItemTextSize)
                val left = leftUnitIndex(p.config.rtl)
                HouseButton("Left page") { session.setBookmark(left, "menu, left page"); asking = false }
                HouseButton("Right page") { session.setBookmark(1 - left, "menu, right page"); asking = false }
            }
        }
        if (picker) BookmarkColorPicker()
    }
}

/** Small house-style button (translucent pill, white text). */
@Composable
private fun HouseButton(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = HouseStyle.TextColor,
        fontSize = HouseStyle.ItemTextSize,
        modifier = Modifier
            .clip(HouseStyle.Shape)
            .background(HouseStyle.Background)
            .border(1.dp, HouseStyle.Hairline, HouseStyle.Shape)
            .focusClick(HouseStyle.Shape, onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

/** "Size  A−  10 pt  A+" for one screen (with "same size" on, that's the shared size). */
@Composable
private fun FontRow(label: String, screen: Screen, labelWidth: Int) {
    val settings = ThorApp.instance.settings
    val pt = settings.fontFor(screen)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = Color.White, fontSize = 14.sp, modifier = Modifier.width(labelWidth.dp))
        Chip("A−") { settings.updateFontFor(screen, pt - 1f) }
        Text("${pt.toInt()} pt", color = Color.White, fontSize = 15.sp)
        Chip("A+") { settings.updateFontFor(screen, pt + 1f) }
    }
}

@Composable
private fun Section(title: String) {
    Text(title.uppercase(), color = Color(0xFF888888), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
}

@Composable
private fun Clock() {
    var now by remember { mutableStateOf(Date()) }
    // Once a minute, on the minute, and only while the menu (this composable) is showing.
    LaunchedEffect(Unit) {
        while (true) {
            now = Date()
            delay(60_000 - System.currentTimeMillis() % 60_000 + 50)
        }
    }
    val fmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    // Time │ battery: a thin divider and spacing, and the percentage smaller and muted, so the
    // two never read as one number. The battery is only read while the menu is open.
    val battery = rememberBattery()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(fmt.format(now), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Light)
        if (battery != null) {
            Box(Modifier.width(1.dp).height(20.dp).background(HouseStyle.Hairline))
            BatteryIndicator(battery)
        }
    }
}
