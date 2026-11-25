package dev.thorreader.bookloader.music

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Thor Bookloader's background music: an ORIGINAL cosy bossa / lounge loop, written here as
 * notes and rendered by a tiny synthesizer (no audio files, no samples). Our own melody and
 * chord progression in D major, 116 BPM, 32 bars (about 66 s), looping seamlessly.
 *
 * Plain Kotlin (no Android), so it can be rendered and checked in unit tests.
 */
object Song {
    const val RATE = 22_050
    const val BPM = 116.0
    const val BARS = 32
    private const val BEAT_S = 60.0 / BPM

    /** Exact loop length in samples. */
    val loopSamples: Int = (BARS * 4 * BEAT_S * RATE).toInt()

    // ---- the composition ---------------------------------------------------------------------

    /** A chord: root (MIDI) and its colour tones as intervals above the root. */
    data class Chord(val root: Int, val tones: List<Int>, val beats: Double = 4.0)

    private fun maj9(r: Int, b: Double = 4.0) = Chord(r, listOf(4, 7, 11, 14), b)
    private fun m9(r: Int, b: Double = 4.0) = Chord(r, listOf(3, 7, 10, 14), b)
    private fun m7(r: Int, b: Double = 4.0) = Chord(r, listOf(3, 7, 10), b)
    private fun dom13(r: Int, b: Double = 4.0) = Chord(r, listOf(4, 10, 14, 21), b)
    private fun dom9(r: Int, b: Double = 4.0) = Chord(r, listOf(4, 7, 10, 14), b)
    private fun dom7b9(r: Int, b: Double = 4.0) = Chord(r, listOf(4, 10, 13), b)
    private fun sus4(r: Int, b: Double = 4.0) = Chord(r, listOf(5, 7, 10, 14), b)
    private fun m6(r: Int, b: Double = 4.0) = Chord(r, listOf(3, 7, 9), b)
    private fun maj7(r: Int, b: Double = 4.0) = Chord(r, listOf(4, 7, 11), b)

    // Roots (bass octave): D=38 E=40 F#=42 G=43 A=45 Bb=46 B=47, C=36 F=41.
    /** 32 bars of chords (bars with two chords split them 2 + 2 beats). */
    val chords: List<Chord> = listOf(
        // A1
        maj9(38), m9(47 - 12), m9(40), dom13(45 - 12),
        m7(42), dom7b9(47 - 12), m9(40), sus4(45 - 12, 2.0), dom13(45 - 12, 2.0),
        // A2
        maj9(38), dom9(38), maj9(43 - 12), m6(43 - 12),
        m7(42), dom7b9(47 - 12), m9(40, 2.0), dom13(45 - 12, 2.0), maj9(38),
        // B (bridge)
        maj7(46 - 12), m7(45 - 12, 2.0), dom9(38, 2.0), maj9(43 - 12), m6(43 - 12, 2.0), dom9(36, 2.0),
        m7(42), m7(41, 2.0), dom9(46 - 12, 2.0), m9(40), dom13(45 - 12),
        // A3
        maj9(38), m9(47 - 12), maj9(43 - 12), m6(43 - 12),
        m7(42, 2.0), dom7b9(47 - 12, 2.0), m9(40, 2.0), dom13(45 - 12, 2.0), maj9(38), sus4(45 - 12, 2.0), dom13(45 - 12, 2.0),
    )

    /** One note: start (beats from the bar's start), length (beats), MIDI pitch. */
    data class N(val at: Double, val len: Double, val pitch: Int)

    private fun bar(vararg n: Triple<Double, Double, Int>) = n.map { N(it.first, it.second, it.third) }
    private fun t(a: Double, l: Double, p: Int) = Triple(a, l, p)

