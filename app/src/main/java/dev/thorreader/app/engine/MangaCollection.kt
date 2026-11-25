package dev.thorreader.app.engine

/**
 * A manga series as a collection, from chapter 1 to the last one: the volumes and chapters the
 * user has (a volume stands in for the chapters it holds), the stretches they don't have
 * ("Vol. 3–11 · Ch. 9–46"), and the rest of the series. Pure (unit-tested).
 */
object MangaCollection {
    /** One book of the series in the library. */
    data class Owned(val bookId: Long, val unit: MangaUnits.Unit)

    sealed interface Row

    /** A volume the user has; [chapters] = what it holds, if known. */
    data class VolumeRow(val volume: Int, val chapters: List<Float>?, val bookId: Long) : Row

    /** A single chapter the user has. */
    data class ChapterRow(val chapter: Float, val volume: Int?, val bookId: Long) : Row

    /**
     * Chapters the user doesn't have: [from]..[to] ([from] null = "the chapters before",
     * unknown), [count] of them if known, [volumes] wholly inside, [tail] = the rest of the series.
     */
    data class GapRow(val from: Float?, val to: Float?, val count: Int?, val volumes: IntRange?, val tail: Boolean) : Row

    /** A book of a (non-manga) book series the user has: its [number] in the series (null = unknown). */
    data class BookRow(val number: Int?, val title: String, val bookId: Long) : Row

    /** Books [from]..[to] of a book series the user doesn't have; [title] when it's one known book. */
    data class MissingBooksRow(val from: Int, val to: Int, val title: String?) : Row

    data class Collection(
        val rows: List<Row>,
        val volumes: Int,
        val chapters: Int,
        /** Last chapter of the series (known for completed ones), or the newest known. */
        val lastChapter: Float?,
        val status: MangaSeriesMap.Status?,
        /** A book series (Dune 1-6), not manga: [books] owned, of [seriesBooks] (if known). */
        val isBooks: Boolean = false,
        val books: Int = 0,
        val seriesBooks: Int? = null,
    ) {
        /** Volumes and loose chapters both: the cover flow is the default view then. */
        val mixed: Boolean get() = volumes > 0 && chapters > 0
    }

    fun build(owned: List<Owned>, map: MangaSeriesMap?): Collection {
        val vols = owned.filter { it.unit.kind == MangaUnits.Kind.VOLUME && it.unit.volume != null }
            .groupBy { it.unit.volume!! }.mapValues { it.value.first() }
        val chs = owned.filter { it.unit.kind == MangaUnits.Kind.CHAPTER && it.unit.chapter != null }
            .groupBy { it.unit.chapter!! }.mapValues { it.value.first() }
        val known = map?.volumes?.filterValues { it.isNotEmpty() }.orEmpty()
        val rows = if (known.isNotEmpty()) withMap(vols, chs, map!!) else withoutMap(vols, chs, map)
        return Collection(rows, vols.size, chs.size, map?.lastChapter ?: chs.keys.maxOrNull(), map?.status)
    }

    private fun withMap(vols: Map<Int, Owned>, chs: Map<Float, Owned>, map: MangaSeriesMap): List<Row> {
        val coveredBy = HashMap<Float, Int>()
        vols.keys.forEach { v -> map.volumes[v]?.forEach { coveredBy[it] = v } }
        val total = map.totalChapters?.takeIf { map.status == MangaSeriesMap.Status.COMPLETED }
        val universe = (map.chapters + chs.keys + (total?.let { t -> (1..t).map { it.toFloat() } } ?: emptyList()))
            .filter { it > 0f }.distinct().sorted()
        // Rows keyed by reading position; volumes whose chapters aren't listed go after the previous volume.
        val keyed = ArrayList<Pair<Float, Row>>()
        val emitted = HashSet<Int>()
        val missing = ArrayList<Float>()
        fun flushGap(tail: Boolean) {
            if (missing.isEmpty()) return
            val inGap = missing.toHashSet()
            val whole = map.volumes.filter { (v, c) -> c.isNotEmpty() && v !in vols && c.all { it in inGap } }.keys.sorted()
            keyed += missing.first() to GapRow(missing.first(), missing.last(), missing.size, whole.takeIf { it.isNotEmpty() }?.let { it.first()..it.last() }, tail)
            missing.clear()
        }
        for (ch in universe) {
            val v = coveredBy[ch]
            when {
                v != null -> {
                    flushGap(false)
                    if (emitted.add(v)) keyed += ch to VolumeRow(v, map.volumes[v], vols.getValue(v).bookId)
                }
                ch in chs -> {
                    flushGap(false)
                    keyed += ch to ChapterRow(ch, map.volumeOf(ch), chs.getValue(ch).bookId)
                }
                else -> missing += ch
            }
        }
        flushGap(true)
        // Volumes the user has whose chapters aren't on the list (e.g. just released).
        vols.keys.filter { it !in emitted }.sorted().forEach { v ->
            val before = map.volumes.filterKeys { it < v }.values.flatten().maxOrNull() ?: 0f
            keyed += (before + 0.001f * v) to VolumeRow(v, null, vols.getValue(v).bookId)
        }
        val rows = keyed.sortedBy { it.first }.map { it.second }.toMutableList()
        // Only the last gap (after the last thing owned) is "the rest of the series".
        for (i in rows.indices) {
            val r = rows[i]
            if (r is GapRow) rows[i] = r.copy(tail = rows.subList(i + 1, rows.size).none { it !is GapRow })
        }
        return rows
    }

    private fun withoutMap(vols: Map<Int, Owned>, chs: Map<Float, Owned>, map: MangaSeriesMap?): List<Row> {
        val rows = ArrayList<Row>()
        vols.keys.sorted().forEach { rows += VolumeRow(it, null, vols.getValue(it).bookId) }
        val sorted = chs.keys.sorted()
        if (sorted.isNotEmpty()) {
            val first = sorted.first()
            if (first > 1f) {
                // Unknown which chapters the volumes hold: "the chapters before" when there are volumes.
                rows += if (vols.isEmpty()) GapRow(1f, first - 1f, (first - 1f).toInt(), null, false) else GapRow(null, first - 1f, null, null, false)
            }
        }
        var prev: Float? = null
        for (c in sorted) {
            prev?.let { p ->
                val gap = (c.toInt() - p.toInt() - 1)
                if (gap > 0) rows += GapRow(p.toInt() + 1f, c.toInt() - 1f, gap, null, false)
            }
            rows += ChapterRow(c, null, chs.getValue(c).bookId)
            prev = c
        }
        val last = map?.totalChapters?.toFloat()
        val upTo = prev
        if (last != null && upTo != null && last > upTo) rows += GapRow(upTo.toInt() + 1f, last, (last - upTo).toInt(), null, true)
        return rows
    }
}
