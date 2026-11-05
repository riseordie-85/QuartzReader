package com.tim.reader.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas as AndroidCanvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tim.reader.R
import com.tim.reader.ResultsLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.max

private enum class ShadowMode(val label: String) {
    PreRendered("Pre-rendered shadows"),
    Elevation("Elevation shadows"),
    LiveBlur("Live blur shadows"),
}

private class ShelfTheme(
    val name: String,
    val wallpaper: Int?,
    val base: Color,
    val bar: Brush,
    val text: Color,
    val cornerDp: Int,
)

private val silver = Brush.verticalGradient(listOf(Color(0xFFF2F2F2), Color(0xFFA8A8A8)))
private val metal = Brush.linearGradient(listOf(Color(0xFF9A9A9A), Color(0xFFC8C8C8), Color(0xFF9A9A9A)))
private val walnut = Brush.linearGradient(listOf(Color(0xFF6B3F22), Color(0xFF8A5A36), Color(0xFF6B3F22)))
private val purple = Brush.linearGradient(listOf(Color(0xFF8F44BF), Color(0xFFB86BD8), Color(0xFF8F44BF)))
private val blue = Brush.verticalGradient(listOf(Color(0xFF0000FF), Color(0xFF0000D0)))

private val themes = listOf(
    ShelfTheme("Silver Gloss", null, Color(0xFFB8B8B8), silver, Color.Black, 28),
    ShelfTheme("Brushed Metal", null, Color(0xFFADADAD), metal, Color.Black, 4),
    ShelfTheme("Wood", R.drawable.wall_wood, Color(0xFF8A5E3B), walnut, Color(0xFFF2DFC0), 4),
    ShelfTheme("Burst", R.drawable.wall_burst, Color(0xFFE4C9FB), purple, Color(0xFFF7E9C8), 28),
    ShelfTheme("Speed Lines", R.drawable.wall_speed, Color(0xFFF08A1D), blue, Color.White, 28),
)

private const val SWIPE_RANGE = 10_000
private const val COVER_WIDTH = 360
private const val COVER_HEIGHT = 540
private const val SHADOW_PAD = 60
private const val STEP_TIMEOUT_MS = 20_000L

private class ShelfAssets(
    val wallpapers: Map<Int, ImageBitmap>,
    val covers: List<ImageBitmap>,
    val shadow: ImageBitmap,
)

private fun loadWallpaper(context: Context, resource: Int, targetWidth: Int): ImageBitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeResource(context.resources, resource, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeResource(context.resources, resource, options).asImageBitmap()
}

private fun makeCover(index: Int): ImageBitmap {
    val bitmap = Bitmap.createBitmap(COVER_WIDTH, COVER_HEIGHT, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    val hue = (index * 47f) % 360f
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.shader = LinearGradient(
        0f,
        0f,
        COVER_WIDTH.toFloat(),
        COVER_HEIGHT.toFloat(),
        android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.75f, 0.9f)),
        android.graphics.Color.HSVToColor(floatArrayOf((hue + 50f) % 360f, 0.9f, 0.35f)),
        Shader.TileMode.CLAMP,
    )
    canvas.drawRect(0f, 0f, COVER_WIDTH.toFloat(), COVER_HEIGHT.toFloat(), paint)
    paint.shader = null
    paint.color = android.graphics.Color.WHITE
    paint.alpha = 225
    canvas.drawRect(36f, 60f, 324f, 96f, paint)
    canvas.drawRect(36f, 120f, 260f, 150f, paint)
    canvas.drawCircle(180f, 330f, 90f + index % 4 * 12f, paint)
    return bitmap.asImageBitmap()
}

private fun makeShadow(): ImageBitmap {
    val bitmap = Bitmap.createBitmap(
        COVER_WIDTH + SHADOW_PAD * 2,
        COVER_HEIGHT + SHADOW_PAD * 2,
        Bitmap.Config.ARGB_8888,
    )
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(170, 0, 0, 0)
        maskFilter = BlurMaskFilter(30f, BlurMaskFilter.Blur.NORMAL)
    }
    AndroidCanvas(bitmap).drawRect(
        SHADOW_PAD.toFloat(),
        SHADOW_PAD.toFloat(),
        (SHADOW_PAD + COVER_WIDTH).toFloat(),
        (SHADOW_PAD + COVER_HEIGHT).toFloat(),
        paint,
    )
    return bitmap.asImageBitmap()
}

