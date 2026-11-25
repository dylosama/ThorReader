package dev.thorreader.app

import dev.thorreader.app.engine.MangaSeriesMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Volume → chapter maps from Wikipedia's "Graphic novel list" tables (formats seen on real pages). */
class MangaSeriesMapTest {
    private val wikitext = """
        ==Volumes==
        {{Graphic novel list/header|OneLanguage=yes}}
        {{Graphic novel list
        |VolumeNumber    = 1
        |OriginalRelDate = December 22, 2017<ref>{{cite web|title=x}}</ref>
        |ChapterListCol1 =
        *1. {{Nihongo|"Awakening to the Joy of Painting"|絵を描く{{Ruby-ja|悦|よろこ}}びに目覚めてみた|E o Egaku}}
        *2. {{Nihongo|"Meaningful Time"|有意義な時間|Yūigina Jikan}}
        |ChapterListCol2 =
        *3. {{Nihongo|"He's Not Tanned At All"|全然焼けてねえ|Zenzen}}
        *4. {{Nihongo|"You're No God"|マジ神じゃない|Majishin}}
        }}
        {{Graphic novel list
        |VolumeNumber    = 2
        |ChapterList =
        * 005. "Prep School Debut of the Dead"
        * 006. "Surprisingly Pure?!"
        * 007. "Where Will We Go?"
        * 008. "Exam Piece"
        }}
        {{Graphic novel list
        |VolumeNumber    = 3
        |ChapterList =
        # "Untitled A"
        # "Untitled B"
        }}
        {{Graphic novel list
        |VolumeNumber    = 4
        |ChapterListCol1 =
        |ChapterListCol2 =
        }}
        {{Graphic novel list/footer}}

        ====Chapters not yet in ''tankōbon'' format====
        *11. {{Nihongo|"As the World Glitches"|世界、バグる。|Sekai}}
        *12. {{Nihongo|"Next"|次|Tsugi}}

        ==Reception==
        *1. Not a chapter.
    """.trimIndent()

    @Test
    fun volumesAndLooseChapters() {
        val (volumes, loose) = MangaSeriesMap.fromWikitext(wikitext)!!
        assertEquals(listOf(1f, 2f, 3f, 4f), volumes[1])
        assertEquals(listOf(5f, 6f, 7f, 8f), volumes[2])
        assertEquals(listOf(9f, 10f), volumes[3]) // unnumbered: continues from volume 2
        assertEquals(emptyList<Float>(), volumes[4]) // announced, no chapters listed yet
        assertEquals(listOf(11f, 12f), loose) // and nothing from "Reception"
        val map = MangaSeriesMap(volumes, loose, MangaSeriesMap.Status.ONGOING, null, "Wikipedia")
        assertEquals(12f, map.lastChapter)
        assertEquals(2, map.volumeOf(7f))
        assertNull(map.volumeOf(11f))
    }

    @Test
    fun numberedListTemplates() {
        val text = """
            {{Graphic novel list
            | VolumeNumber = 1
            | ChapterListCol1 =
            {{Numbered list|start=1
            |{{Nihongo|"Dog & Chainsaw"|犬とチェンソー|Inu to Chensō}}
            |{{Nihongo|"Power"|{{Ruby-ja|力|パワー}}|Pawā}}
            }}
            | ChapterListCol2 =
            {{Numbered list|start=3
            |{{Nihongo|"Service"|使役|Shieki}}
            }}
            }}
            {{Graphic novel list/footer}}
            ===Chapters not yet in volumes===
            {{Numbered list|start=4
            |{{Nihongo|"Next"|次|Tsugi}}
            |{{Nihongo|"After"|後|Ato}}
            }}
        """.trimIndent()
        val (v, loose) = MangaSeriesMap.fromWikitext(text)!!
        assertEquals(listOf(1f, 2f, 3f), v[1])
        assertEquals(listOf(4f, 5f), loose)
    }

    @Test
    fun noVolumeList() {
        assertNull(MangaSeriesMap.fromWikitext("'''Dune''' is a 1965 novel."))
    }

    @Test
    fun aniListStatus() {
        assertEquals(MangaSeriesMap.Status.ONGOING, MangaSeriesMap.status("RELEASING"))
        assertEquals(MangaSeriesMap.Status.COMPLETED, MangaSeriesMap.status("FINISHED"))
        assertEquals(MangaSeriesMap.Status.HIATUS, MangaSeriesMap.status("HIATUS"))
        assertNull(MangaSeriesMap.status(null))
    }
}
