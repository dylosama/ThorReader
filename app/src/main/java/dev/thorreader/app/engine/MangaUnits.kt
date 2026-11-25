package dev.thorreader.app.engine

/**
 * Manga files are volumes (tankōbon) or single chapters. This tells which, from the file name
 * (the way scanlation groups, Mihon / Tachiyomi, and digital volume releases name them):
 *   "Blue Period v01 (2020) (Digital) (1r0n).cbz"   → volume 1
 *   "Chainsaw Man, Vol. 1.cbz"                      → volume 1
 *   "Chainsaw Man, Ch. 1 A Dog and a Chainsaw.cbz"  → chapter 1
 *   "Blue Period - c047.cbz", "Vol.02 Ch.012.5"     → chapter 47, chapter 12.5 (volume 2)
 *   "One Piece 1045.cbz" (3+ digits)                → chapter 1045; "Series 03" → volume 3
 *   "呪術廻戦 第47話"                                 → chapter 47
 * Pure (unit-tested); computed when needed, so existing books are covered too.
 */
object MangaUnits {
    enum class Kind { VOLUME, CHAPTER }

    data class Unit(val series: String?, val kind: Kind, val volume: Int?, val chapter: Float?) {
        /** Where it sits in reading order: a volume at its number, a chapter by its number. */
        val label: String
            get() = when (kind) {
                Kind.VOLUME -> "Vol. ${volume ?: "?"}"
                Kind.CHAPTER -> "Ch. ${chapter?.let(::fmt) ?: "?"}"
            }
    }

    private const val NUM = """(\d{1,4}(?:\.\d{1,2})?)"""
    private val bracketed = Regex("""\s*[\(\[\{][^)\]\}]*[\)\]\}]""")
    private val sep = """[\s,._-]+"""

    /** "… v02 ch 12 …", "… Vol.2 Chapter 12.5 …" (volume and chapter). */
    private val volChapter = Regex("""^(.*?)$sep(?:v|vol|volume)\.?\s*(\d{1,3})$sep(?:c|ch|chap|chapter|#)\.?\s*$NUM(?:\b.*)?$""", RegexOption.IGNORE_CASE)

    /** "… Ch. 1 Title", "… c047", "… Chapter 12.5", "… #47". */
    private val chapter = Regex("""^(.*?)$sep(?:c|ch|chap|chapter|#)\.?\s*$NUM(?:\b.*)?$""", RegexOption.IGNORE_CASE)

    /** "… 第47話" (Japanese chapter). */
    private val jaChapter = Regex("""^(.*?)\s*第\s*$NUM\s*[話话回]""")

    /** "… v01", "… Vol. 1", "… Volume 3", "… 第3巻". */
    private val volume = Regex("""^(.*?)$sep(?:v|vol|volume|tome|t)\.?\s*(\d{1,3})(?:\b.*)?$""", RegexOption.IGNORE_CASE)
    private val jaVolume = Regex("""^(.*?)\s*第?\s*(\d{1,3})\s*巻""")

    /** "… 047" / "… 1045" (chapter) or "… 03" (volume): a bare number at the end. */
    private val bare = Regex("""^(.*?)$sep(\d{1,4}(?:\.\d{1,2})?)$""")

    fun fromFileName(fileName: String): Unit? {
        val base = fileName.substringBeforeLast('.').replace('_', ' ')
            .replace(bracketed, "").replace(Regex("\\s+"), " ").trim()
        fun s(x: String) = x.trim().trimEnd(',', '-', '.', ':', ' ').trim().ifEmpty { null }
        volChapter.find(base)?.let { m ->
            return Unit(s(m.groupValues[1]), Kind.CHAPTER, m.groupValues[2].toIntOrNull(), m.groupValues[3].toFloatOrNull())
        }
        jaChapter.find(base)?.let { m -> return Unit(s(m.groupValues[1]), Kind.CHAPTER, null, m.groupValues[2].toFloatOrNull()) }
        chapter.find(base)?.let { m ->
            // "Spider-Man v1 001" style is caught by volChapter; a lone "c" must be glued to digits.
            return Unit(s(m.groupValues[1]), Kind.CHAPTER, null, m.groupValues[2].toFloatOrNull())
        }
        volume.find(base)?.let { m -> return Unit(s(m.groupValues[1]), Kind.VOLUME, m.groupValues[2].toIntOrNull(), null) }
        jaVolume.find(base)?.let { m -> return Unit(s(m.groupValues[1]), Kind.VOLUME, m.groupValues[2].toIntOrNull(), null) }
        bare.find(base)?.let { m ->
            val digits = m.groupValues[2]
            val n = digits.toFloatOrNull() ?: return null
            val intPart = digits.substringBefore('.')
            // Chapters are zero-padded to 3+ digits or run high; volumes are small numbers.
            return if (intPart.length >= 3 || n > 99 || '.' in digits) {
                Unit(s(m.groupValues[1]), Kind.CHAPTER, null, n)
            } else {
                Unit(s(m.groupValues[1]), Kind.VOLUME, n.toInt(), null)
            }
        }
        return null
    }

    fun fmt(f: Float): String = if (f == f.toLong().toFloat()) f.toLong().toString() else f.toString()
}
