package dev.thorreader.app.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.thorreader.app.ReadingMode
import kotlinx.coroutines.flow.Flow

/**
 * One imported book. The file itself stays where the user keeps it ([uri], persisted SAF
 * permission); we only store metadata, a cached cover thumbnail and the reading position.
 */
@Entity(tableName = "books", indices = [Index(value = ["uri"], unique = true)])
data class Book(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uri: String,
    val fileName: String,
    /** [dev.thorreader.app.importer.BookFormat] name. */
    val format: String,
    val sizeBytes: Long,
    val title: String,
    val author: String? = null,
    val series: String? = null,
    val seriesNumber: Float? = null,
    /** Genres/subjects joined with "; ". */
    val genres: String? = null,
    val pageCount: Int = 0,
    /** Absolute path of the cached cover JPEG, or null. */
    val coverPath: String? = null,
    /** ComicInfo.xml said Manga = YesAndRightToLeft. */
    val mangaRtl: Boolean = false,
    /** User's per-book reading direction choice; null = automatic ([mangaRtl]). */
    val rtlOverride: Boolean? = null,
    /** Reading position: fixed formats = page index; reflowable = chapter index. */
    val posChapter: Int = 0,
    /** Reflowable: fraction (0..1) through the chapter. */
    val posFraction: Float = 0f,
    /** Overall progress 0..1 (for display). */
    val progress: Float = 0f,
    val addedAt: Long = System.currentTimeMillis(),
    val lastOpenedAt: Long = 0,
    /** This book's reading mode ([dev.thorreader.app.ReadingMode] name); null = not set yet. */
    val mode: String? = null,
    /** When the last page was reached (epoch ms); null = not completed. */
    val completedAt: Long? = null,
    /** Page count of the book's most recent full layout (0 = never fully laid out). */
    val layoutPages: Int = 0,
    /** This book's text settings; null = not set yet (takes the current ones on first open). */
    val fontTop: Float? = null,
    val fontBottom: Float? = null,
    val sameFont: Boolean? = null,
    val marginLevel: Int? = null,
    /** The bookmark (one per book) as a book location; null = none. */
    val bookmarkChapter: Int? = null,
    val bookmarkFraction: Float? = null,
    /** Page number shown at the reading position in the last-used layout (0 = unknown). */
    val lastPage: Int = 0,
    /** Long text about the book (never used as a genre). */
    val description: String? = null,
    /** ComicInfo or the online lookup says manga (Type view), independent of reading direction. */
    val isManga: Boolean = false,
    /** Country of origin from the lookup (JP, KR, CN, ...). */
    val country: String? = null,
    val publisher: String? = null,
    val year: Int? = null,
    val isbn: String? = null,
    val storyArc: String? = null,
    /** Type chosen by hand (Books / Manga / Comics); null = automatic. */
    val typeOverride: String? = null,
    /** Fields edited by hand, comma-separated (the lookup never overwrites them). */
    val editedFields: String? = null,
    /** Online lookup: null never, "pending", "done", "nomatch", "review", "retry", "off". */
    val lookupState: String? = null,
    /** The lookup found no confident match: shown under "Needs review". */
    val needsReview: Boolean = false,
    val lookupAt: Long = 0,
) {
    fun edited(field: String): Boolean = editedFields?.split(',')?.contains(field) == true

    val rtl: Boolean get() = rtlOverride ?: mangaRtl
    val readingMode: ReadingMode? get() = mode?.let { runCatching { ReadingMode.valueOf(it) }.getOrNull() }
    val completed: Boolean get() = completedAt != null

    /** Pages to show in the library: this layout's count if known, else the source's. */
    val displayPages: Int get() = if (layoutPages > 0) layoutPages else pageCount
}

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY lastOpenedAt DESC, title COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<Book>>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun get(id: Long): Book?

    @Query("SELECT uri FROM books")
    suspend fun allUris(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(book: Book): Long

    @Query(
        "UPDATE books SET posChapter = :chapter, posFraction = :fraction, progress = :progress, " +
            "lastOpenedAt = :time WHERE id = :id"
    )
    suspend fun savePosition(id: Long, chapter: Int, fraction: Float, progress: Float, time: Long)

    @Query("UPDATE books SET rtlOverride = :rtl WHERE id = :id")
    suspend fun setRtlOverride(id: Long, rtl: Boolean?)

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE books SET mode = :mode WHERE id = :id")
    suspend fun setMode(id: Long, mode: String)

    @Query("UPDATE books SET completedAt = :at WHERE id = :id")
    suspend fun setCompleted(id: Long, at: Long?)

    @Query("UPDATE books SET bookmarkChapter = :chapter, bookmarkFraction = :fraction WHERE id = :id")
    suspend fun setBookmark(id: Long, chapter: Int?, fraction: Float?)

    @Query("UPDATE books SET lastPage = :page WHERE id = :id")
    suspend fun setLastPage(id: Long, page: Int)

    @Query("UPDATE books SET layoutPages = :pages WHERE id = :id")
    suspend fun setLayoutPages(id: Long, pages: Int)

    @Query("UPDATE books SET fontTop = :top, fontBottom = :bottom, sameFont = :same, marginLevel = :margin WHERE id = :id")
    suspend fun setText(id: Long, top: Float, bottom: Float, same: Boolean, margin: Int)

    @Query("SELECT * FROM books")
    suspend fun all(): List<Book>

    @Query("SELECT id FROM books WHERE lookupState IS NULL OR lookupState IN ('pending', 'retry')")
    suspend fun idsNeedingLookup(): List<Long>

    @Query(
        "UPDATE books SET title = :title, author = :author, series = :series, seriesNumber = :number, genres = :genres, " +
            "description = :description, isManga = :isManga, mangaRtl = :mangaRtl, country = :country, publisher = :publisher, " +
            "year = :year, isbn = :isbn, storyArc = :storyArc, typeOverride = :typeOverride, editedFields = :editedFields, " +
            "lookupState = :lookupState, needsReview = :needsReview, lookupAt = :lookupAt, coverPath = :coverPath WHERE id = :id",
    )
    suspend fun updateMeta(
        id: Long, title: String, author: String?, series: String?, number: Float?, genres: String?, description: String?,
        isManga: Boolean, mangaRtl: Boolean, country: String?, publisher: String?, year: Int?, isbn: String?, storyArc: String?,
        typeOverride: String?, editedFields: String?, lookupState: String?, needsReview: Boolean, lookupAt: Long, coverPath: String?,
    )

    @Query("UPDATE books SET lookupState = :state, needsReview = :review, lookupAt = :at WHERE id = :id")
    suspend fun setLookupState(id: Long, state: String?, review: Boolean, at: Long)

    @Query("UPDATE books SET mangaRtl = 1 WHERE rtlOverride IS NULL AND series IS NOT NULL AND lower(trim(series)) = :seriesLower")
    suspend fun setSeriesAutoRtl(seriesLower: String)

    @Query("SELECT * FROM books WHERE series IS NOT NULL")
    suspend fun booksInAnySeries(): List<Book>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setSeriesDirection(d: SeriesDirection)

    @Query("SELECT rtl FROM series_direction WHERE seriesKey = :key")
    suspend fun seriesRtl(key: String): Boolean?
}

