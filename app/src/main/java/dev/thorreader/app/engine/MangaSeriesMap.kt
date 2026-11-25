package dev.thorreader.app.engine

/**
 * A manga series' chapter map: which chapters each volume holds, the chapters not yet in a
 * volume, and whether the series is still running. Built from Wikipedia's "Graphic novel list"
 * tables (the volume lists of manga articles and "List of … chapters" pages) and AniList's
 * status. Pure parsing here (unit-tested); the fetching is in manga/MangaSeries.
 */
data class MangaSeriesMap(
    /** Volume number → its chapter numbers, in order. */
    val volumes: Map<Int, List<Float>>,
    /** Chapters published but not collected in a volume yet. */
    val loose: List<Float>,
    val status: Status?,
    /** AniList's chapter count (only known once a series has ended). */
    val totalChapters: Int?,
    /** Where it came from, for the info line ("Wikipedia", "AniList"). */
    val source: String,
) {
    enum class Status { ONGOING, COMPLETED, HIATUS, CANCELLED }

    /** Every known chapter, in order. */
    val chapters: List<Float> get() = (volumes.values.flatten() + loose).distinct().sorted()

    /** The last chapter known: the end of the series when completed. */
    val lastChapter: Float?
        get() = listOfNotNull(chapters.maxOrNull(), totalChapters?.toFloat()).maxOrNull()

    fun volumeOf(chapter: Float): Int? = volumes.entries.firstOrNull { chapter in it.value }?.key

    companion object {
        /**
         * The volumes and chapters in a Wikipedia article's wikitext, or null if it has no
         * volume list. Chapter lines look like `*1. {{Nihongo|"Title"|…}}`, `* 001. "Title"`,
         * `*Chapter 12: …` or `<li value="5">`; a list without numbers continues from the
         * previous volume. Chapters after the last volume ("not yet in tankōbon format") are loose.
         */
        fun fromWikitext(text: String): Pair<Map<Int, List<Float>>, List<Float>>? {
            val marker = "{{Graphic novel list"
            val starts = Regex("""\{\{\s*Graphic novel list\s*\|""", RegexOption.IGNORE_CASE).findAll(text).map { it.range.first }.toList()
            if (starts.isEmpty()) return null
            val volumes = LinkedHashMap<Int, List<Float>>()
            var last = 0f
            var end = 0
            for ((i, s) in starts.withIndex()) {
                val blockEnd = blockEnd(text, s)
                end = maxOf(end, blockEnd)
                val block = text.substring(s, minOf(blockEnd, starts.getOrElse(i + 1) { text.length }.coerceAtLeast(blockEnd)))
                val vol = Regex("""\|\s*VolumeNumber\s*=\s*(\d{1,3})""").find(block)?.groupValues?.get(1)?.toIntOrNull() ?: continue
                val chapters = chaptersIn(block, after = last)
                if (chapters.isNotEmpty()) {
                    volumes[vol] = chapters
                    last = chapters.max()
                } else if (vol !in volumes) {
                    volumes[vol] = emptyList()
                }
            }
            if (volumes.isEmpty()) return null
            // After the last volume table: chapters not in a volume yet (up to the next section
            // of the same or a higher level, e.g. "==Reception==").
            val tail = text.substring(end.coerceAtMost(text.length))
            val tailEnd = Regex("""\n==[^=]""").find(tail)?.range?.first ?: tail.length
            val loose = numbered(tail.substring(0, tailEnd)).filter { it > last }
            return volumes to loose.distinct().sorted()
        }

        /** Index just after the template starting at [start] (matching {{ … }} nesting). */
        private fun blockEnd(text: String, start: Int): Int {
            var depth = 0
            var i = start
            while (i < text.length - 1) {
                if (text[i] == '{' && text[i + 1] == '{') { depth++; i += 2; continue }
                if (text[i] == '}' && text[i + 1] == '}') {
                    depth--
                    i += 2
                    if (depth == 0) return i
                    continue
                }
                i++
            }
            return text.length
        }

        private val numberedLine = Regex("""(?m)^\s*[*#:]+\s*(?:Chapter|Ch\.|Episode|Act|Vol\.\s*\d+\s*Ch\.)?\s*0*(\d{1,4}(?:\.\d{1,2})?)\s*[.:)\-–—]""", RegexOption.IGNORE_CASE)
        private val liValue = Regex("""<li\s+value\s*=\s*"?(\d{1,4})""", RegexOption.IGNORE_CASE)

        private fun numbered(s: String): List<Float> =
            (numberedLine.findAll(s).mapNotNull { it.groupValues[1].toFloatOrNull() } +
                liValue.findAll(s).mapNotNull { it.groupValues[1].toFloatOrNull() } +
                numberedListTemplates(s)).toList()

        /**
         * `{{Numbered list|start=5|{{Nihongo|…}}|{{Nihongo|…}}}}` (the "List of … chapters"
         * pages): chapters start, start+1, … one per item.
         */
        private fun numberedListTemplates(s: String): List<Float> {
            val out = ArrayList<Float>()
            Regex("""\{\{\s*Numbered list\s*\|""", RegexOption.IGNORE_CASE).findAll(s).forEach { m ->
                val end = blockEnd(s, m.range.first)
                val body = s.substring(m.range.last + 1, (end - 2).coerceAtLeast(m.range.last + 1))
                // Split the template's parameters at its own level (not inside nested {{ }} / [[ ]]).
                val params = ArrayList<String>()
                var depth = 0
                val cur = StringBuilder()
                var i = 0
                while (i < body.length) {
                    val two = if (i + 1 < body.length) body.substring(i, i + 2) else ""
                    when {
                        two == "{{" || two == "[[" -> { depth++; cur.append(two); i += 2; continue }
                        two == "}}" || two == "]]" -> { depth--; cur.append(two); i += 2; continue }
                        body[i] == '|' && depth == 0 -> { params += cur.toString(); cur.clear() }
                        else -> cur.append(body[i])
                    }
                    i++
                }
                params += cur.toString()
                var start = 1
                var count = 0
                params.forEach { p ->
                    val t = p.trim()
                    val named = Regex("""^([a-z_]+)\s*=\s*(.*)$""", RegexOption.IGNORE_CASE).find(t)
                    when {
                        named != null && named.groupValues[1].equals("start", ignoreCase = true) -> start = named.groupValues[2].trim().toIntOrNull() ?: 1
                        named != null -> Unit // list_style= etc.
                        t.isNotEmpty() -> count++
                    }
                }
                repeat(count) { out += (start + it).toFloat() }
            }
            return out
        }

        private fun chaptersIn(block: String, after: Float): List<Float> {
            val list = block.substringAfter("ChapterList", "")
            if (list.isEmpty()) return emptyList()
            val nums = numbered(list).distinct().sorted()
            if (nums.isNotEmpty()) return nums
            // "# Title" lines without numbers: they continue from the previous volume.
            val count = Regex("""(?m)^\s*#\s*\S""").findAll(list).count()
            return (1..count).map { after.toInt() + it.toFloat() }
        }

        fun status(aniList: String?): Status? = when (aniList?.uppercase()) {
            "RELEASING", "NOT_YET_RELEASED" -> Status.ONGOING
            "FINISHED" -> Status.COMPLETED
            "HIATUS" -> Status.HIATUS
            "CANCELLED" -> Status.CANCELLED
            else -> null
        }
    }
}
