package dev.thorreader.app.convert

import android.util.Log
import dev.thorreader.app.TAG
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.Charset

/** Shown to the reader as it is. */
class DrmProtectedException : IOException("This book is DRM-protected.\nThor Reader can only open DRM-free books.")

/**
 * Kindle books: MOBI (old format, "MOBI 6"), AZW (MOBI 6 with Amazon extras) and AZW3 / KF8.
 *
 * All are Palm database files: record 0 is a header, then compressed text records, then
 * pictures. KF8 stores its text as "skeleton" files with "fragments" to insert into them,
 * described by index (INDX) records; we reassemble the original XHTML parts, pictures and
 * style sheets into a [ConvBook]. Written from the public format descriptions (MobileRead wiki).
 */
class MobiFile(private val data: ByteArray) {

    private val recordCount: Int
    private val offsets: IntArray

    init {
        if (data.size < 86) throw IOException("Not a Kindle book (file too short)")
        val type = String(data, 60, 8, Charsets.ISO_8859_1)
        if (type != "BOOKMOBI" && type != "TEXtREAd") throw IOException("Not a Kindle book ($type)")
        recordCount = u16(data, 76)
        offsets = IntArray(recordCount) { u32(data, 78 + it * 8).toInt() }
    }

    fun record(i: Int): ByteArray {
        if (i !in 0 until recordCount) throw IOException("Missing record $i")
        val start = offsets[i]
        val end = if (i + 1 < recordCount) offsets[i + 1] else data.size
        if (start < 0 || end > data.size || end < start) throw IOException("Broken record $i")
        return data.copyOfRange(start, end)
    }

    /** The first (MOBI 6, or the only KF8) header. */
    val first = Header(this, 0)

    /** Encrypted with Amazon/Mobipocket DRM? */
    val drm: Boolean get() = first.encryption != 0

    /** The KF8 part: the whole book (AZW3) or the second half of a combined MOBI/KF8 file. */
    val kf8: Header? by lazy {
        if (first.version >= 8) return@lazy first
        val boundary = first.exth[121]?.firstOrNull()?.let { u32(it, 0).toInt() } ?: return@lazy null
        for (i in listOf(boundary, boundary + 1)) {
            runCatching { Header(this, i) }.getOrNull()?.takeIf { it.isMobi && it.version >= 8 }?.let { return@lazy it }
        }
        null
    }

    class Header(private val f: MobiFile, val start: Int) {
        private val r = f.record(start)
        val compression = u16(r, 0)
        val textLength = u32(r, 4).toInt()
        val textRecords = u16(r, 8)
        val encryption = u16(r, 12)
        val isMobi = r.size >= 24 && String(r, 16, 4, Charsets.ISO_8859_1) == "MOBI"
        private val headerLen = if (isMobi) u32(r, 20).toInt() else 0
        private val encoding = if (isMobi) u32(r, 28).toInt() else 1252
        val version = if (isMobi && r.size >= 40) u32(r, 36).toInt() else 0
        val charset: Charset = if (encoding == 65001) Charsets.UTF_8 else Charset.forName("windows-1252")

        private fun field(off: Int): Long = if (isMobi && r.size >= off + 4 && off < 16 + headerLen) u32(r, off) else NONE

        /** Record index of a field that counts from this header (KF8) or the file start (MOBI 6). */
        private fun index(off: Int): Int {
            val v = field(off)
            return if (v == NONE) -1 else (v + if (version >= 8) start else 0).toInt()
        }

        val firstResource = index(0x6C)
        private val huffRecord = field(0x70)
        private val huffCount = field(0x74).toInt()
        private val extraFlags = if (isMobi && headerLen >= 0xE4 && version >= 5 && r.size >= 0xF4) u16(r, 0xF2) else 0
        val fdst = if (version >= 8) index(0xC0) else -1
        val ncx = index(0xF4)
        val fragmentIndex = if (version >= 8) index(0xF8) else -1
        val skeletonIndex = if (version >= 8) index(0xFC) else -1

