package com.tim.reader.theme

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.tim.reader.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MEMORY_BYTES = 192 * 1024 * 1024
private const val MAX_PIXELS = 16_000_000L
private const val THUMBNAIL_SIZE = 320

/** Theme artwork, sized to the screen once and then shared. */
object ThemeImages {
    private val memory = object : LruCache<String, ImageBitmap>(MEMORY_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.asAndroidBitmap().byteCount
    }

    private val placeholders = mapOf(
        "wood" to R.drawable.wall_wood,
        "speed" to R.drawable.wall_speed,
        "kawaii" to R.drawable.wall_kawaii,
        "burst" to R.drawable.wall_burst,
        "crystal" to R.drawable.wall_crystal,
    )

    private fun key(name: String, thumbnail: Boolean) = if (thumbnail) "$name#thumbnail" else name

    fun cached(name: String, thumbnail: Boolean = false): ImageBitmap? = memory.get(key(name, thumbnail))

    suspend fun load(context: Context, name: String, thumbnail: Boolean = false): ImageBitmap? = memory.get(key(name, thumbnail)) ?: withContext(Dispatchers.IO) {
        val metrics = context.resources.displayMetrics
        val screenWidth = if (thumbnail) THUMBNAIL_SIZE else maxOf(metrics.widthPixels, metrics.heightPixels)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val file = if (ThemeFiles.isImported(name)) ThemeFiles.imageFile(name) ?: return@withContext null else null
        val resource = placeholders[name]
        if (file == null && resource == null) return@withContext null

        fun decode(options: BitmapFactory.Options) =
            if (file != null) BitmapFactory.decodeFile(file.path, options) else BitmapFactory.decodeResource(context.resources, resource!!, options)

        decode(bounds)
        // Android refuses to draw a huge bitmap, so size and pixel count both have to stay in check.
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (
            longest / (sample * 2) >= screenWidth ||
            bounds.outWidth.toLong() * bounds.outHeight / (sample * sample) > MAX_PIXELS
        ) {
            sample *= 2
        }
        decode(BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()?.also { memory.put(key(name, thumbnail), it) }
    }

    /** The wallpapers that come with the app, with names to show for them. */
    val builtIn = listOf("wood" to "Wood", "speed" to "Speed lines", "kawaii" to "Kawaii", "burst" to "Burst", "crystal" to "Crystal")
}

@Composable
fun rememberThemeImage(name: String?, thumbnail: Boolean = false): State<ImageBitmap?> {
    val context = LocalContext.current.applicationContext
    return produceState(name?.let { ThemeImages.cached(it, thumbnail) }, name, thumbnail) {
        value = name?.let { ThemeImages.load(context, it, thumbnail) }
    }
}
