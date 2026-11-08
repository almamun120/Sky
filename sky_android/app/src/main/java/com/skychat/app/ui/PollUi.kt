package com.skychat.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.skychat.app.SkyApp
import com.skychat.app.data.Msg
import org.json.JSONObject

/**
 * A poll inside a chat. The poll is a message of kind "poll" (body = JSON {"q": question, "o": [options], "m": 1 if several answers are allowed}).
 * Every vote is an encrypted control message ("vote") to everybody in the chat, so each phone counts for itself; the server sees nothing.
 */
@Composable
fun PollBubble(app: SkyApp, m: Msg) {
    val me = app.prefs.phone ?: ""
    val poll = remember(m.body) { try { JSONObject(m.body) } catch (e: Exception) { null } } ?: return
    val arr = poll.optJSONArray("o") ?: return
    val opts = (0 until arr.length()).map { arr.optString(it) }
    val multi = poll.optInt("m") == 1
    val votes by app.db.dao().votes(m.id).collectAsState(initial = emptyList())
    val byVoter = votes.associate { v -> v.voter to v.choices.split(",").mapNotNull { it.toIntOrNull() }.toSet() }
    val mine = byVoter[me] ?: emptySet()
    val counts = opts.indices.map { i -> byVoter.values.count { i in it } }
    val voters = byVoter.values.count { it.isNotEmpty() }
    Column(Modifier.widthIn(min = 230.dp)) {
        Text("📊 " + poll.optString("q"), fontWeight = FontWeight.Bold)
        Text(if (multi) t("Select one or more") else t("Select one"), fontSize = 12.sp, color = Color.Gray)
        Spacer(Modifier.height(6.dp))
        opts.forEachIndexed { i, o ->
            val next = if (multi) (if (i in mine) mine - i else mine + i) else (if (i in mine) emptySet() else setOf(i))
            Row(Modifier.fillMaxWidth().clickable { app.client.votePoll(m, next.toList()) }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (multi) Checkbox(i in mine, null) else RadioButton(i in mine, null)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(o)
                    val frac = if (voters == 0) 0f else counts[i].toFloat() / voters
                    Box(Modifier.fillMaxWidth().height(4.dp).background(Sky.divider)) {
                        Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(Accent))
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(counts[i].toString(), fontSize = 13.sp)
            }
        }
        Text("$voters " + t("votes"), fontSize = 12.sp, color = Color.Gray)
    }
}

/** Question, 2 to 12 options, "allow several answers". */
@Composable
fun PollDialog(onDismiss: () -> Unit, onCreate: (String, List<String>, Boolean) -> Unit) {
    var q by remember { mutableStateOf("") }
    var opts by remember { mutableStateOf(listOf("", "")) }
    var multi by remember { mutableStateOf(false) }
    val filled = opts.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("Create poll")) },
        text = {
            Column {
                OutlinedTextField(q, { if (it.length <= 200) q = it }, Modifier.fillMaxWidth(), label = { Text(t("Question")) })
                opts.forEachIndexed { i, o ->
                    OutlinedTextField(o, { v -> if (v.length <= 100) opts = opts.toMutableList().also { it[i] = v } },
                        Modifier.fillMaxWidth().padding(top = 6.dp), label = { Text(t("Option") + " ${i + 1}") }, singleLine = true)
                }
                if (opts.size < 12) TextButton(onClick = { opts = opts + "" }) { Text(t("Add option")) }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(t("Allow several answers"), Modifier.weight(1f))
                    Switch(multi, { multi = it })
                }
            }
        },
        confirmButton = {
            TextButton(enabled = q.isNotBlank() && filled.size >= 2, onClick = { onCreate(q.trim(), filled, multi) }) { Text(t("Send")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("Cancel")) } }
    )
}
