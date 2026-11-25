package dev.thorreader.bookloader

import dev.thorreader.bookloader.music.Song
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/** The background music: the composition adds up, and the rendered loop is clean and seamless. */
class MusicTest {
    @Test
    fun compositionFillsTheLoop() {
        assertEquals("chords cover all 32 bars", Song.BARS * 4.0, Song.chords.sumOf { it.beats }, 1e-9)
        assertEquals(Song.BARS, Song.melody.size)
        for ((b, notes) in Song.melody.withIndex()) {
            for (n in notes) {
                assertTrue("bar ${b + 1}: note inside the bar", n.at >= 0 && n.at + n.len <= 4.0 + 1e-9)
                assertTrue("bar ${b + 1}: lead range", n.pitch in 64..90)
            }
            assertTrue("bar ${b + 1}: notes in order, no overlaps", notes.zipWithNext().all { (a, c) -> a.at + a.len <= c.at + 1e-9 })
        }
        val seconds = Song.loopSamples.toDouble() / Song.RATE
        assertTrue("loop is $seconds s", seconds in 60.0..90.0)
    }

    @Test
    fun theLoopIsCleanSeamlessAndAlwaysTheSame() {
        val pcm = Song.render()
        assertEquals(Song.loopSamples, pcm.size)
        var peak = 0
        var sum = 0.0
        var maxStep = 0
        for (i in pcm.indices) {
            val v = pcm[i].toInt()
            peak = maxOf(peak, abs(v))
            sum += v.toDouble() * v
            if (i > 0) maxStep = maxOf(maxStep, abs(v - pcm[i - 1]))
        }
        val rms = sqrt(sum / pcm.size) / 32768
        assertTrue("never clips (peak $peak)", peak < 32767)
        assertTrue("audible (rms $rms)", rms > 0.05)
        assertTrue("not too loud (rms $rms)", rms < 0.5)
        // Seamless: the jump from the last sample back to the first is no bigger than any jump inside the loop.
        val seam = abs(pcm[0] - pcm[pcm.size - 1])
        assertTrue("seam $seam vs largest step $maxStep", seam <= maxStep)
        // No silent gap anywhere (every quarter second has sound).
        val q = Song.RATE / 4
        for (start in 0 until pcm.size - q step q) {
            val loud = (start until start + q).maxOf { abs(pcm[it].toInt()) }
            assertTrue("sound at ${start / Song.RATE.toDouble()} s", loud > 300)
        }
        assertArrayEquals("deterministic", pcm, Song.render())
    }

    @Test
    fun soundEffectsAreShortAndAudible() {
        val bloop = Song.bloop()
        val chime = Song.chime()
        assertTrue(bloop.size < Song.RATE / 2)
        assertTrue(chime.size < Song.RATE * 2)
        assertTrue(bloop.maxOf { abs(it.toInt()) } > 5000)
        assertTrue(chime.maxOf { abs(it.toInt()) } > 3000)
        assertTrue(chime.maxOf { abs(it.toInt()) } < 32767)
    }
}

/** Writes bookloader/build/music-preview.wav (two loops + the effects) to listen to on a PC. */
class MusicPreview {
    @Test
    fun writePreview() {
        val loop = Song.render()
        val gap = ShortArray(Song.RATE / 2)
        val all = loop + loop + gap + Song.bloop() + gap + Song.chime()
        val dir = listOf(java.io.File("build"), java.io.File("bookloader/build")).first { it.exists() }
        val out = java.io.File(dir, "music-preview.wav")
        val data = java.nio.ByteBuffer.allocate(44 + all.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        data.put("RIFF".toByteArray()).putInt(36 + all.size * 2).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(Song.RATE).putInt(Song.RATE * 2).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(all.size * 2)
        all.forEach { data.putShort(it) }
        out.writeBytes(data.array())
        assertTrue(out.length() > 1000)
    }
}