    /** The lead melody, bar by bar (our own tune). */
    val melody: List<List<N>> = listOf(
        // A1
        bar(t(0.0, .5, 81), t(.5, .5, 78), t(1.5, 1.0, 76), t(2.5, .5, 78), t(3.0, 1.0, 81)),
        bar(t(.5, .5, 83), t(1.0, .5, 81), t(1.5, 1.5, 78), t(3.0, .5, 76), t(3.5, .5, 74)),
        bar(t(0.0, 1.0, 76), t(1.0, .5, 79), t(1.5, .5, 78), t(2.0, 1.0, 76), t(3.0, .5, 71), t(3.5, .5, 74)),
        bar(t(0.0, 1.5, 73), t(1.5, .5, 76), t(2.0, .5, 78), t(2.5, 1.5, 79)),
        bar(t(0.0, .5, 81), t(.5, 1.0, 76), t(1.5, .5, 78), t(2.0, 2.0, 73)),
        bar(t(0.0, .5, 75), t(.5, .5, 78), t(1.0, .5, 81), t(1.5, .5, 84), t(2.0, 1.0, 83), t(3.0, 1.0, 78)),
        bar(t(0.0, 1.5, 79), t(1.5, .5, 78), t(2.0, .5, 76), t(2.5, .5, 74), t(3.0, 1.0, 78)),
        bar(t(0.0, 1.0, 74), t(1.0, 1.0, 76), t(2.0, .5, 73), t(2.5, 1.5, 69)),
        // A2
        bar(t(0.0, .5, 69), t(.5, .5, 73), t(1.0, .5, 76), t(1.5, .5, 81), t(2.0, 1.5, 78), t(3.5, .5, 76)),
        bar(t(0.0, 1.0, 78), t(1.0, .5, 76), t(1.5, .5, 72), t(2.0, 2.0, 74)),
        bar(t(0.0, .5, 83), t(.5, .5, 81), t(1.0, 1.0, 78), t(2.0, .5, 79), t(2.5, 1.5, 81)),
        bar(t(0.0, 1.0, 82), t(1.0, .5, 79), t(1.5, .5, 76), t(2.0, 2.0, 74)),
        bar(t(0.0, .5, 73), t(.5, .5, 76), t(1.0, .5, 81), t(1.5, 1.5, 78), t(3.0, 1.0, 76)),
        bar(t(0.0, 1.0, 75), t(1.0, .5, 78), t(1.5, .5, 81), t(2.0, .5, 83), t(2.5, 1.5, 81)),
        bar(t(0.0, 1.0, 79), t(1.0, 1.0, 78), t(2.0, .5, 76), t(2.5, .5, 78), t(3.0, 1.0, 79)),
        bar(t(0.0, 1.5, 78), t(1.5, .5, 76), t(2.0, 2.0, 74)),
        // B (bridge)
        bar(t(0.0, .5, 77), t(.5, .5, 81), t(1.0, 1.5, 82), t(2.5, .5, 81), t(3.0, 1.0, 77)),
        bar(t(0.0, 1.0, 76), t(1.0, 1.0, 72), t(2.0, .5, 78), t(2.5, 1.5, 76)),
        bar(t(0.0, .5, 74), t(.5, .5, 78), t(1.0, .5, 81), t(1.5, 1.0, 83), t(2.5, .5, 81), t(3.0, 1.0, 78)),
        bar(t(0.0, 1.0, 82), t(1.0, 1.0, 79), t(2.0, .5, 76), t(2.5, 1.5, 74)),
        bar(t(0.0, .5, 73), t(.5, .5, 76), t(1.0, 1.0, 81), t(2.0, 1.0, 78), t(3.0, 1.0, 76)),
        bar(t(0.0, 1.0, 77), t(1.0, .5, 80), t(1.5, .5, 84), t(2.0, 1.0, 74), t(3.0, 1.0, 80)),
        bar(t(0.0, 1.5, 79), t(1.5, .5, 78), t(2.0, 1.0, 74), t(3.0, 1.0, 71)),
        bar(t(0.0, .5, 73), t(.5, .5, 76), t(1.0, .5, 78), t(1.5, .5, 79), t(2.0, 2.0, 81)),
        // A3
        bar(t(0.0, .5, 81), t(.5, .5, 78), t(1.5, 1.0, 76), t(2.5, .5, 78), t(3.0, 1.0, 85)),
        bar(t(0.0, 1.0, 86), t(1.0, .5, 83), t(1.5, 1.5, 78), t(3.0, 1.0, 81)),
        bar(t(0.0, .5, 79), t(.5, .5, 83), t(1.0, 1.0, 86), t(2.0, .5, 83), t(2.5, 1.5, 81)),
        bar(t(0.0, 1.0, 82), t(1.0, .5, 79), t(1.5, .5, 76), t(2.0, 2.0, 74)),
        bar(t(0.0, 1.0, 76), t(1.0, 1.0, 73), t(2.0, .5, 75), t(2.5, 1.5, 78)),
        bar(t(0.0, 1.0, 79), t(1.0, 1.0, 76), t(2.0, 1.0, 78), t(3.0, 1.0, 79)),
        bar(t(0.0, 1.0, 78), t(1.0, .5, 76), t(1.5, 2.5, 74)),
        bar(t(0.0, 1.0, 74), t(1.0, 1.0, 76), t(2.0, 1.0, 78), t(3.0, 1.0, 79)),
    )

