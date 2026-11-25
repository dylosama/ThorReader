package dev.thorreader.app

import dev.thorreader.app.engine.Opds
import dev.thorreader.app.io.SafeImages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hostile input from catalogs and covers is refused or neutralised. */
class NetworkInputHardeningTest {
    @Test
    fun catalogEntityBombIsStrippedBeforeParsing() {
        // "Billion laughs": nested entities that expand to gigabytes.
        val bomb = """
            <?xml version="1.0"?>
            <!DOCTYPE lolz [
              <!ENTITY lol "lol">
              <!ENTITY lol2 "&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;">
              <!ENTITY lol3 "&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;">
            ]>
            <feed xmlns="http://www.w3.org/2005/Atom"><title>Feed</title></feed>
        """.trimIndent().toByteArray()
        val stripped = String(Opds.stripDoctype(bomb))
        assertFalse("ENTITY" in stripped)
        assertTrue("<feed" in stripped)
        // And an external entity (reading a local file) never gets a chance either.
        val xxe = """<?xml version="1.0"?><!DOCTYPE f [<!ENTITY x SYSTEM "file:///data/data/x">]><feed xmlns="http://www.w3.org/2005/Atom"><title>&x;</title></feed>"""
        assertFalse("SYSTEM" in String(Opds.stripDoctype(xxe.toByteArray())))
    }

    @Test
    fun imageBombsAreRefusedAndCoversDecodedSmall() {
        assertNull(SafeImages.sampleFor(60_000, 60_000, 400)) // 3.6 gigapixels claimed by a tiny file
        assertNull(SafeImages.sampleFor(0, 100, 400))
        assertEquals(1, SafeImages.sampleFor(300, 450, 400)) // already small: as is
        assertEquals(4, SafeImages.sampleFor(2000, 3000, 400)) // 3000 â†’ 750 (â‰¥ 400, never blurrier)
    }
}
