package com.abysl.afm

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import blue.rae.spirit.sdk.GroupState

@Composable
fun GroupScreen(
    group: GroupState,
    nodeId: String,
    ready: Boolean,
    adding: Boolean,
    onBack: () -> Unit,
    onAddDevice: (String, String) -> Unit,
    scanDeviceButton: (@Composable (String) -> Unit)? = null,
) {
    var code by remember(group.id) { mutableStateOf("") }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Groups") }
        }
        Text(group.name, style = MaterialTheme.typography.headlineMedium)
        Text("Every member can add devices to this group. Adding a device lets it see this group and its members.")
        Text("Members", style = MaterialTheme.typography.titleLarge)
        group.members.forEach { member ->
            val online = member.online
            val color = if (online) Color(0xFF2E7D32) else Color(0xFFB3261E)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(10.dp).background(color, CircleShape))
                Column(Modifier.weight(1f)) {
                    Text(if (member.id == nodeId) "${member.name} (this device)" else member.name)
                    Text(member.id, style = MaterialTheme.typography.bodySmall)
                }
                Text(if (online) "Online" else "Offline", color = color)
            }
        }
        scanDeviceButton?.invoke(group.id)
        OutlinedTextField(code, { code = it }, label = { Text("Paste code") }, modifier = Modifier.fillMaxWidth(), maxLines = 3)
        Button(enabled = ready && !adding && code.isNotBlank(), onClick = { onAddDevice(group.id, code) }) { Text("Add pasted code") }
        if (adding) Text("Adding device…")
        HorizontalDivider()
        Text("Files", style = MaterialTheme.typography.titleLarge)
        Text("Files coming soon. Group files will be visible to all current and future members.")
    }
}
