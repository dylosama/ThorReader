package dev.thorreader.app.io

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * Decoding pictures that come from outside (covers from online lookups and catalogs, covers in
 * book files): the size is read first, absurd dimensions are refused (a small file can claim a
 * picture big enough to exhaust memory - an "image bomb"), and the picture is decoded no larger
 * than needed. Also faster and lighter for small thumbnails.
 */
object SafeImages {
    /** More pixels than any real cover or page (≈ 13 000 x 13 000) means a broken or hostile file. */
    const val MAX_PIXELS = 170_000_000L

    /** [bytes] decoded with its longest side at most about [maxSide] px, or null. */
    fun decode(bytes: ByteArray, maxSide: Int): Bitmap? = runCatching {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        val sample = sampleFor(o.outWidth, o.outHeight, maxSide) ?: return null
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    /** The picture file at [path], longest side at most about [maxSide] px, or null. */
    fun decodeFile(path: String, maxSide: Int): Bitmap? = runCatching {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        val sample = sampleFor(o.outWidth, o.outHeight, maxSide) ?: return null
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    /**
     * The power-of-two sample that brings the longest side to [maxSide] or just above (never
     * below: no blur), or null for a picture that can't be decoded or is impossibly large.
     */
    fun sampleFor(w: Int, h: Int, maxSide: Int): Int? {
        if (w <= 0 || h <= 0) return null
        if (w.toLong() * h > MAX_PIXELS) return null
        var sample = 1
        while (maxOf(w, h) / (sample * 2) >= maxSide) sample *= 2
        return sample
    }
}
