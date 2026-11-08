package com.tim.reader.ui

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.tim.reader.library.DeviceSettings
import com.tim.reader.stats.Reminders
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** The daily reminder switch and its time. Turning it on asks for permission to notify. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderSetting() {
    val context = LocalContext.current
    var choosingTime by remember { mutableStateOf(false) }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            DeviceSettings.updateReminder(true)
            Reminders.schedule(context)
        } else {
            Toast.makeText(context, "Reminders need permission to show notifications", Toast.LENGTH_LONG).show()
        }
    }
    val time = LocalTime.of(DeviceSettings.reminderMinutes / 60, DeviceSettings.reminderMinutes % 60)

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Daily reading reminder")
                Text(
                    "Only shows if you haven't read yet that day, or haven't reached a daily goal.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(DeviceSettings.reminderOn, { on ->
                if (on && !Reminders.canNotify(context)) {
                    askPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    DeviceSettings.updateReminder(on)
                    Reminders.schedule(context)
                }
            })
        }
        if (DeviceSettings.reminderOn) {
            TextButton({ choosingTime = true }) {
                Text("At ${time.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))}")
            }
        }
    }

    if (choosingTime) {
        val picker = rememberTimePickerState(time.hour, time.minute)
        AlertDialog(
            onDismissRequest = { choosingTime = false },
            title = { Text("Reminder time") },
            text = { TimePicker(picker) },
            confirmButton = {
                TextButton({
                    choosingTime = false
                    DeviceSettings.updateReminder(true, picker.hour * 60 + picker.minute)
                    Reminders.schedule(context)
                }) { Text("OK") }
            },
            dismissButton = { TextButton({ choosingTime = false }) { Text("Cancel") } },
        )
    }
}