/**
 * Remembered reading direction per series, so volumes imported later get it too.
 * [seriesKey] = [seriesKey] of the series name.
 */
@Entity(tableName = "series_direction")
data class SeriesDirection(@PrimaryKey val seriesKey: String, val rtl: Boolean)

/** Series names compared case-insensitively and ignoring extra spaces. */
fun seriesKey(series: String?): String? =
    series?.trim()?.replace(Regex("\\s+"), " ")?.lowercase()?.takeIf { it.isNotEmpty() }

@Database(entities = [Book::class, SeriesDirection::class], version = 6, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun books(): BookDao

    companion object {
        /** v1 -> v2: add the per-series reading direction table. Existing books are kept. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `series_direction` (`seriesKey` TEXT NOT NULL, `rtl` INTEGER NOT NULL, PRIMARY KEY(`seriesKey`))"
                )
            }
        }

        /** v2 -> v3: each book remembers its own reading mode. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN mode TEXT")
            }
        }

        /** v3 -> v4 (v0.2.0): completion date, layout page count, per-book text settings. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN completedAt INTEGER")
                db.execSQL("ALTER TABLE books ADD COLUMN layoutPages INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE books ADD COLUMN fontTop REAL")
                db.execSQL("ALTER TABLE books ADD COLUMN fontBottom REAL")
                db.execSQL("ALTER TABLE books ADD COLUMN sameFont INTEGER")
                db.execSQL("ALTER TABLE books ADD COLUMN marginLevel INTEGER")
            }
        }

        /** v4 -> v5 (still v0.2.0): bookmark and the current page number for the library. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN bookmarkChapter INTEGER")
                db.execSQL("ALTER TABLE books ADD COLUMN bookmarkFraction REAL")
                db.execSQL("ALTER TABLE books ADD COLUMN lastPage INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v5 -> v6 (still v0.2.0): metadata fields for the online lookup and hand edits. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                listOf(
                    "description TEXT", "isManga INTEGER NOT NULL DEFAULT 0", "country TEXT", "publisher TEXT",
                    "year INTEGER", "isbn TEXT", "storyArc TEXT", "typeOverride TEXT", "editedFields TEXT",
                    "lookupState TEXT", "needsReview INTEGER NOT NULL DEFAULT 0", "lookupAt INTEGER NOT NULL DEFAULT 0",
                ).forEach { db.execSQL("ALTER TABLE books ADD COLUMN $it") }
            }
        }
    }
}

/** Save a book's metadata fields only (not its reading position, mode or text settings). */
suspend fun BookDao.saveMeta(b: Book) = updateMeta(
    b.id, b.title, b.author, b.series, b.seriesNumber, b.genres, b.description, b.isManga, b.mangaRtl, b.country,
    b.publisher, b.year, b.isbn, b.storyArc, b.typeOverride, b.editedFields, b.lookupState, b.needsReview, b.lookupAt, b.coverPath,
)
