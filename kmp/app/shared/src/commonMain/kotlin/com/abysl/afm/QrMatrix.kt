package com.abysl.afm

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import blue.rae.spirit.sdk.PairingInvitation
import kotlin.math.floor
import kotlin.math.min

private const val qrQuietZoneModules = 4

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

internal fun isValidQrMatrix(width: Int, modules: ByteArray): Boolean =
    width > 0 && width <= 46340 && modules.size == width * width

internal fun qrMatrixModule(width: Int, modules: ByteArray, row: Int, column: Int): Boolean {
    require(isValidQrMatrix(width, modules))
    require(row in 0 until width)
    require(column in 0 until width)
    return modules[row * width + column].toInt() != 0
}
