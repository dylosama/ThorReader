package dev.thorreader.app.engine

/**
 * Turns a table-of-contents entry into a running header:
 *   "Chapter 3: The Humming Ridge" -> "Ch. 3 · The Humming Ridge"
 *   "3. The Humming Ridge"         -> "Ch. 3 · The Humming Ridge"
 *   "CHAPTER XII"                  -> "Ch. XII"
 *   "Prologue"                     -> "Prologue"   (no number: title only)
 * A number is only recognised after a "chapter" word or when followed by a separator, so titles
 * like "1984" or "I Am Legend" stay as they are.
 */
object ChapterTitle {
    private const val NUM = """(\d{1,4}|[IVXLCDM]{1,7}|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty)"""
    private const val SEP = """\s*[:.\-–—]\s*"""
    private val withWord = Regex("""^\s*(?:chapter|chap\.?|ch\.?|kapitel|chapitre|cap[ií]tulo|capitolo|hoofdstuk)\s+$NUM(?:$SEP|\s+|$)(.*)$""", RegexOption.IGNORE_CASE)
    private val numberFirst = Regex("""^\s*$NUM$SEP(.+)$""", RegexOption.IGNORE_CASE)

    private val WORDS = setOf(
        "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve",
        "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty",
    )

    fun format(label: String?): String? {
        val t = label?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val m = withWord.find(t) ?: numberFirst.find(t)
        if (m != null) {
            val number = m.groupValues[1]
            // Roman numerals only count if written in capitals (avoids "I. ..." words).
            val isRoman = number.all { it in "IVXLCDM" }
            val isWord = number.lowercase() in WORDS
            if (number.any { it.isDigit() } || isRoman || isWord) {
                val rest = m.groupValues[2].trim().trimStart(':', '.', '-', '–', '—').trim()
                val num = if (isWord) number.lowercase().replaceFirstChar { it.uppercase() } else number
                return if (rest.isEmpty()) "Ch. $num" else "Ch. $num · $rest"
            }
        }
        return t
    }
}
