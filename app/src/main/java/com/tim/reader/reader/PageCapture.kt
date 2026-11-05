package com.tim.reader.reader

import android.app.Activity
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Copies what's on screen, which is the only way to include a web view's page and highlights. */
suspend fun Activity.capturePage(): Bitmap? = suspendCancellableCoroutine { result ->
    val view = window.decorView
    if (view.width <= 0 || view.height <= 0) {
        result.resume(null)
        return@suspendCancellableCoroutine
    }
    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
    try {
        PixelCopy.request(
            window,
            bitmap,
            { code -> result.resume(bitmap.takeIf { code == PixelCopy.SUCCESS }) },
            Handler(Looper.getMainLooper()),
        )
    } catch (_: IllegalArgumentException) {
        result.resume(null)
    }
}
