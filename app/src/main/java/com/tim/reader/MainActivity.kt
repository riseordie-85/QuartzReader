package com.tim.reader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import com.tim.reader.library.DeviceSettings
import com.tim.reader.stats.AchievementCenter
import com.tim.reader.stats.ReadingTracker
import com.tim.reader.stats.Reminders
import com.tim.reader.theme.BookFonts
import com.tim.reader.theme.ThemeFiles
import com.tim.reader.ui.ReaderApp

class MainActivity : ComponentActivity() {
    private val incoming = mutableStateOf<List<Uri>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        ResultsLog.init(this)
        DeviceSettings.load(this)
        ThemeFiles.init(this)
        // Alarms don't survive a phone update to the app, so the reminder is set again on every start.
        Reminders.schedule(this)
        BookFonts.init(this)
        if (savedInstanceState == null) incoming.value = urisFrom(intent)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    ReaderApp(incoming.value) { incoming.value = emptyList() }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ReadingTracker.resume(this)
        AchievementCenter.check(this)
    }

    override fun onPause() {
        super.onPause()
        ReadingTracker.pause(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incoming.value = urisFrom(intent)
    }

    /** Files sent from other apps through Share or Open With. */
    private fun urisFrom(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_VIEW -> listOfNotNull(intent.data)
        Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        else -> emptyList()
    }
}