        /** EXTH metadata: type -> values. */
        val exth: Map<Int, List<ByteArray>> = runCatching {
            val out = HashMap<Int, MutableList<ByteArray>>()
            if (field(0x80) != NONE && (field(0x80) and 0x40L) != 0L) {
                val p = 16 + headerLen
                if (String(r, p, 4, Charsets.ISO_8859_1) == "EXTH") {
                    val n = u32(r, p + 8).toInt()
                    var q = p + 12
                    repeat(n) {
                        val type = u32(r, q).toInt()
                        val len = u32(r, q + 4).toInt()
                        if (len < 8 || q + len > r.size) return@repeat
                        out.getOrPut(type) { ArrayList() } += r.copyOfRange(q + 8, q + len)
                        q += len
                    }
                }
            }
            out as Map<Int, List<ByteArray>>
        }.getOrDefault(emptyMap())

        fun exthText(type: Int): List<String> = exth[type].orEmpty().map { String(it, charset).trim() }.filter { it.isNotEmpty() }

        val fullName: String? = runCatching {
            val off = field(0x54).toInt()
            val len = field(0x58).toInt()
            if (off > 0 && len > 0 && off + len <= r.size) String(r, off, len, charset).trim().ifEmpty { null } else null
        }.getOrNull()

        /** All text, decompressed (MOBI 6: the HTML; KF8: all flows back to back). */
        fun text(): ByteArray {
            val huff = if (compression == HUFF) Huffcdic(f, huffRecord, huffCount, start) else null
            if (compression != NO_COMPRESSION && compression != PALMDOC && huff == null) throw IOException("Unknown compression $compression")
            val out = ByteArrayOutputStream(textLength.coerceIn(0, 64 shl 20))
            for (i in 1..textRecords) {
                val rec = f.record(start + i)
                val size = rec.size - trailingSize(rec, extraFlags)
                val raw = rec.copyOfRange(0, size.coerceIn(0, rec.size))
                out.write(
                    when (compression) {
                        PALMDOC -> palmDoc(raw)
                        HUFF -> huff!!.unpack(raw)
                        else -> raw
                    },
                )
            }
            val all = out.toByteArray()
            return if (textLength in 1 until all.size) all.copyOf(textLength) else all
        }
    }

    // ---- MOBI 6 -> EPUB -----------------------------------------------------------------

