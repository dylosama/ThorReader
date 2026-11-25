package dev.thorreader.app.engine

import java.security.MessageDigest

/**
 * The pure parts of KOReader-compatible progress sync (used by CrossPoint on the Xteink X4,
 * and KOReader): the document id, and positions in KOReader's form. No Android types, so it is
 * unit-tested.
 *
 * Positions on the server:
 * - EPUB: "progress" is an XPointer, "/body/DocFragment[N]/body/<steps>/text().0", where N is the
 *   1-based spine item and the steps lead to an element (e.g. "div[2]/p[5]") inside that
 *   section's <body>; "percentage" is the book-wide fraction 0..1, measured in bytes of the
 *   sections (as CrossPoint computes it, so its fallback lands in the same place).
 * - Paged documents (PDF, comics): "progress" is the 1-based page number, "percentage" page / count.
 */
object KoSync {

    // ---- document id ---------------------------------------------------------------------------

    /**
     * KOReader's partial MD5 (util.partialMD5, also CrossPoint's binary id): one MD5 over
     * 1024-byte samples at offsets 0, 1024, 4096, 16384, ... (1024 << 2i for i = 0..10), stopping
     * at the end of the file. [read] returns up to len bytes at an offset (empty at / past the end).
     */
    fun partialMd5(size: Long, read: (offset: Long, len: Int) -> ByteArray): String {
        val md = MessageDigest.getInstance("MD5")
        for (i in -1..10) {
            val offset = if (i < 0) 0L else 1024L shl (2 * i)
            if (offset >= size) break
            val sample = read(offset, 1024)
            if (sample.isEmpty()) break
            md.update(sample)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** KOReader's "file name" id: MD5 of the file name (with its extension). */
    fun filenameMd5(fileName: String): String = md5(fileName.substringAfterLast('/'))

    /** The key the server stores: MD5 (hex) of the password. */
    fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    // ---- book-wide percentage (by section bytes, like CrossPoint) -------------------------------

    /** Book fraction of byte fraction [inSection] through spine item [spine], given each item's size. */
    fun bookFraction(sizes: List<Long>, spine: Int, inSection: Float): Float {
        val total = sizes.sum()
        if (total <= 0L || spine !in sizes.indices) return 0f
        val before = sizes.take(spine).sum()
        return ((before + sizes[spine] * inSection.coerceIn(0f, 1f)) / total.toDouble()).toFloat().coerceIn(0f, 1f)
    }

    /** Spine item and byte fraction within it for book fraction [pct] (the inverse of [bookFraction]). */
    fun sectionAt(sizes: List<Long>, pct: Float): Pair<Int, Float> {
        val total = sizes.sum()
        if (total <= 0L || sizes.isEmpty()) return 0 to 0f
        val target = total * pct.coerceIn(0f, 1f).toDouble()
        var before = 0L
        sizes.forEachIndexed { i, s ->
            if (target < before + s || i == sizes.lastIndex) {
                return i to (if (s > 0) ((target - before) / s).toFloat().coerceIn(0f, 1f) else 0f)
            }
            before += s
        }
        return sizes.lastIndex to 1f
    }

    // ---- XPointers -----------------------------------------------------------------------------

    /** Spine index (0-based) from "/body/DocFragment[N]...", or null. */
    fun docFragment(xpointer: String): Int? =
        Regex("""/body/DocFragment\[(\d+)]""").find(xpointer)?.groupValues?.get(1)?.toIntOrNull()?.minus(1)?.takeIf { it >= 0 }

    /** One element in a section, in document order: its path below <body> and where it starts. */
    data class Block(
        /** e.g. ["div[1]", "p[3]"]: element name and 1-based index among same-named siblings. */
        val steps: List<String>,
        /** Visible characters before this element's text, over the whole <body>. */
        val visibleBefore: Int,
        /** Character offset of the element in the section's HTML (for the byte fraction). */
        val rawOffset: Int,
    )

    /** A section's text map: elements that start with text, and the totals. */
    class Section(val blocks: List<Block>, val visibleTotal: Int, val rawLength: Int) {
        /** Fraction of the visible text before [b] (0..1): how far through the section it starts. */
        fun fractionOf(b: Block): Float = if (visibleTotal <= 0) 0f else (b.visibleBefore.toFloat() / visibleTotal).coerceIn(0f, 1f)
        fun rawFractionOf(b: Block): Float = if (rawLength <= 0) 0f else (b.rawOffset.toFloat() / rawLength).coerceIn(0f, 1f)
    }

    /**
     * Read a section's HTML into [Section]: every element inside <body> whose first content is
     * text (a paragraph, a heading...) with its path and position. A small tolerant tag scanner:
     * comments, CDATA, <head>, <script> and <style> are skipped; self-closing and void elements
     * (br, img...) take a sibling index but have no text.
     */
    fun scan(html: String): Section {
        class Frame(val name: String, val step: String, val start: Int) {
            val counts = HashMap<String, Int>()
            var sawChild = false // an element child came before any text
            var recorded = false
        }
        val stack = ArrayList<Frame>()
        val rootCounts = HashMap<String, Int>() // children of <body>
        var inBody = false
        var skipDepth = 0 // inside head / script / style
        var visible = 0
        val blocks = ArrayList<Block>()
        var i = 0
        val n = html.length
        fun text(from: Int, to: Int) {
            if (!inBody || skipDepth > 0 || from >= to) return
            val count = visibleCount(html.substring(from, to))
            if (count == 0) return
            val top = stack.lastOrNull()
            if (top != null && !top.recorded && !top.sawChild) {
                top.recorded = true
                blocks += Block(stack.map { it.step }, visible, top.start)
            }
            visible += count
        }
        var textFrom = 0
        while (i < n) {
            val lt = html.indexOf('<', i)
            if (lt < 0) {
                text(textFrom, n)
                break
            }
            text(textFrom, lt)
            when {
                html.startsWith("<!--", lt) -> {
                    val end = html.indexOf("-->", lt + 4)
                    i = if (end < 0) n else end + 3
                }
                html.startsWith("<![CDATA[", lt) -> {
                    val end = html.indexOf("]]>", lt + 9)
                    i = if (end < 0) n else end + 3
                }
                html.startsWith("<?", lt) || html.startsWith("<!", lt) -> {
                    val end = html.indexOf('>', lt + 2)
                    i = if (end < 0) n else end + 1
                }
                else -> {
                    val end = html.indexOf('>', lt + 1)
                    if (end < 0) {
                        i = n
                    } else {
                        val raw = html.substring(lt + 1, end).trim()
                        val closing = raw.startsWith("/")
                        val name = raw.removePrefix("/").takeWhile { !it.isWhitespace() && it != '/' }.substringAfter(':').lowercase()
                        val selfClosing = raw.endsWith("/") || name in VOID
                        if (name.isNotEmpty()) {
                            if (closing) {
                                when {
                                    name == "body" -> inBody = false
                                    name in SKIPPED -> if (skipDepth > 0) skipDepth--
                                    inBody && skipDepth == 0 -> {
                                        // Pop to the matching element (tolerates unclosed tags).
                                        val at = stack.indexOfLast { it.name == name }
                                        if (at >= 0) while (stack.size > at) stack.removeAt(stack.size - 1)
                                    }
                                }
                            } else when {
                                name == "body" -> {
                                    inBody = true
                                    stack.clear()
                                    rootCounts.clear()
                                }
                                name in SKIPPED -> if (!selfClosing) skipDepth++
                                inBody && skipDepth == 0 -> {
                                    val parent = stack.lastOrNull()
                                    val parentCounts = parent?.counts ?: rootCounts
                                    // An element before any text: the parent doesn't start with text.
                                    if (parent != null && !parent.recorded) parent.sawChild = true
                                    val k = (parentCounts[name] ?: 0) + 1
                                    parentCounts[name] = k
                                    if (!selfClosing) stack += Frame(name, "$name[$k]", lt)
                                }
                            }
                        }
                        i = end + 1
                    }
                }
            }
            textFrom = i
        }
        return Section(blocks, visible, n)
    }

    private val VOID = setOf("br", "img", "hr", "meta", "link", "input", "image", "col", "area", "source", "wbr", "embed", "param")
    private val SKIPPED = setOf("head", "script", "style", "title")

    private fun visibleCount(s: String): Int {
        var c = 0
        var j = 0
        while (j < s.length) {
            val ch = s[j]
            if (ch == '&') {
                val semi = s.indexOf(';', j)
                if (semi in (j + 1)..(j + 10)) {
                    c++
                    j = semi + 1
                    continue
                }
            }
            if (!ch.isWhitespace()) c++
            j++
        }
        return c
    }

    /**
     * The XPointer for a reading position [fraction] (0..1, of the visible text) through spine
     * item [spine] whose HTML is scanned as [section]: the last element starting at or before it.
     * Also returns that element's byte fraction through the section (for the percentage).
     */
    fun xpointerFor(section: Section, spine: Int, fraction: Float): Pair<String, Float> {
        val head = "/body/DocFragment[${spine + 1}]/body"
        val b = section.blocks.lastOrNull { section.fractionOf(it) <= fraction + 1e-4f } ?: section.blocks.firstOrNull()
            ?: return head to 0f
        return "$head/${b.steps.joinToString("/")}/text().0" to section.rawFractionOf(b)
    }

    /**
     * Where an XPointer points inside [section] (fraction of the visible text, 0..1), or null if
     * its element can't be found. A path that doesn't match exactly is shortened (its parent).
     */
    fun fractionFor(section: Section, xpointer: String): Float? {
        val path = xpointer.substringAfter("/body/DocFragment[", "").substringAfter("]/body", "")
        if (path.isEmpty()) return null
        // Element steps only: stop at "text()..." or an offset step like "img.0".
        var steps = path.split('/').filter { it.isNotEmpty() }
            .takeWhile { !it.startsWith("text()") && !it.contains('.') }
            .map { s ->
                val name = s.substringBefore('[').substringAfter(':').lowercase()
                val idx = s.substringAfter('[', "1]").substringBefore(']').toIntOrNull() ?: 1
                "$name[$idx]"
            }
        while (steps.isNotEmpty()) {
            // The element itself, or the first text-starting element inside it.
            val hit = section.blocks.firstOrNull { it.steps.size >= steps.size && it.steps.subList(0, steps.size) == steps }
            if (hit != null) return section.fractionOf(hit)
            steps = steps.dropLast(1)
        }
        return null
    }
}