    // ---- rendering ---------------------------------------------------------------------------

    fun hz(midi: Int) = 440.0 * 2.0.pow((midi - 69) / 12.0)

    private fun sampleAt(beats: Double) = (beats * BEAT_S * RATE).toInt()

    /** Adds into the loop with wrap-around, so notes ringing past the end continue at the start. */
    private class Mix(val buf: FloatArray) {
        fun add(i: Int, v: Float) {
            val n = buf.size
            buf[((i % n) + n) % n] += v
        }
    }

    /** Marimba-bell lead: a sine with a bright, fast-fading 4th partial and a soft bell overtone. */
    private fun lead(m: Mix, start: Int, lenS: Double, f: Double, gain: Float) {
        val total = ((lenS + 0.9) * RATE).toInt()
        for (i in 0 until total) {
            val ts = i.toDouble() / RATE
            val env = (1 - exp(-ts * 400)) * exp(-ts * 2.6) * (if (ts > lenS) exp(-(ts - lenS) * 9) else 1.0)
            val v = sin(2 * PI * f * ts) + 0.35 * sin(2 * PI * f * 4 * ts) * exp(-ts * 18) + 0.12 * sin(2 * PI * f * 2.76 * ts) * exp(-ts * 7)
            m.add(start + i, (v * env * gain).toFloat())
        }
    }

    /** FM electric piano for the chords: the brightness fades as the note rings. */
    private fun epiano(m: Mix, start: Int, lenS: Double, f: Double, gain: Float) {
        val total = ((lenS + 0.35) * RATE).toInt()
        for (i in 0 until total) {
            val ts = i.toDouble() / RATE
            val index = 1.6 * exp(-ts * 6) + 0.2
            val env = (1 - exp(-ts * 300)) * exp(-ts * 1.8) * (if (ts > lenS) exp(-(ts - lenS) * 14) else 1.0)
            val v = sin(2 * PI * f * ts + index * sin(2 * PI * f * ts))
            m.add(start + i, (v * env * gain).toFloat())
        }
    }

    /** Soft round bass. */
    private fun bass(m: Mix, start: Int, lenS: Double, f: Double, gain: Float) {
        val total = ((lenS + 0.15) * RATE).toInt()
        for (i in 0 until total) {
            val ts = i.toDouble() / RATE
            val env = (1 - exp(-ts * 250)) * exp(-ts * 1.2) * (if (ts > lenS) exp(-(ts - lenS) * 30) else 1.0)
            val v = sin(2 * PI * f * ts) + 0.22 * sin(4 * PI * f * ts)
            m.add(start + i, (v * env * gain).toFloat())
        }
    }