    /** Old-style MOBI HTML, with <img recindex="00001"> pictures and <mbp:pagebreak/>. */
    fun mobi6Book(): ConvBook {
        val h = first
        val book = baseBook(h)
        val html = String(h.text(), h.charset)
        val body = Regex("<body[^>]*>(.*)</body>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)?.groupValues?.get(1) ?: html
        val images = HashMap<Int, String>()
        val withImages = Regex("recindex=[\"']?(\\d+)[\"']?", RegexOption.IGNORE_CASE).replace(body) { m ->
            val n = m.groupValues[1].toInt()
            val path = images.getOrPut(n) { resource(h, n - 1)?.let { book.addImage(it) } ?: "" }
            if (path.isEmpty()) "" else "src=\"../$path\""
        }.replace(Regex("filepos=[\"']?\\d+[\"']?", RegexOption.IGNORE_CASE), "")
        val parts = withImages.split(Regex("<mbp:pagebreak\\s*/?>", RegexOption.IGNORE_CASE))
        parts.forEach { part ->
            val clean = part.replace(Regex("</?mbp:[^>]*>", RegexOption.IGNORE_CASE), "")
            if (HeadingSplitter.plain(clean).isNotEmpty() || clean.contains("<img", true)) {
                book.addSection(firstHeading(clean), clean)
            }
        }
        cover(h, book)
        return book
    }

    // ---- KF8 -> EPUB --------------------------------------------------------------------

    fun kf8Book(): ConvBook {
        val h = kf8 ?: throw IOException("No KF8 part")
        val book = baseBook(h)
        val raw = h.text()
        val flows = flows(h, raw)
        val text = flows.firstOrNull() ?: raw

        val skeletons = Index.read(this, h.skeletonIndex).entries
        val fragTable = Index.read(this, h.fragmentIndex)
        val frags = fragTable.entries
        val parts = ArrayList<String>()
        if (skeletons.isEmpty()) {
            parts += String(text, h.charset)
        } else {
            var fragPtr = 0
            for (skel in skeletons) {
                val fragCount = skel.tags[1]?.firstOrNull()?.toInt() ?: 0
                val skelPos = skel.tags[6]?.getOrNull(0)?.toInt() ?: continue
                val skelLen = skel.tags[6]?.getOrNull(1)?.toInt() ?: continue
                var basePtr = skelPos + skelLen
                var skeleton = text.copyOfRange(skelPos.coerceIn(0, text.size), basePtr.coerceIn(0, text.size))
                repeat(fragCount) {
                    val fr = frags.getOrNull(fragPtr++) ?: return@repeat
                    val insertPos = (fr.name.trim().toIntOrNull() ?: return@repeat) - skelPos
                    val len = fr.tags[6]?.getOrNull(1)?.toInt() ?: 0
                    val slice = text.copyOfRange(basePtr.coerceIn(0, text.size), (basePtr + len).coerceIn(0, text.size))
                    val at = insertPos.coerceIn(0, skeleton.size)
                    skeleton = skeleton.copyOfRange(0, at) + slice + skeleton.copyOfRange(at, skeleton.size)
                    basePtr += len
                }
                parts += String(skeleton, h.charset)
            }
        }

        // Chapter names from the KF8 table of contents (NCX index): fragment -> part.
        val partTitles = HashMap<Int, Pair<Int, String>>() // part -> (depth, title)
        runCatching {
            val fragToPart = IntArray(frags.size) { frags[it].tags[3]?.firstOrNull()?.toInt() ?: -1 }
            val ncx = Index.read(this, h.ncx)
            for (e in ncx.entries) {
                val label = e.tags[3]?.firstOrNull()?.toInt()?.let { ncx.cncx[it] } ?: continue
                val fid = e.tags[6]?.firstOrNull()?.toInt() ?: continue
                val part = fragToPart.getOrNull(fid) ?: continue
                val depth = e.tags[4]?.firstOrNull()?.toInt() ?: 0
                val prev = partTitles[part]
                if (prev == null || depth < prev.first) partTitles[part] = depth to label.trim()
            }
        }.onFailure { Log.w(TAG, "KF8 table of contents unreadable", it) }

        val images = HashMap<Int, String>()
        val styles = HashMap<Int, String>()
        fun embed(n: Int): String = images.getOrPut(n) { resource(h, n - 1)?.let { book.addImage(it) } ?: "" }
        fun flow(n: Int, mime: String): String = styles.getOrPut(n) {
            val bytes = flows.getOrNull(n) ?: return@getOrPut ""
            if (!mime.contains("css")) return@getOrPut ""
            val path = "styles/flow$n.css"
            book.styles[path] = fixUrls(String(bytes, h.charset), ::embed, prefix = "../")
            path
        }

        parts.forEachIndexed { i, part ->
            var x = part
            // Style sheets and pictures stored as separate flows / resource records.
            x = Regex("kindle:flow:([0-9A-Va-v]{4})\\?mime=([a-zA-Z/+-]+)").replace(x) { m ->
                val p = flow(base32(m.groupValues[1]), m.groupValues[2])
                if (p.isEmpty()) "" else "../$p"
            }
            x = fixUrls(x, ::embed, prefix = "../")
            // Internal Kindle links can't be followed here: drop the targets, keep the text.
            x = Regex("href=\"kindle:pos:[^\"]*\"").replace(x, "href=\"#\"")
            x = x.replace(Regex("<link[^>]*href=\"\"[^>]*/?>"), "")
            val title = partTitles[i]?.second ?: firstHeading(x)
            book.sections += ConvBook.Section(title, x, fullDocument = true)
        }
        cover(h, book)
        return book
    }

    private fun fixUrls(s: String, embed: (Int) -> String, prefix: String): String =
        Regex("kindle:embed:([0-9A-Va-v]{4})(\\?mime=[a-zA-Z/+-]+)?").replace(s) { m ->
            val p = embed(base32(m.groupValues[1]))
            if (p.isEmpty()) "" else prefix + p
        }

    /** KF8 text flows (FDST): flow 0 is the HTML, later ones style sheets / SVG. */
    private fun flows(h: Header, raw: ByteArray): List<ByteArray> = runCatching {
        if (h.fdst < 0) return listOf(raw)
        val r = record(h.fdst)
        if (String(r, 0, 4, Charsets.ISO_8859_1) != "FDST") return listOf(raw)
        val off = u32(r, 4).toInt()
        val n = u32(r, 8).toInt()
        List(n) { i ->
            val s = u32(r, off + i * 8).toInt().coerceIn(0, raw.size)
            val e = u32(r, off + i * 8 + 4).toInt().coerceIn(s, raw.size)
            raw.copyOfRange(s, e)
        }
    }.getOrDefault(listOf(raw))

    private fun baseBook(h: Header): ConvBook = ConvBook().apply {
        title = h.exthText(503).firstOrNull() ?: h.fullName ?: first.exthText(503).firstOrNull() ?: first.fullName
        authors += (h.exthText(100).ifEmpty { first.exthText(100) }).distinct()
        subjects += (h.exthText(105).ifEmpty { first.exthText(105) }).distinct()
        language = h.exthText(524).firstOrNull()
    }

    private fun cover(h: Header, book: ConvBook) {
        val idx = (h.exth[201] ?: first.exth[201])?.firstOrNull()?.let { u32(it, 0).toInt() } ?: return
        val bytes = resource(h, idx) ?: return
        book.coverPath = book.resources.entries.firstOrNull { it.value.contentEquals(bytes) }?.key ?: book.addImage(bytes)
    }

    /** Resource [n] (0-based, counted from the first resource record), if it is a picture. */
    private fun resource(h: Header, n: Int): ByteArray? {
        val first = if (h.firstResource >= 0) h.firstResource else this.first.firstResource
        if (first < 0) return null
        return runCatching { record(first + n) }.getOrNull()?.takeIf { imageExt(it) != null }
    }

    private fun firstHeading(html: String): String? =
        Regex("<h[1-3][^>]*>(.*?)</h[1-3]>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)?.groupValues?.get(1)?.let(HeadingSplitter::plain)?.takeIf { it.isNotEmpty() && it.length < 120 }

    // ---- index records (INDX + TAGX + CNCX) --------------------------------------------

    class Entry(val name: String, val tags: Map<Int, List<Long>>)
    class Index(val entries: List<Entry>, val cncx: Map<Int, String>) {
        companion object {
            fun read(f: MobiFile, idx: Int): Index {
                if (idx < 0) return Index(emptyList(), emptyMap())
                val main = f.record(idx)
                if (String(main, 0, 4, Charsets.ISO_8859_1) != "INDX") return Index(emptyList(), emptyMap())
                val headerLen = u32(main, 4).toInt()
                val count = u32(main, 24).toInt()
                val cncxCount = u32(main, 52).toInt()
                // Tag table (TAGX) right after the header of the main record.
                val tags = ArrayList<IntArray>()
                var controlBytes = 0
                if (String(main, headerLen, 4, Charsets.ISO_8859_1) == "TAGX") {
                    val end = u32(main, headerLen + 4).toInt()
                    controlBytes = u32(main, headerLen + 8).toInt()
                    var i = 12
                    while (i < end) {
                        val p = headerLen + i
                        tags += intArrayOf(main[p].u(), main[p + 1].u(), main[p + 2].u(), main[p + 3].u())
                        i += 4
                    }
                }
                // Label strings (CNCX records follow the index records).
                val cncx = HashMap<Int, String>()
                for (j in 0 until cncxCount) {
                    val c = f.record(idx + count + 1 + j)
                    var off = 0
                    while (off < c.size && c[off].toInt() != 0) {
                        val start = off
                        val (used, len) = vwi(c, off)
                        off += used
                        val end = (off + len.toInt()).coerceAtMost(c.size)
                        cncx[start + j * 0x10000] = String(c, off, end - off, Charsets.UTF_8)
                        off = end
                    }
                }
                val entries = ArrayList<Entry>()
                for (i in idx + 1..idx + count) {
                    val d = f.record(i)
                    if (String(d, 0, 4, Charsets.ISO_8859_1) != "INDX") continue
                    val idxt = u32(d, 20).toInt()
                    val n = u32(d, 24).toInt()
                    val pos = IntArray(n + 1) { j -> if (j < n) u16(d, idxt + 4 + 2 * j) else idxt }
                    for (j in 0 until n) {
                        val s = pos[j]
                        val textLen = d[s].u()
                        val name = String(d, s + 1, textLen, Charsets.ISO_8859_1)
                        entries += Entry(name, tagMap(controlBytes, tags, d, s + 1 + textLen, pos[j + 1]))
                    }
                }
                return Index(entries, cncx)
            }

            private fun tagMap(controlBytes: Int, table: List<IntArray>, d: ByteArray, start: Int, end: Int): Map<Int, List<Long>> {
                class T(val tag: Int, val count: Int?, val bytes: Long?, val perEntry: Int)
                val found = ArrayList<T>()
                var cbIndex = 0
                var dataStart = start + controlBytes
                for ((tag, perEntry, mask0, endFlag) in table.map { listOf(it[0], it[1], it[2], it[3]) }) {
                    if (endFlag == 1) {
                        cbIndex++
                        continue
                    }
                    val cb = d[start + cbIndex].u()
                    var mask = mask0
                    var value = cb and mask
                    if (value == 0) continue
                    if (value == mask) {
                        if (Integer.bitCount(mask) > 1) {
                            val (used, v) = vwi(d, dataStart)
                            dataStart += used
                            found += T(tag, null, v, perEntry)
                        } else {
                            found += T(tag, 1, null, perEntry)
                        }
                    } else {
                        while (mask and 1 == 0) {
                            mask = mask shr 1
                            value = value shr 1
                        }
                        found += T(tag, value, null, perEntry)
                    }
                }
                val out = HashMap<Int, List<Long>>()
                for (t in found) {
                    val values = ArrayList<Long>()
                    if (t.count != null) {
                        repeat(t.count * t.perEntry) {
                            if (dataStart >= end) return@repeat
                            val (used, v) = vwi(d, dataStart)
                            dataStart += used
                            values += v
                        }
                    } else {
                        var consumed = 0L
                        while (consumed < t.bytes!! && dataStart < end) {
                            val (used, v) = vwi(d, dataStart)
                            dataStart += used
                            consumed += used
                            values += v
                        }
                    }
                    out[t.tag] = values
                }
                return out
            }
        }
    }

    companion object {
        const val NO_COMPRESSION = 1
        const val PALMDOC = 2
        const val HUFF = 17480
        private const val NONE = 0xFFFFFFFFL

        /** Quick check used on import: is this Kindle file DRM-protected? */
        fun isDrmProtected(head: ByteArray): Boolean = runCatching {
            if (head.size < 86 || String(head, 60, 8, Charsets.ISO_8859_1) != "BOOKMOBI") return false
            val r0 = u32(head, 78).toInt()
            r0 + 14 <= head.size && u16(head, r0 + 12) != 0
        }.getOrDefault(false)

        /** Bytes a record's trailing entries take (they are not part of the text). */
        fun trailingSize(rec: ByteArray, flags: Int): Int {
            var num = 0
            var size = rec.size
            var f = flags shr 1
            while (f != 0) {
                if (f and 1 != 0) num += trailingEntry(rec, size - num)
                f = f shr 1
            }
            if (flags and 1 != 0 && size - num - 1 >= 0) num += (rec[size - num - 1].toInt() and 0x3) + 1
            return num.coerceAtMost(size)
        }

        private fun trailingEntry(rec: ByteArray, end: Int): Int {
            var bitPos = 0
            var result = 0
            var size = end
            while (size > 0) {
                val v = rec[size - 1].toInt() and 0xFF
                result = result or ((v and 0x7F) shl bitPos)
                bitPos += 7
                size--
                if (v and 0x80 != 0 || bitPos >= 28) break
            }
            return result
        }

        /** PalmDOC (LZ77-style) decompression. */
        fun palmDoc(src: ByteArray): ByteArray {
            var o = ByteArray(src.size * 8 + 16)
            var n = 0
            fun put(b: Int) {
                if (n == o.size) o = o.copyOf(o.size * 2)
                o[n++] = b.toByte()
            }
            var i = 0
            while (i < src.size) {
                val c = src[i++].toInt() and 0xFF
                when {
                    c in 1..8 -> repeat(c) { if (i < src.size) put(src[i++].toInt()) }
                    c < 0x80 -> put(c)
                    c >= 0xC0 -> {
                        put(' '.code)
                        put(c xor 0x80)
                    }
                    i < src.size -> {
                        val pair = (c shl 8) or (src[i++].toInt() and 0xFF)
                        val dist = (pair shr 3) and 0x7FF
                        val len = (pair and 7) + 3
                        if (dist in 1..n) repeat(len) { put(o[n - dist].toInt()) }
                    }
                }
            }
            return o.copyOf(n)
        }

        /** Forward variable-width integer (high bit marks the last byte): (bytes used, value). */
        fun vwi(d: ByteArray, off: Int): Pair<Int, Long> {
            var value = 0L
            var used = 0
            while (off + used < d.size) {
                val v = d[off + used].toInt() and 0xFF
                used++
                value = (value shl 7) or (v and 0x7F).toLong()
                if (v and 0x80 != 0) break
            }
            return used to value
        }

        fun base32(s: String): Int = s.uppercase().fold(0) { acc, c -> acc * 32 + if (c.isDigit()) c - '0' else c - 'A' + 10 }

        fun u16(b: ByteArray, off: Int): Int = ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)
        fun u32(b: ByteArray, off: Int): Long =
            ((b[off].toLong() and 0xFF) shl 24) or ((b[off + 1].toLong() and 0xFF) shl 16) or
                ((b[off + 2].toLong() and 0xFF) shl 8) or (b[off + 3].toLong() and 0xFF)

        private fun Byte.u() = toInt() and 0xFF
    }
}

/** HUFF/CDIC text compression (used by some Kindle files instead of PalmDOC). */
private class Huffcdic(f: MobiFile, huffRecord: Long, huffCount: Int, start: Int) {
    private val dict1 = IntArray(256)
    private val dict1Term = BooleanArray(256)
    private val dict1Max = LongArray(256)
    private val minCode = LongArray(33)
    private val maxCode = LongArray(33)
    private val dictionary = ArrayList<ByteArray?>()
    private val dictFlags = ArrayList<Boolean>()

