package dev.thorreader.app

import dev.thorreader.app.ui.Key
import dev.thorreader.app.ui.TypingSession
import dev.thorreader.app.ui.caretText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The built-in bottom-screen keyboard: what each key does to the text being typed. */
class TypingTest {
    private fun session(text: String = "", kind: TypingSession.Kind = TypingSession.Kind.TEXT): Pair<TypingSession, MutableList<String>> {
        val seen = mutableListOf<String>()
        return TypingSession(text, kind, "hint", { seen += it }, {}) to seen
    }

    @Test
    fun typesLiveAndMovesTheCursor() {
        val (s, seen) = session("dne")
        s.moveCursor(-2)
        s.insert("u")
        assertEquals("dune", s.text)
        assertEquals(2, s.cursor)
        assertEquals(listOf("dune"), seen) // the list filters on every change
        s.backspace()
        assertEquals("dne", s.text)
        s.moveCursor(-10)
        s.backspace() // nothing before the cursor
        assertEquals("dne", s.text)
    }

    @Test
    fun shiftCapitalisesOneLetter() {
        val (s, _) = session()
        s.press(Key.Shift)
        val upperQ = s.keys()[1].first() as Key.Char
        assertEquals("Q", upperQ.c)
        s.press(upperQ)
        val q = s.keys()[1].first() as Key.Char
        assertEquals("q", q.c)
        s.press(q)
        assertEquals("Qq", s.text)
    }

    @Test
    fun symbolsPageAndBack() {
        val (s, _) = session()
        s.press(Key.ToSymbols)
        assertEquals("@", (s.keys()[1].first() as Key.Char).c)
        s.press(Key.ToLetters)
        assertEquals("q", (s.keys()[1].first() as Key.Char).c)
    }

    @Test
    fun numberFieldGetsANumberPadAndOnlyDigits() {
        val (s, _) = session(kind = TypingSession.Kind.NUMBER)
        assertEquals(listOf("1", "2", "3"), s.keys()[0].map { it.label })
        s.insert("1a2.5")
        assertEquals("12.5", s.text)
        assertTrue(s.keys().flatten().none { it == Key.Shift || it == Key.Space })
    }

    @Test
    fun singleLineFieldsTurnNewlinesIntoSpaces() {
        val (s, _) = session()
        s.insert("a\nb")
        assertEquals("a b", s.text)
        val (m, _) = session(kind = TypingSession.Kind.MULTILINE)
        m.insert("a\nb")
        assertEquals("a\nb", m.text)
        assertTrue(m.keys().last().contains(Key.Enter))
    }

    @Test
    fun doneEndsTypingAndDpadHighlightStaysOnTheKeyboard() {
        val (s, _) = session()
        assertTrue(s.press(Key.Char("x")))
        assertFalse(s.press(Key.Done))
        s.moveHighlight(-5, -5)
        assertEquals(0 to 0, s.row to s.col)
        s.moveHighlight(10, 20)
        assertEquals(s.keys().lastIndex, s.row)
        assertEquals(Key.Done, s.highlighted())
    }

    @Test
    fun caretIsDrawnAtTheCursor() {
        assertEquals("ab|c", caretText("abc", 2).text)
        assertEquals("abc", caretText("abc", -1).text)
    }
}
