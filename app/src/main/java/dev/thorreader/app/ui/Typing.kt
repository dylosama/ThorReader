package dev.thorreader.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.thorreader.app.ThorApp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One text field being edited. While it exists, the bottom screen shows the typing area
 * (built-in keyboard, or the system keyboard placed on the bottom screen) and the top screen
 * stays fully visible: the field there mirrors [text] live.
 */
class TypingSession(
    initial: String,
    val kind: Kind,
    val hint: String,
    private val onChange: (String) -> Unit,
    val onDone: () -> Unit,
) {
    enum class Kind { TEXT, NUMBER, MULTILINE, SECRET }

    var text by mutableStateOf(initial)
        private set
    var cursor by mutableIntStateOf(initial.length)
        private set

    /** Set when a touch lands on the field or the keyboard (so it doesn't end typing). */
    var touchedInside = false

    /** Keyboard key highlighted for the d-pad: row and column on the current page. */
    var row by mutableIntStateOf(0)
    var col by mutableIntStateOf(0)
    var page by mutableStateOf(Page.LETTERS)
    var shift by mutableStateOf(false)

    enum class Page { LETTERS, SYMBOLS }

    fun set(newText: String, newCursor: Int = newText.length) {
        text = newText
        cursor = newCursor.coerceIn(0, newText.length)
        onChange(newText)
    }

    fun insert(s: String) {
        val t = if (kind == Kind.NUMBER) s.filter { it.isDigit() || it == '.' } else if (kind == Kind.MULTILINE) s else s.replace("\n", " ")
        if (t.isEmpty()) return
        set(text.substring(0, cursor) + t + text.substring(cursor), cursor + t.length)
        if (shift && kind != Kind.NUMBER) shift = false
    }

    fun backspace() {
        if (cursor == 0) return
        set(text.substring(0, cursor - 1) + text.substring(cursor), cursor - 1)
    }

    fun moveCursor(d: Int) {
        cursor = (cursor + d).coerceIn(0, text.length)
    }

    /** The keys of the current page (rows). */
    fun keys(): List<List<Key>> = Keyboard.layout(kind, page, shift)

    /** The key the d-pad highlight is on. */
    fun highlighted(): Key? = keys().getOrNull(row)?.let { r -> r.getOrNull(col.coerceIn(0, r.lastIndex)) }

    /** D-pad: move the highlight. */
    fun moveHighlight(dRow: Int, dCol: Int) {
        val k = keys()
        row = (row + dRow).coerceIn(0, k.lastIndex)
        col = (col + dCol).coerceIn(0, k[row].lastIndex)
    }

    /** A key was pressed (tap or A on the highlight). Returns false when typing should end. */
    fun press(key: Key): Boolean {
        when (key) {
            is Key.Char -> insert(key.c)
            Key.Space -> insert(" ")
            Key.Back -> backspace()
            Key.Enter -> insert("\n")
            Key.Shift -> shift = !shift
            Key.Left -> moveCursor(-1)
            Key.Right -> moveCursor(1)
            Key.ToSymbols -> page = Page.SYMBOLS
            Key.ToLetters -> page = Page.LETTERS
            Key.Done -> return false
        }
        val k = keys()
        row = row.coerceIn(0, k.lastIndex)
        col = col.coerceIn(0, k[row].lastIndex)
        return true
    }
}

/** A key of the built-in keyboard. */
sealed class Key(val label: String, val weight: Float = 1f) {
    class Char(val c: String) : Key(c)
    data object Space : Key("space", 4f)
    data object Back : Key("⌫", 1.5f)
    data object Enter : Key("↵", 1.5f)
    data object Shift : Key("⇧", 1.5f)
    data object Left : Key("◀")
    data object Right : Key("▶")
    data object ToSymbols : Key("?123", 1.5f)
    data object ToLetters : Key("ABC", 1.5f)
    data object Done : Key("Done", 2f)
}

/** Keyboard layouts: QWERTY with shift, a numbers/symbols page, and a number pad. */
object Keyboard {
    private fun chars(s: String, upper: Boolean) = s.map { Key.Char(if (upper) it.uppercase() else it.toString()) }

    fun layout(kind: TypingSession.Kind, page: TypingSession.Page, shift: Boolean): List<List<Key>> {
        if (kind == TypingSession.Kind.NUMBER) {
            return listOf(
                chars("123", false),
                chars("456", false),
                chars("789", false),
                listOf(Key.Char("."), Key.Char("0"), Key.Back),
                listOf(Key.Left, Key.Right, Key.Done),
            )
        }
        val bottom = buildList {
            add(if (page == TypingSession.Page.LETTERS) Key.ToSymbols else Key.ToLetters)
            add(Key.Left)
            add(Key.Space)
            add(Key.Right)
            if (kind == TypingSession.Kind.MULTILINE) add(Key.Enter)
            add(Key.Done)
        }
        return if (page == TypingSession.Page.LETTERS) {
            listOf(
                chars("1234567890", false),
                chars("qwertyuiop", shift),
                chars("asdfghjkl'", shift),
                listOf(Key.Shift) + chars("zxcvbnm", shift) + chars(",.", false) + listOf(Key.Back),
                bottom,
            )
        } else {
            listOf(
                chars("1234567890", false),
                chars("@#&_-+()/:", false),
                chars("!?\"*;=%$€£", false),
                chars("[]{}<>~|\\^", false) + listOf(Key.Back),
                bottom,
            )
        }
    }
}

