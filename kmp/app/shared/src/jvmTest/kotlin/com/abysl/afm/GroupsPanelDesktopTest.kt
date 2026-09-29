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
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import blue.rae.spirit.sdk.PairingInvitation
import blue.rae.spirit.sdk.MeshFailure
import blue.rae.spirit.sdk.MeshState
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
class GroupsPanelDesktopTest {
    @Test
    fun explainsWhenWindowIsTooNarrowForQr() = runComposeUiTest {
        setContent {
            Box(Modifier.width(20.dp)) {
                ExactQrMatrix(PairingInvitation("spirit1test", 177, ByteArray(177 * 177), 300))
            }
        }
        onNodeWithContentDescription("Join QR code").assertDoesNotExist()
        onNodeWithText("Enlarge this window to display the join QR code.").assertExists()
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
        val state = mutableStateOf(MeshState(loading = false, nodeId = "self", invitation = invitation, invitationSecondsRemaining = 299))
        setContent { MaterialTheme { GroupsPanel(state.value, {}, {}, {}, {}) } }
        onNodeWithText("Join a group").performClick()
        val image = onNodeWithContentDescription("Join QR code").captureToImage().toPixelMap()
        val pixels = IntArray(image.width * image.height) { index ->
            val color = image[index % image.width, index / image.width]
            if (color.red < 0.5f) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val source = RGBLuminanceSource(image.width, image.height, pixels)
        assertEquals(ticket, QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text)
        runOnIdle { state.value = state.value.copy(invitationSecondsRemaining = 0) }
        onNodeWithContentDescription("Join QR code").assertDoesNotExist()
        onNodeWithText("Join QR expired. Refresh it before joining a group.").assertIsDisplayed()
    }

    @Test
    fun filtersControlCharactersAndExplainsTypedInvalidInput() = runComposeUiTest {
        assertEquals("Invalid input. Use a valid ticket or a group name of 1–128 UTF-8 bytes with no control characters.", failureMessage(MeshFailure.Invalid, "Could not create group"))
        assertEquals(failureMessage(MeshFailure.Invalid, "Could not create group"), failureMessage(MeshFailure.Invalid, "Another message"))
        var created: String? = null
        setContent { MaterialTheme { GroupsPanel(MeshState(loading = false, nodeId = "self"), { created = it }, {}, {}, {}) } }
        onNodeWithText("New group").performClick()
        onNodeWithText("Group name").performTextInput("F\namily")
        onNodeWithText("Create group").performClick()
        runOnIdle { assertEquals("Family", created) }
    }
}
