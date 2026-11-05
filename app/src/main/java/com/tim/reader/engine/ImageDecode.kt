package com.tim.reader.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import com.gemalto.jp2.JP2Decoder

data class PageSize(val width: Int, val height: Int, val isJp2: Boolean = false) {
    /** Too tall to draw in one piece, so the reader cuts it into slices. */
    val isTall get() = !isJp2 && (height > 4096 || height > width * 4)
}

object ImageDecode {
    const val SLICE_HEIGHT = 2048
    private const val MAX_FULL_HEIGHT = 8192
    private const val MAX_BITMAP_BYTES = 96L * 1024 * 1024

    /** Android refuses to draw larger bitmaps, and that failure can't be caught. */
    fun fits(width: Int, height: Int) = width.toLong() * height * 4 <= MAX_BITMAP_BYTES

    fun size(bytes: ByteArray): PageSize? {
        if (JP2Decoder.isJPEG2000(bytes)) {
            val header = JP2Decoder(bytes).readHeader() ?: return null
            return PageSize(header.width, header.height, isJp2 = true)
        }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        return if (options.outWidth > 0) PageSize(options.outWidth, options.outHeight) else null
    }

    fun full(bytes: ByteArray, targetWidth: Int): Bitmap? {
        if (JP2Decoder.isJPEG2000(bytes)) {
            val decoder = JP2Decoder(bytes)
            val header = decoder.readHeader() ?: return null
            val last = header.numResolutions - 1
            var skip = 0
            while (
                skip < last && (
                    (header.width shr (skip + 1)) >= targetWidth ||
                        !fits(header.width shr skip, header.height shr skip)
                    )
            ) {
                skip++
            }
            if (!fits(header.width shr skip, header.height shr skip)) return null
            return decoder.setSkipResolutions(skip).decode()
        }
        val size = size(bytes) ?: return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleFor(size, targetWidth, MAX_FULL_HEIGHT)
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    fun openRegions(bytes: ByteArray): BitmapRegionDecoder =
        BitmapRegionDecoder.newInstance(bytes, 0, bytes.size)

    fun region(decoder: BitmapRegionDecoder, top: Int, bottom: Int, targetWidth: Int): Bitmap? {
        val width = decoder.width
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleFor(PageSize(width, bottom - top), targetWidth, Int.MAX_VALUE)
        }
        return decoder.decodeRegion(Rect(0, top, width, bottom), options)
    }

    /** The largest power of two that keeps the width sharp and the height within limits. */
    private fun sampleFor(size: PageSize, targetWidth: Int, maxHeight: Int): Int {
        var sample = 1
        while (size.width / (sample * 2) >= targetWidth) sample *= 2
        while (size.height / sample > maxHeight) sample *= 2
        while (!fits(size.width / sample, size.height / sample)) sample *= 2
        return sample
    }
}