    /** Deterministic noise (the same loop every time). */
    private var seed = 0x2545F491
    private fun noise(): Double {
        seed = seed xor (seed shl 13); seed = seed xor (seed ushr 17); seed = seed xor (seed shl 5)
        return (seed and 0xFFFF) / 32768.0 - 1.0
    }

    /** Brushed snare swish: soft filtered noise. */
    private fun brush(m: Mix, start: Int, lenS: Double, gain: Float) {
        val total = (lenS * RATE).toInt()
        var lp = 0.0
        for (i in 0 until total) {
            val ts = i.toDouble() / RATE
            lp += 0.35 * (noise() - lp)
            val env = (1 - exp(-ts * 60)) * exp(-ts * (3.0 / lenS))
            m.add(start + i, (lp * env * gain).toFloat())
        }
    }

    /** Rim click: a very short bright knock. */
    private fun rim(m: Mix, start: Int, gain: Float) {
        val total = (0.05 * RATE).toInt()
        for (i in 0 until total) {
            val ts = i.toDouble() / RATE
            val v = sin(2 * PI * 1700 * ts) * 0.6 + noise() * 0.4
            m.add(start + i, (v * exp(-ts * 90) * gain).toFloat())
        }
    }

    /** Soft kick: a short falling sine. */
    private fun kick(m: Mix, start: Int, gain: Float) {
        val total = (0.25 * RATE).toInt()
        var phase = 0.0
        for (i in 0 until total) {
            val ts = i.toDouble() / RATE
            phase += 2 * PI * (55 + 70 * exp(-ts * 30)) / RATE
            m.add(start + i, (sin(phase) * exp(-ts * 14) * gain).toFloat())
        }
    }

    /** Fold [midi] into [lo, hi] by octaves (chord voicings in the piano's middle). */
    private fun fold(midi: Int, lo: Int, hi: Int): Int {
        var x = midi
        while (x < lo) x += 12
        while (x > hi) x -= 12
        return x
    }

    /** Renders the whole loop: 16-bit mono PCM at [RATE], exactly [loopSamples] long, seamless. */
    fun render(): ShortArray {
        seed = 0x2545F491
        val buf = FloatArray(loopSamples)
        val m = Mix(buf)
        val beatS = BEAT_S

        // Chords: e-piano comping (two-bar bossa pattern, in eighths) and the bass.
        var beat = 0.0
        var chordIndex = 0
        for (c in chords) {
            val voicing = c.tones.map { fold(c.root + it, 57, 70) }.distinct().sorted()
            val barNo = (beat / 4).toInt()
            val pattern = if (barNo % 2 == 0) listOf(0, 3, 6) else listOf(2, 4, 7)
            for (e in pattern) {
                val at = (barNo * 4) + e * 0.5
                if (at >= beat && at < beat + c.beats) {
                    voicing.forEachIndexed { k, p -> epiano(m, sampleAt(at) + k * 25, 0.42 * beatS * 2, hz(p), 0.075f) }
                }
            }
            // Bass: root on the first beat, fifth on the third (and pick-ups in between).
            val root = hz(c.root)
            val fifth = hz(c.root + 7)
            bass(m, sampleAt(beat), 1.4 * beatS, root, 0.32f)
            if (c.beats >= 2) bass(m, sampleAt(beat + 1.5), 0.45 * beatS, root, 0.22f)
            if (c.beats >= 4) {
                bass(m, sampleAt(beat + 2), 1.4 * beatS, fifth, 0.28f)
                bass(m, sampleAt(beat + 3.5), 0.45 * beatS, fifth, 0.2f)
            }
            beat += c.beats
            chordIndex++
        }

        // Lead melody.
        melody.forEachIndexed { b, notes ->
            for (n in notes) lead(m, sampleAt(b * 4 + n.at), n.len * beatS, hz(n.pitch), 0.17f)
        }

        // Percussion: brush on every eighth (longer on 2 and 4), rim on a bossa pattern, soft kick.
        val rimPattern = listOf(0, 3, 6, 10, 12) // eighths within two bars
        for (b in 0 until BARS) {
            for (e in 0 until 8) {
                val at = b * 4 + e * 0.5
                val accent = e == 2 || e == 6
                brush(m, sampleAt(at), if (accent) 0.32 else 0.12, if (accent) 0.07f else 0.035f)
            }
            for (e in rimPattern) if ((e / 8) == b % 2) rim(m, sampleAt(b * 4 + (e % 8) * 0.5), 0.05f)
            kick(m, sampleAt(b * 4.0), 0.22f)
            kick(m, sampleAt(b * 4 + 2.0), 0.16f)
        }

        val wet = reverb(buf)
        // Gentle limiter, then 16-bit.
        var peak = 1e-6f
        for (i in buf.indices) {
            buf[i] = buf[i] + 0.2f * wet[i]
            peak = max(peak, abs(buf[i]))
        }
        val norm = 0.85f / peak
        return ShortArray(buf.size) { i -> (tanh(buf[i] * norm * 1.1) / tanh(1.1) * 32767 * 0.9).toInt().toShort() }
    }