    init {
        // The record number counts from the KF8 header in some files and from the file start in others.
        var first = huffRecord.toInt()
        if (String(f.record(first), 0, 4, Charsets.ISO_8859_1) != "HUFF") first += start
        val huff = f.record(first)
        if (String(huff, 0, 4, Charsets.ISO_8859_1) != "HUFF") throw IOException("Bad HUFF record")
        val off1 = MobiFile.u32(huff, 8).toInt()
        val off2 = MobiFile.u32(huff, 12).toInt()
        for (i in 0 until 256) {
            val v = MobiFile.u32(huff, off1 + i * 4)
            val codeLen = (v and 0x1F).toInt()
            dict1[i] = codeLen
            dict1Term[i] = (v and 0x80) != 0L
            dict1Max[i] = (((v shr 8) + 1) shl (32 - codeLen)) - 1
        }
        for (len in 1..32) {
            val mn = MobiFile.u32(huff, off2 + (len - 1) * 8)
            val mx = MobiFile.u32(huff, off2 + (len - 1) * 8 + 4)
            minCode[len] = mn shl (32 - len)
            maxCode[len] = ((mx + 1) shl (32 - len)) - 1
        }
        for (i in 1 until huffCount) {
            val c = f.record(first + i)
            if (String(c, 0, 4, Charsets.ISO_8859_1) != "CDIC") break
            val phrases = MobiFile.u32(c, 8).toInt()
            val bits = MobiFile.u32(c, 12).toInt()
            val n = minOf(1 shl bits, phrases - dictionary.size)
            for (j in 0 until n) {
                val off = MobiFile.u16(c, 16 + j * 2)
                val blen = MobiFile.u16(c, 16 + off)
                val s = 18 + off
                dictionary += c.copyOfRange(s, (s + (blen and 0x7FFF)).coerceAtMost(c.size))
                dictFlags += (blen and 0x8000) != 0
            }
        }
    }

