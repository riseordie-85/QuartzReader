package com.tim.reader.theme

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.tim.reader.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Theme artwork, sized to the screen once and then shared. */
object ThemeImages {
    private val memory = LruCache<String, ImageBitmap>(12)

    private val placeholders = mapOf(
        "wood" to R.drawable.wall_wood,
        "speed" to R.drawable.wall_speed,
        "kawaii" to R.drawable.wall_kawaii,
        "burst" to R.drawable.wall_burst,
    )

    fun cached(name: String): ImageBitmap? = memory.get(name)

    suspend fun load(context: Context, name: String): ImageBitmap? = memory.get(name) ?: withContext(Dispatchers.IO) {
        val screenWidth = maxOf(context.resources.displayMetrics.widthPixels, context.resources.displayMetrics.heightPixels)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val file = if (ThemeFiles.isImported(name)) ThemeFiles.imageFile(name) ?: return@withContext null else null
        val resource = placeholders[name]
        if (file == null && resource == null) return@withContext null

        fun decode(options: BitmapFactory.Options) =
            if (file != null) BitmapFactory.decodeFile(file.path, options) else BitmapFactory.decodeResource(context.resources, resource!!, options)

        decode(bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= screenWidth) sample *= 2
        decode(BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()?.also { memory.put(name, it) }
    }

    /** The wallpapers that come with the app, with names to show for them. */
    val builtIn = listOf("wood" to "Wood", "speed" to "Speed lines", "kawaii" to "Kawaii", "burst" to "Burst")
}

@Composable
fun rememberThemeImage(name: String?): State<ImageBitmap?> {
    val context = LocalContext.current.applicationContext
    return produceState(name?.let(ThemeImages::cached), name) {
        value = name?.let { ThemeImages.load(context, it) }
    }
}
