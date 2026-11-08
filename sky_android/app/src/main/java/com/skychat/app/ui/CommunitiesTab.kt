package com.skychat.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.skychat.app.SkyApp
import org.json.JSONArray

/**
 * Communities: an announcement group (only admins post) plus the groups that were added to it.
 * Joining the announcement group (invite link, or added by an admin) makes you a member of the community: you see the list of its
 * groups here and join the ones you want. Leaving the community means leaving all of its groups.
 */
@Composable
fun CommunitiesTab(app: SkyApp, onOpen: (String) -> Unit) {
    val groups by app.db.dao().groups().collectAsState(initial = emptyList())
    val me = app.prefs.phone ?: ""
    val communities = groups.filter { it.community && it.members.split(",").contains(me) }
    var newDlg by remember { mutableStateOf(false) }
    var addTo by remember { mutableStateOf<String?>(null) }          // community id an admin is adding a group to
    var removeAsk by remember { mutableStateOf<Pair<String, String>?>(null) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Button(onClick = { newDlg = true }, colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
                Text("＋ " + t("New community"))
            }
        }
        if (communities.isEmpty()) item {
            Text(t("A community brings related groups together under one announcement group. Create one, or open a community invite link."),
                color = Color.Gray, fontSize = 14.sp)
        }
        items(communities, key = { it.gid }) { c ->
            val subs = remember(c.subs) {
                try {
                    val a = JSONArray(c.subs.ifEmpty { "[]" })
                    (0 until a.length()).map { a.getJSONObject(it).let { j -> j.getString("g") to j.optString("n") } }
                } catch (e: Exception) { emptyList() }
            }
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Sky.surface)) {
                Column(Modifier.padding(12.dp)) {
                    Row(Modifier.fillMaxWidth().clickable { onOpen(c.gid) }, verticalAlignment = Alignment.CenterVertically) {
                        Text("🏘", fontSize = 26.sp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.name, fontWeight = FontWeight.Bold, maxLines = 1)
                            Text(t("Announcements") + " · ${c.members.split(",").count { it.isNotEmpty() }} " + t("members"), fontSize = 12.sp, color = Color.Gray)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    subs.forEach { (gid, name) ->
                        val joined = groups.firstOrNull { it.gid == gid }?.members?.split(",")?.contains(me) == true
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("# " + name.ifEmpty { gid }, Modifier.weight(1f).clickable(enabled = joined) { onOpen(gid) }, maxLines = 1)
                            if (joined) Text("✓", color = Accent)
                            else TextButton(onClick = { app.client.joinCommunityGroup(c.gid, gid) }) { Text(t("Join")) }
                            if (c.isAdmin(me)) TextButton(onClick = { removeAsk = c.gid to gid }) { Text(t("Remove"), color = Color(0xFFD32F2F)) }
                        }
                    }
                    if (subs.isEmpty()) Text(t("No groups in this community yet."), fontSize = 13.sp, color = Color.Gray)
                    Row {
                        if (c.isAdmin(me)) TextButton(onClick = { addTo = c.gid }) { Text(t("Add a group")) }
                        TextButton(onClick = { app.client.leaveGroup(c.gid) }) { Text(t("Leave community"), color = Color(0xFFD32F2F)) }
                    }
                }
            }
        }
    }

    if (newDlg) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newDlg = false },
            title = { Text(t("New community")) },
            text = { OutlinedTextField(name, { if (it.length <= 64) name = it }, label = { Text(t("Community name")) }, singleLine = true) },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { app.client.createCommunity(name); newDlg = false }) { Text(t("Create")) } },
            dismissButton = { TextButton(onClick = { newDlg = false }) { Text(t("Cancel")) } }
        )
    }
    addTo?.let { cid ->
        val mine = groups.filter { it.isAdmin(me) && it.members.split(",").contains(me) && !it.community && it.parent.isEmpty() }
        AlertDialog(
            onDismissRequest = { addTo = null },
            title = { Text(t("Add a group")) },
            text = {
                if (mine.isEmpty()) Text(t("You are not an admin of a group that is free to add."))
                else Column { mine.forEach { g ->
                    Text(g.name, Modifier.fillMaxWidth().clickable { app.client.addGroupToCommunity(cid, g.gid); addTo = null }.padding(vertical = 10.dp))
                } }
            },
            confirmButton = {}, dismissButton = { TextButton(onClick = { addTo = null }) { Text(t("Cancel")) } }
        )
    }
    removeAsk?.let { (cid, gid) ->
        AlertDialog(
            onDismissRequest = { removeAsk = null },
            title = { Text(t("Remove this group from the community?")) },
            text = { Text(t("Its members stay in it as an ordinary group.")) },
            confirmButton = { TextButton(onClick = { app.client.removeGroupFromCommunity(cid, gid); removeAsk = null }) { Text(t("Remove"), color = Color(0xFFD32F2F)) } },
            dismissButton = { TextButton(onClick = { removeAsk = null }) { Text(t("Cancel")) } }
        )
    }
}