/**
 * A text field on the top screen. It never opens the system keyboard there: tapping it starts
 * typing on the bottom screen, and it shows the text live (with a caret while being edited).
 */
@Composable
fun TypingField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String = "",
    kind: TypingSession.Kind = TypingSession.Kind.TEXT,
    textColor: Color = Color.White,
    fontSize: Int = 15,
    onDone: () -> Unit = {},
    autoStart: Boolean = false,
) {
    val app = ThorApp.instance
    val session = app.typing
    val mine = remember { mutableStateOf<TypingSession?>(null) }
    val active = session != null && session === mine.value
    fun start() {
        val s = TypingSession(value, kind, hint, onValueChange, onDone)
        mine.value = s
        app.startTyping(s)
    }
    androidx.compose.runtime.LaunchedEffect(Unit) { if (autoStart) start() }
    val shown = if (active) session!!.text else value
    Box(
        modifier
            .typingArea()
            .border(if (active) 2.dp else 0.dp, if (active) Accent else Color.Transparent, RoundedCornerShape(8.dp))
            .focusClick(RoundedCornerShape(8.dp)) { if (!active) start() },
    ) {
        if (shown.isEmpty() && !active) {
            Text(hint, color = Color(0xFF777777), fontSize = fontSize.sp, maxLines = 1)
        } else {
            Text(
                caretText(if (kind == TypingSession.Kind.SECRET && !active) "•".repeat(shown.length) else shown, if (active) session!!.cursor else -1),
                color = textColor,
                fontSize = fontSize.sp,
                maxLines = if (kind == TypingSession.Kind.MULTILINE) 8 else 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Touches here are "inside" typing and don't end it (the field and the keyboard). */
fun Modifier.typingArea(): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
        ThorApp.instance.typing?.touchedInside = true
    }
}

/** [text] with a caret drawn at [cursor] (-1 = none). A still caret: no blinking (no redraws). */
fun caretText(text: String, cursor: Int): AnnotatedString = buildAnnotatedString {
    if (cursor < 0) {
        append(text)
        return@buildAnnotatedString
    }
    val c = cursor.coerceIn(0, text.length)
    append(text.substring(0, c))
    pushStyle(SpanStyle(color = Accent, fontWeight = FontWeight.Bold))
    append("|")
    pop()
    append(text.substring(c))
}

/**
 * The built-in keyboard (approach B), drawn on the bottom screen in the house style: a preview
 * strip with the text and caret, then big keys. Backspace repeats while held. The d-pad
 * highlight (from ThorApp key handling) is shown as an outline.
 */
@Composable
fun BottomKeyboard(s: TypingSession) {
    val app = ThorApp.instance
    Column(
        Modifier.fillMaxSize().typingArea().background(Color(0xFF0C0C0C)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PreviewStrip(s)
        val rows = s.keys()
        rows.forEachIndexed { r, keys ->
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                keys.forEachIndexed { c, k ->
                    KeyButton(k, highlighted = r == s.row && c == s.col.coerceIn(0, keys.lastIndex)) {
                        app.tick()
                        if (!s.press(k)) app.stopTyping("Done key")
                    }
                }
            }
        }
    }
}

/** The text being typed, with the caret; and a Paste button (handy for API keys). */
@Composable
fun PreviewStrip(s: TypingSession) {
    val app = ThorApp.instance
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(HouseStyle.PanelShape)
            .background(HouseStyle.PanelBackground)
            .border(1.dp, HouseStyle.Hairline, HouseStyle.PanelShape)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.weight(1f)) {
            if (s.text.isEmpty()) {
                Text(caretText("", 0), fontSize = 18.sp)
                Text("   " + s.hint, color = Color(0xFF777777), fontSize = 16.sp, maxLines = 1)
            } else {
                Text(caretText(s.text, s.cursor), color = Color.White, fontSize = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (s.kind != TypingSession.Kind.NUMBER) {
            Chip("Paste") {
                val clip = app.getSystemService(android.content.ClipboardManager::class.java)?.primaryClip
                clip?.getItemAt(0)?.coerceToText(app)?.toString()?.let { s.insert(it.trim()) }
            }
        }
    }
}

@Composable
private fun RowScope.KeyButton(k: Key, highlighted: Boolean, onPress: () -> Unit) {
    val scope = rememberCoroutineScope()
    var pressed by remember { mutableStateOf(false) }
    val special = k !is Key.Char && k != Key.Space
    Box(
        Modifier
            .weight(k.weight)
            .fillMaxSize()
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    pressed -> Color(0xFF4A4A4A)
                    k == Key.Done -> Accent
                    special -> Color(0xFF242424)
                    else -> Color(0xFF2E2E2E)
                },
            )
            .border(2.dp, if (highlighted) Accent else Color.Transparent, RoundedCornerShape(10.dp))
            .pointerInput(k) {
                awaitEachGesture {
                    awaitFirstDown()
                    pressed = true
                    onPress()
                    // Backspace repeats while held.
                    val repeat = if (k == Key.Back) scope.launch {
                        delay(450)
                        while (true) {
                            onPress()
                            delay(70)
                        }
                    } else null
                    waitForUpOrCancellation()
                    repeat?.cancel()
                    pressed = false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            k.label,
            color = if (k == Key.Done) OnAccent else Color.White,
            fontSize = if (k is Key.Char) 22.sp else 16.sp,
            fontWeight = if (k == Key.Done) FontWeight.Bold else FontWeight.Normal,
        )
    }
}
