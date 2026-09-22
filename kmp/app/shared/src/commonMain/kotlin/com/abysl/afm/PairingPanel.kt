package com.abysl.afm

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import blue.rae.spirit.sdk.DeviceStatus
import blue.rae.spirit.sdk.PairingInvitation
import blue.rae.spirit.sdk.PairingState
import kotlin.math.floor
import kotlin.math.min

private const val qrQuietZoneModules = 4

@Composable
fun PairingPanel(
    pairing: PairingState,
    onRefreshTicket: () -> Unit,
    pairDeviceButton: (@Composable () -> Unit)? = null,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("Device pairing", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text("Identity: ${pairing.name}")
        Text("Node ID: ${pairing.nodeId}")
        Text("Mesh: ${pairing.meshName ?: "Not paired"}")
        Text("On one Android device, scan each other device's QR to build your mesh.")
        Text("Existing independent meshes cannot be merged.")
        Spacer(Modifier.height(12.dp))
        PairingStatus(pairing)
        pairing.invitation?.let { invitation ->
            Spacer(Modifier.height(12.dp))
            InvitationPanel(
                invitation = invitation,
                secondsRemaining = pairing.invitationSecondsRemaining,
                busy = pairing.busy,
                onRefreshTicket = onRefreshTicket,
            )
        } ?: run {
            Spacer(Modifier.height(12.dp))
            Button(onClick = onRefreshTicket, enabled = !pairing.loading && !pairing.busy) {
                Text("Refresh QR")
            }
        }
        pairDeviceButton?.let { button ->
            Spacer(Modifier.height(12.dp))
            button()
        }
        Spacer(Modifier.height(20.dp))
        Text("Paired devices", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        if (pairing.peers.isEmpty()) {
            Text("No paired devices yet.")
        } else {
            pairing.peers.forEach { peer ->
                key(peer.id) {
                    PeerStatus(peer)
                }
            }
        }
    }
}

@Composable
private fun PairingStatus(pairing: PairingState) {
    when {
        pairing.loading -> Text("Waiting for device pairing status…")
        pairing.error != null -> Text(pairing.error.orEmpty(), color = MaterialTheme.colorScheme.error)
        pairing.busy -> Text("Updating device pairing…")
        pairing.notice != null -> Text(pairing.notice.orEmpty())
    }
}

@Composable
private fun InvitationPanel(
    invitation: PairingInvitation,
    secondsRemaining: Int,
    busy: Boolean,
    onRefreshTicket: () -> Unit,
) {
    val hasUsableMatrix = secondsRemaining > 0 && isValidQrMatrix(invitation.width, invitation.modules)
    if (hasUsableMatrix) {
        ExactQrMatrix(invitation)
        Spacer(Modifier.height(8.dp))
        Text("Ticket expires in $secondsRemaining seconds.")
        Text("This ticket is a single-use, 5 minute bearer secret. Show it only to trusted devices.")
    } else if (secondsRemaining <= 0) {
        Text("Pairing QR expired. Refresh it before pairing a device.")
    } else {
        Text("Pairing QR data is unavailable. Refresh it to create a new code.")
    }
    Spacer(Modifier.height(8.dp))
    Text("Ticket lifetime: ${invitation.lifetimeSeconds} seconds.")
    Spacer(Modifier.height(8.dp))
    Button(onClick = onRefreshTicket, enabled = !busy) {
        Text("Refresh QR")
    }
}

@Composable
internal fun ExactQrMatrix(invitation: PairingInvitation) {
    val totalWidth = invitation.width + qrQuietZoneModules * 2
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        if (with(LocalDensity.current) { maxWidth.toPx() } < totalWidth) {
            Text("Enlarge this window to display the pairing QR code.")
            return@BoxWithConstraints
        }
        Canvas(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .fillMaxWidth()
                .aspectRatio(1f)
                .semantics { contentDescription = "Pairing QR code" },
        ) {
            val modulePixels = floor(min(size.width, size.height) / totalWidth).toInt()
            if (modulePixels == 0) return@Canvas
            val renderedPixels = modulePixels * totalWidth
            val left = floor((size.width - renderedPixels) / 2f)
            val top = floor((size.height - renderedPixels) / 2f)
            drawRect(
                color = Color.White,
                topLeft = Offset(left, top),
                size = Size(renderedPixels.toFloat(), renderedPixels.toFloat()),
            )
            for (row in 0 until invitation.width) {
                for (column in 0 until invitation.width) {
                    if (qrMatrixModule(invitation.width, invitation.modules, row, column)) {
                        drawRect(
                            color = Color.Black,
                            topLeft = Offset(
                                left + (column + qrQuietZoneModules) * modulePixels,
                                top + (row + qrQuietZoneModules) * modulePixels,
                            ),
                            size = Size(modulePixels.toFloat(), modulePixels.toFloat()),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PeerStatus(peer: DeviceStatus) {
    val online = peer.online
    val statusText = if (online) "Online" else "Offline"
    val statusColor = if (online) Color(0xFF2E7D32) else Color(0xFFB3261E)
    Row(
        modifier = Modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(statusColor, CircleShape),
        )
        Column(Modifier.weight(1f)) {
            Text(peer.name)
            Text(peer.id, style = MaterialTheme.typography.bodySmall)
        }
        Text(statusText, color = statusColor)
    }
}

internal fun isValidQrMatrix(width: Int, modules: ByteArray): Boolean =
    width > 0 && width <= 46340 && modules.size == width * width

internal fun qrMatrixModule(width: Int, modules: ByteArray, row: Int, column: Int): Boolean {
    require(isValidQrMatrix(width, modules))
    require(row in 0 until width)
    require(column in 0 until width)
    return modules[row * width + column].toInt() != 0
}
