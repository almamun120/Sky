package com.skychat.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.skychat.app.data.ChatState

/** Dialogs shared by the chat list (long press) and the chat screen (⋮ menu). */

@Composable
fun MuteDialog(group: Boolean, onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    val hour = 3_600_000L
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("Mute notifications")) },
        text = {
            Column {
                if (group) Text(t("You will still be notified when someone @-mentions you."),
                    fontSize = 13.sp, color = Color.Gray, modifier = Modifier.padding(bottom = 8.dp))
                listOf("8 hours" to 8 * hour, "1 week" to 168 * hour, t("Always") to -1L).forEach { (label, ms) ->
                    Text(label, Modifier.fillMaxWidth().padding(vertical = 12.dp).clickable {
                        onPick(if (ms < 0) ChatState.FOREVER else System.currentTimeMillis() + ms)
                    })
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("Cancel")) } }
    )
}

@Composable
fun BlockDialog(name: String, block: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (block) "Block $name?" else "Unblock $name?") },
        text = {
            Text(if (block)
                "$name will no longer be able to message or call you, and will not see when you are online or typing. " +
                "They are not told. Messages they send while blocked are never delivered, even after you unblock. " +
                t("Their messages in groups are not affected.")
            else "$name will be able to message and call you again.")
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(if (block) t("Block") else t("Unblock")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("Cancel")) } }
    )
}

@Composable
fun ReportDialog(
    name: String, blockedAlready: Boolean,
    onSend: (reason: String, withMessages: Boolean, alsoBlock: Boolean) -> Unit, onDismiss: () -> Unit
) {
    val reasons = listOf(t("Spam"), t("Harassment or abuse"), t("Scam or fraud"), t("Other"))
    var reason by remember { mutableStateOf(reasons[0]) }
    var withMsgs by remember { mutableStateOf(true) }
    var alsoBlock by remember { mutableStateOf(!blockedAlready) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Report $name") },
        text = {
            Column {
                reasons.forEach { r ->
                    Row(Modifier.fillMaxWidth().clickable { reason = r }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = reason == r, onClick = { reason = r })
                        Text(r)
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Row(Modifier.fillMaxWidth().clickable { withMsgs = !withMsgs }, verticalAlignment = Alignment.Top) {
                    Checkbox(withMsgs, { withMsgs = it })
                    Column(Modifier.padding(top = 12.dp)) {
                        Text(t("Include their last 5 messages"))
                        Text("Messages are end-to-end encrypted. If you tick this, this phone sends those messages " +
                            "to Sky in readable form. Nothing else from the chat is sent.",
                            fontSize = 12.sp, color = Color.Gray)
                    }
                }
                if (!blockedAlready) Row(Modifier.fillMaxWidth().clickable { alsoBlock = !alsoBlock },
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(alsoBlock, { alsoBlock = it })
                    Text("Also block $name")
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSend(reason, withMsgs, alsoBlock && !blockedAlready) }) { Text(t("Report")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("Cancel")) } }
    )
}