/** Frame times, in milliseconds. */
private class FrameStats {
    private var count = 0
    private var total = 0.0
    private var worst = 0.0
    private var janky = 0

    fun add(milliseconds: Double) {
        count++
        total += milliseconds
        worst = max(worst, milliseconds)
        if (milliseconds > 25.0) janky++
    }

    fun reset() {
        count = 0
        total = 0.0
        worst = 0.0
        janky = 0
    }

    fun summary(): String {
        if (count == 0) return "Measuring"
        val fps = 1000.0 * count / total
        return "%.0f fps, %d slow frames of %d, worst %.0f ms".format(fps, janky, count, worst)
    }
}

/** Logs before and after a step, so a stall shows exactly where it happened. */
private suspend fun testStep(label: String, stats: FrameStats, block: suspend () -> Unit) {
    ResultsLog.add("Starting: $label")
    stats.reset()
    val finished = withTimeoutOrNull(STEP_TIMEOUT_MS) {
        block()
        true
    }
    if (finished == true) {
        ResultsLog.add("$label: ${stats.summary()}")
    } else {
        ResultsLog.add("TIMED OUT: $label, ${stats.summary()}")
    }
}

@Composable
fun ShelfTestScreen() {
    val context = LocalContext.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val screenWidth = constraints.maxWidth
        val assets by produceState<ShelfAssets?>(null, screenWidth) {
            value = withContext(Dispatchers.Default) {
                ShelfAssets(
                    wallpapers = themes.mapNotNull { it.wallpaper }
                        .associateWith { loadWallpaper(context, it, screenWidth) },
                    covers = List(24) { makeCover(it) },
                    shadow = makeShadow(),
                )
            }
        }
        val ready = assets
        if (ready == null) {
            Text("Loading", Modifier.padding(32.dp))
        } else {
            val columns = (maxWidth.value / 90f).toInt().coerceIn(3, 8)
            Shelves(ready, columns)
        }
    }
}

@Composable
private fun Shelves(assets: ShelfAssets, columns: Int) {
    val pager = rememberPagerState(initialPage = SWIPE_RANGE / 2 - SWIPE_RANGE / 2 % themes.size) { SWIPE_RANGE }
    val lists = remember { List(themes.size) { LazyListState() } }
    val stats = remember { FrameStats() }
    var shadowMode by remember { mutableStateOf(ShadowMode.PreRendered) }
    var readout by remember { mutableStateOf("Measuring") }
    var testJob by remember { mutableStateOf<Job?>(null) }
    val running = testJob != null
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        var previous = 0L
        while (true) {
            withFrameNanos { now ->
                if (previous != 0L) stats.add((now - previous) / 1_000_000.0)
                previous = now
            }
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            readout = stats.summary()
        }
    }

    Box(Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 1,
            // A touch would cancel the scripted scrolling.
            userScrollEnabled = !running,
        ) { page ->
            val themeIndex = page % themes.size
            ShelfPage(
                theme = themes[themeIndex],
                previous = themes[(themeIndex + themes.size - 1) % themes.size].name,
                next = themes[(themeIndex + 1) % themes.size].name,
                list = lists[themeIndex],
                assets = assets,
                columns = columns,
                shadowMode = shadowMode,
                seed = themeIndex,
                touchEnabled = !running,
            )
        }

        Column(
            Modifier
                .fillMaxWidth()
                .background(Color(0xBB000000))
                .safeDrawingPadding()
                .padding(8.dp),
        ) {
            Text(readout, color = Color.White, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({
                    shadowMode = ShadowMode.entries[(shadowMode.ordinal + 1) % ShadowMode.entries.size]
                    stats.reset()
                }) { Text(shadowMode.label, fontSize = 12.sp) }
                Button(
                    onClick = {
                        val job = testJob
                        if (job != null) {
                            job.cancel(CancellationException("Stop was tapped"))
                        } else {
                            testJob = scope.launch {
                                try {
                                    ResultsLog.add("--- Shelf test, $columns columns")
                                    for (mode in ShadowMode.entries) {
                                        shadowMode = mode
                                        delay(500)
                                        testStep("Scroll, ${mode.label}", stats) {
                                            val current = lists[pager.currentPage % themes.size]
                                            current.animateScrollBy(4000f, tween(4000, easing = LinearEasing))
                                            current.animateScrollBy(-4000f, tween(2000, easing = LinearEasing))
                                        }
                                        testStep("Swipe, ${mode.label}", stats) {
                                            repeat(themes.size) {
                                                pager.animateScrollToPage(
                                                    pager.currentPage + 1,
                                                    animationSpec = tween(600),
                                                )
                                            }
                                        }
                                    }
                                    ResultsLog.add("Shelf test finished")
                                } catch (error: CancellationException) {
                                    ResultsLog.add("Shelf test stopped: ${error.message}")
                                    throw error
                                } finally {
                                    shadowMode = ShadowMode.PreRendered
                                    testJob = null
                                }
                            }
                        }
                    },
                ) { Text(if (running) "Stop" else "Run test", fontSize = 12.sp) }
            }
        }
    }
}