    fun unpack(src: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(src.size * 3)
        var bitsLeft = src.size.toLong() * 8
        val d = src + ByteArray(8)
        var pos = 0
        var x = readLong(d, pos)
        var n = 32
        while (true) {
            if (n <= 0) {
                pos += 4
                x = readLong(d, pos)
                n += 32
            }
            val code = (x ushr n) and 0xFFFFFFFFL
            val top = (code ushr 24).toInt()
            var codeLen = dict1[top]
            var max = dict1Max[top]
            if (!dict1Term[top]) {
                while (codeLen < 32 && code < minCode[codeLen]) codeLen++
                max = maxCode[codeLen]
            }
            n -= codeLen
            bitsLeft -= codeLen
            if (bitsLeft < 0 || codeLen == 0) break
            val r = ((max - code) ushr (32 - codeLen)).toInt()
            if (r !in dictionary.indices) break
            var slice = dictionary[r] ?: break
            if (!dictFlags[r]) {
                dictionary[r] = null // guards against loops
                slice = unpack(slice)
                dictionary[r] = slice
                dictFlags[r] = true
            }
            out.write(slice)
        }
        return out.toByteArray()
    }

    private fun readLong(d: ByteArray, p: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (if (p + i < d.size) d[p + i].toLong() and 0xFF else 0L)
        return v
    }
}
