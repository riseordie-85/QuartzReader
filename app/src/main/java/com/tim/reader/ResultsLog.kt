package com.tim.reader

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateListOf
import java.io.File

/** Everything the tests measure lands here, ready to paste back into the chat. */
object ResultsLog {
    val lines = mutableStateListOf<String>()
    private val main = Handler(Looper.getMainLooper())
    private var file: File? = null

    fun init(context: Context) {
        if (file != null) return
        val target = File(context.filesDir, "results.txt")
        file = target
        if (target.exists()) lines.addAll(target.readLines())
        val memory = context.getSystemService(ActivityManager::class.java)
        val metrics = context.resources.displayMetrics
        add(
            "Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}, " +
                "screen ${metrics.widthPixels}x${metrics.heightPixels}, heap ${memory.largeMemoryClass} MB",
        )
    }

    fun add(line: String) {
        android.util.Log.i("QuartzReader", line)
        main.post {
            lines.add(line)
            file?.appendText(line + "\n")
        }
    }

    fun clear() {
        lines.clear()
        file?.delete()
    }
}

inline fun <T> timed(label: String, block: () -> T): T {
    val start = System.nanoTime()
    val result = block()
    ResultsLog.add("$label: ${(System.nanoTime() - start) / 1_000_000} ms")
    return result
}