@Composable
private fun ShelfPage(
    theme: ShelfTheme,
    previous: String,
    next: String,
    list: LazyListState,
    assets: ShelfAssets,
    columns: Int,
    shadowMode: ShadowMode,
    seed: Int,
    touchEnabled: Boolean,
) {
    val shape = RoundedCornerShape(theme.cornerDp.dp)
    Box(
        Modifier
            .fillMaxSize()
            .background(theme.base),
    ) {
        val wallpaper = theme.wallpaper?.let { assets.wallpapers[it] }
        if (wallpaper != null) {
            Image(
                bitmap = wallpaper,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }

        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 180.dp, bottom = 96.dp),
            userScrollEnabled = touchEnabled,
        ) {
            items(60) { row ->
                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        repeat(columns) { column ->
                            val cover = assets.covers[(seed * 5 + row * columns + column) % assets.covers.size]
                            Book(cover, assets.shadow, shadowMode, Modifier.weight(1f))
                        }
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(22.dp)
                            .shadow(10.dp, shape)
                            .background(theme.bar, shape),
                    )
                }
            }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .safeDrawingPadding()
                .padding(start = 12.dp, end = 12.dp, top = 100.dp)
                .clip(shape)
                .background(theme.bar)
                .padding(16.dp),
        ) {
            Text(theme.name, color = theme.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }

        Row(
            Modifier
                .align(androidx.compose.ui.Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, theme.base)))
                .safeDrawingPadding()
                .padding(start = 24.dp, end = 24.dp, top = 40.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(previous, color = theme.text)
            Text(next, color = theme.text)
        }
    }
}

@Composable
private fun Book(cover: ImageBitmap, shadow: ImageBitmap, mode: ShadowMode, modifier: Modifier) {
    when (mode) {
        ShadowMode.PreRendered -> Canvas(modifier.aspectRatio(COVER_WIDTH / COVER_HEIGHT.toFloat())) {
            val width = size.width.toInt()
            val height = size.height.toInt()
            val pad = width * SHADOW_PAD / COVER_WIDTH
            drawImage(
                image = shadow,
                dstOffset = IntOffset(-pad + pad / 3, -pad + pad / 3),
                dstSize = IntSize(width + pad * 2, height + pad * 2),
            )
            drawImage(image = cover, dstSize = IntSize(width, height))
        }
        ShadowMode.Elevation -> Image(
            bitmap = cover,
            contentDescription = null,
            modifier = modifier
                .aspectRatio(COVER_WIDTH / COVER_HEIGHT.toFloat())
                .shadow(12.dp, RectangleShape),
            contentScale = ContentScale.FillBounds,
        )
        ShadowMode.LiveBlur -> Box(modifier.aspectRatio(COVER_WIDTH / COVER_HEIGHT.toFloat())) {
            Box(
                Modifier
                    .fillMaxSize()
                    .offset(4.dp, 4.dp)
                    .graphicsLayer { renderEffect = BlurEffect(24f, 24f, TileMode.Decal) }
                    .background(Color(0x99000000)),
            )
            Image(
                bitmap = cover,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds,
            )
        }
    }
}
