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
        "burst" to R.drawable.wall_burst,
    )

    fun cached(name: String): ImageBitmap? = memory.get(name)

    suspend fun load(context: Context, name: String): ImageBitmap? = memory.get(name) ?: withContext(Dispatchers.IO) {
        val resource = placeholders[name] ?: return@withContext null
        val screenWidth = maxOf(context.resources.displayMetrics.widthPixels, context.resources.displayMetrics.heightPixels)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeResource(context.resources, resource, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= screenWidth) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeResource(context.resources, resource, options)?.asImageBitmap()?.also { memory.put(name, it) }
    }
}

@Composable
fun rememberThemeImage(name: String?): State<ImageBitmap?> {
    val context = LocalContext.current.applicationContext
    return produceState(name?.let(ThemeImages::cached), name) {
        value = name?.let { ThemeImages.load(context, it) }
    }
}
