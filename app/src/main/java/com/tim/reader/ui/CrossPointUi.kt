package com.tim.reader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.tim.reader.sync.CrossPointSettings
import com.tim.reader.sync.KoSyncClient
import com.tim.reader.sync.SyncException
import com.tim.reader.sync.SyncOffer
import com.tim.reader.sync.md5Hex
import com.tim.reader.sync.normalizeServer
import kotlinx.coroutines.launch

@Composable
private fun SettingSwitch(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked, onChange)
    }
}

/** Settings for progress sync with CrossPoint and KOReader devices. */
@Composable
fun CrossPointSetting() {
    val context = LocalContext.current
    // Loads once, before the fields below read what's saved.
    CrossPointSettings.ensureLoaded(context)
    val scope = rememberCoroutineScope()
    var server by remember { mutableStateOf(CrossPointSettings.server) }
    var username by remember { mutableStateOf(CrossPointSettings.username) }
    var password by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var problem by remember { mutableStateOf(false) }

    fun account(create: Boolean) {
        val base = normalizeServer(server)
        val name = username.trim()
        val key = md5Hex(password.toByteArray())
        working = true
        message = null
        scope.launch {
            try {
                if (create) KoSyncClient.register(base, name, key)
                KoSyncClient.authenticate(base, name, key)
                CrossPointSettings.signIn(server, name, key)
                password = ""
                problem = false
                message = if (create) "Account made. Sign in with the same details on your other devices." else null
            } catch (error: SyncException) {
                problem = true
                message = error.message
            } finally {
                working = false
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SettingSwitch(
            "Sync reading progress",
            "Shares your place in each book with CrossPoint and KOReader devices signed in to the same sync server. " +
                "Books match when they're the same file on each device.",
            CrossPointSettings.enabled,
        ) { CrossPointSettings.updateEnabled(it) }
        if (!CrossPointSettings.enabled) return@Column

        if (CrossPointSettings.isSignedIn) {
            Text(
                "Signed in as ${CrossPointSettings.username} on ${CrossPointSettings.baseUrl.substringAfter("://")}",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton({ CrossPointSettings.signOut() }) { Text("Sign out") }
        } else {
            OutlinedTextField(
                server,
                { server = it },
                Modifier.fillMaxWidth(),
                label = { Text("Sync server") },
                placeholder = { Text("sync.crosspointreader.com") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            Text(
                "Leave it empty for the free CrossPoint server. KOReader's is sync.koreader.rocks, and a server of your own works too. " +
                    "Accounts belong to one server.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (normalizeServer(server).startsWith("http://")) {
                Text(
                    "This address isn't encrypted, so only use it on your own network.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            OutlinedTextField(
                username,
                { username = it },
                Modifier.fillMaxWidth(),
                label = { Text("Username") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
            )
            OutlinedTextField(
                password,
                { password = it },
                Modifier.fillMaxWidth(),
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            val ready = username.isNotBlank() && password.isNotEmpty() && !working
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ account(create = false) }, enabled = ready) { Text(if (working) "Checking" else "Sign in") }
                OutlinedButton({ account(create = true) }, enabled = ready) { Text("Make an account") }
            }
            Text(
                "Use the same username and password as on your CrossPoint or KOReader devices. " +
                    "Only a scrambled form of the password is sent or kept.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        message?.let {
            Text(
                it,
                color = if (problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        SettingSwitch(
            "Jump ahead without asking",
            "When another device is further on in a book, go there as it opens. Otherwise you're asked first.",
            CrossPointSettings.autoJump,
        ) { CrossPointSettings.updateAutoJump(it) }
        SettingSwitch(
            "Sync highlights and notes",
            "Shares highlights in EPUBs with CrossPoint devices, along with their notes. Only CrossPoint's sync server keeps them, " +
                "and they include the highlighted words. On CrossPoint, turn on Sync Clippings too.",
            CrossPointSettings.syncHighlights,
        ) { CrossPointSettings.updateSyncHighlights(it) }
        SettingSwitch(
            "Send book titles",
            "Lets the server show which books you're reading. Off, it gets only a code made from each file and how far in you are.",
            CrossPointSettings.sendTitles,
        ) { CrossPointSettings.updateSendTitles(it) }
        SettingSwitch(
            "Match books by file name",
            "For when each device has a different copy of a book under the same file name. " +
                "Set it the same way on your other devices.",
            CrossPointSettings.matchByName,
        ) { CrossPointSettings.updateMatchByName(it) }
    }
}

/** Asks whether to go to another device's place. */
@Composable
fun SyncOfferDialog(offer: SyncOffer, onGo: () -> Unit, onStay: () -> Unit) {
    AlertDialog(
        // A stray tap beside the question shouldn't answer it. Back still means stay.
        onDismissRequest = onStay,
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text("Go to ${offer.percent}%?") },
        text = { Text("${offer.device} is further on in this book.") },
        confirmButton = { TextButton(onGo) { Text("Go there") } },
        dismissButton = { TextButton(onStay) { Text("Stay here") } },
    )
}
