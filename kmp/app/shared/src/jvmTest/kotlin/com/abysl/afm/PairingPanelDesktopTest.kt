package com.abysl.afm

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import blue.rae.spirit.sdk.DeviceStatus
import blue.rae.spirit.sdk.PairingInvitation
import blue.rae.spirit.sdk.PairingState
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.EncodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class PairingPanelDesktopTest {
    @Test
    fun explainsWhenWindowIsTooNarrowForQr() = runComposeUiTest {
        setContent {
            Box(Modifier.width(20.dp)) {
                ExactQrMatrix(PairingInvitation("spirit1test", 177, ByteArray(177 * 177), 300))
            }
        }
        onNodeWithContentDescription("Pairing QR code").assertDoesNotExist()
        onNodeWithText("Enlarge this window to display the pairing QR code.").assertExists()
    }

    @Test
    fun rendersDecodableExactTicketAndRemovesExpiredQr() = runComposeUiTest {
        val ticket = "spirit1" + "AbCdEf0123456789-_".repeat(20)
        val matrix = QRCodeWriter().encode(ticket, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0))
        val invitation = PairingInvitation(
            ticket,
            matrix.width,
            ByteArray(matrix.width * matrix.height) { index ->
                if (matrix[index % matrix.width, index / matrix.width]) 1 else 0
            },
            300,
        )
        val state = mutableStateOf(PairingState(loading = false, invitation = invitation, invitationSecondsRemaining = 299))
        setContent { MaterialTheme { PairingPanel(state.value, onRefreshTicket = {}, onLeaveMesh = {}) } }
        val image = onNodeWithContentDescription("Pairing QR code").captureToImage().toPixelMap()
        val pixels = IntArray(image.width * image.height) { index ->
            val color = image[index % image.width, index / image.width]
            if (color.red < 0.5f) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val source = RGBLuminanceSource(image.width, image.height, pixels)
        assertEquals(ticket, QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text)
        runOnIdle { state.value = state.value.copy(invitationSecondsRemaining = 0) }
        onNodeWithContentDescription("Pairing QR code").assertDoesNotExist()
        onNodeWithText("Pairing QR expired. Refresh it before pairing a device.").assertIsDisplayed()
    }

    @Test
    fun showsPeerIdentityAndTextStatusWithoutDesktopCameraButton() = runComposeUiTest {
        var refreshes = 0
        setContent {
            MaterialTheme {
                PairingPanel(
                    PairingState(
                        loading = false,
                        peers = listOf(DeviceStatus("node-a", "Desktop", true), DeviceStatus("node-c", "Phone", false)),
                    ),
                    onRefreshTicket = { refreshes++ },
                    onLeaveMesh = {},
                )
            }
        }
        onNodeWithText("node-a").assertIsDisplayed()
        onNodeWithText("node-c").assertIsDisplayed()
        onNodeWithText("Online").assertIsDisplayed()
        onNodeWithText("Offline").assertIsDisplayed()
        onNodeWithText("Pair device").assertDoesNotExist()
        onNodeWithText("Leave mesh").assertDoesNotExist()
        onNodeWithText("Refresh QR").performClick()
        runOnIdle { assertEquals(1, refreshes) }
    }

    @Test
    fun leavingAMeshRequiresConfirmation() = runComposeUiTest {
        var leaves = 0
        val state = mutableStateOf(PairingState(loading = false, meshName = "AFM mesh"))
        setContent {
            MaterialTheme {
                PairingPanel(state.value, onRefreshTicket = {}, onLeaveMesh = { leaves++ })
            }
        }
        onNodeWithText("Leave mesh").performClick()
        onNodeWithText("Leave AFM mesh?").assertIsDisplayed()
        onNodeWithText("Cancel").performClick()
        onNodeWithText("Leave AFM mesh?").assertDoesNotExist()
        runOnIdle { assertEquals(0, leaves) }

        onNodeWithText("Leave mesh").performClick()
        onNodeWithText("Leave").performClick()
        onNodeWithText("Leave AFM mesh?").assertDoesNotExist()
        runOnIdle { assertEquals(1, leaves) }

        runOnIdle { state.value = state.value.copy(busy = true) }
        onNodeWithText("Leave mesh").assertIsNotEnabled()
        runOnIdle { state.value = state.value.copy(busy = false, meshName = null) }
        onNodeWithText("Leave mesh").assertDoesNotExist()
    }
}