    /** A small Schroeder room (3 combs + 1 all-pass), run twice round the loop so the tail wraps. */
    private fun reverb(dry: FloatArray): FloatArray {
        val n = dry.size
        val out = FloatArray(n)
        val combs = intArrayOf(1557, 1617, 1491).map { (it * RATE / 44_100.0).toInt() }
        val lines = combs.map { FloatArray(it) }
        val idx = IntArray(combs.size)
        val ap = FloatArray((556 * RATE / 44_100.0).toInt())
        var apI = 0
        for (pass in 0..1) {
            for (i in 0 until n) {
                var s = 0f
                for (c in lines.indices) {
                    val line = lines[c]
                    val y = line[idx[c]]
                    line[idx[c]] = dry[i] + y * 0.78f
                    idx[c] = (idx[c] + 1) % line.size
                    s += y
                }
                s /= lines.size
                val y = ap[apI]
                val v = -s * 0.5f + y
                ap[apI] = s + y * 0.5f
                apI = (apI + 1) % ap.size
                if (pass == 1) out[i] = v
            }
        }
        return out
    }

    // ---- sound effects -------------------------------------------------------------------------

    /** "Bloop": a quick, rounded upward blip (a book has been sent). About 0.25 s. */
    fun bloop(): ShortArray {
        val total = (0.26 * RATE).toInt()
        var phase = 0.0
        return ShortArray(total) { i ->
            val ts = i.toDouble() / RATE
            val f = 520 + 520 * min(1.0, ts / 0.09)
            phase += 2 * PI * f / RATE
            val env = (1 - exp(-ts * 500)) * exp(-ts * 16)
            ((sin(phase) + 0.25 * sin(2 * phase)) * env * 0.6 * 32767).toInt().toShort()
        }
    }

    /** Soft chime: three bell notes going up (the whole queue is done). About 1.2 s. */
    fun chime(): ShortArray {
        val total = (1.25 * RATE).toInt()
        val notes = listOf(0.0 to 86, 0.12 to 90, 0.24 to 93) // D6, F#6, A6
        val out = FloatArray(total)
        for ((at, p) in notes) {
            val f = hz(p)
            val s0 = (at * RATE).toInt()
            for (i in 0 until total - s0) {
                val ts = i.toDouble() / RATE
                val v = sin(2 * PI * f * ts) + 0.3 * sin(2 * PI * f * 2.76 * ts) * exp(-ts * 8)
                out[s0 + i] += (v * (1 - exp(-ts * 400)) * exp(-ts * 3.5) * 0.28).toFloat()
            }
        }
        return ShortArray(total) { (out[it].coerceIn(-1f, 1f) * 32767).toInt().toShort() }
    }
}
