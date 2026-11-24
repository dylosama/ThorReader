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
) {
    val rtl: Boolean get() = rtlOverride ?: mangaRtl
    val readingMode: ReadingMode? get() = mode?.let { runCatching { ReadingMode.valueOf(it) }.getOrNull() }
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

@Database(entities = [Book::class, SeriesDirection::class], version = 3, exportSchema = false)
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
    }
}
