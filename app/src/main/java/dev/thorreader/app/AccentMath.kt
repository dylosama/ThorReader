package dev.thorreader.app

import kotlin.math.pow

/** Pure colour maths for the theme colour (no Android). Colours are 0xAARRGGBB ints. */
object AccentMath {
    /** The 8 presets: orange (default), red, gold, green, teal, blue, purple, pink. */
    val PRESETS: List<Int> = listOf(
        0xFFE0A050.toInt(), // orange
        0xFFE5574F.toInt(), // red
        0xFFE8C14A.toInt(), // gold
        0xFF5DBB63.toInt(), // green
        0xFF3FB8AF.toInt(), // teal
        0xFF4F8FE5.toInt(), // blue
        0xFF9B6DE0.toInt(), // purple
        0xFFE56FA8.toInt(), // pink
    )
    val PRESET_NAMES = listOf("Orange", "Red", "Gold", "Green", "Teal", "Blue", "Purple", "Pink")
    val DEFAULT: Int = PRESETS[0]

    private fun channel(c: Int, shift: Int): Double {
        val v = ((c shr shift) and 0xFF) / 255.0
        return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    /** WCAG relative luminance, 0 (black) .. 1 (white). */
    fun luminance(argb: Int): Double = 0.2126 * channel(argb, 16) + 0.7152 * channel(argb, 8) + 0.0722 * channel(argb, 0)

    /** WCAG contrast ratio between two colours (1 .. 21). */
    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** Should text on [argb] be black (true) or white (false)? Whichever contrasts more. */
    fun prefersDarkText(argb: Int): Boolean = contrast(argb, 0xFF000000.toInt()) >= contrast(argb, 0xFFFFFFFF.toInt())

    /** A custom theme colour from a hue (0..360): saturated but not neon, readable on dark. */
    fun fromHue(hue: Float): Int {
        val h = ((hue % 360f) + 360f) % 360f
        val s = 0.62f
        val v = 0.90f
        val c = v * s
        val x = c * (1 - kotlin.math.abs((h / 60f) % 2 - 1))
        val m = v - c
        val (r, g, b) = when {
            h < 60 -> Triple(c, x, 0f)
            h < 120 -> Triple(x, c, 0f)
            h < 180 -> Triple(0f, c, x)
            h < 240 -> Triple(0f, x, c)
            h < 300 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        fun to8(f: Float) = ((f + m) * 255f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (to8(r) shl 16) or (to8(g) shl 8) or to8(b)
    }
}
