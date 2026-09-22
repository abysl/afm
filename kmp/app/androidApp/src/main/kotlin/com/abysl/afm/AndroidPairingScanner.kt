package com.abysl.afm

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

@Composable
fun AndroidPairDeviceButton(
    enabled: Boolean,
    onTicket: (String) -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val scanOptions = remember {
        ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setBeepEnabled(false)
            setOrientationLocked(false)
            setPrompt("Scan a device pairing QR code")
        }
    }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let(onTicket)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            try {
                scanner.launch(scanOptions)
            } catch (_: Exception) {
                onError("Unable to open the camera. Check camera availability and try again.")
            }
        } else {
            onError("Camera permission is needed to pair a device. Allow it in Settings, then select Pair device to retry.")
        }
    }

    Button(
        onClick = {
            if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
                onError("No camera is available on this device. Connect or enable a camera, then try again.")
            } else if (context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                try {
                    scanner.launch(scanOptions)
                } catch (_: Exception) {
                    onError("Unable to open the camera. Check camera availability and try again.")
                }
            } else {
                permission.launch(Manifest.permission.CAMERA)
            }
        },
        enabled = enabled,
    ) {
        Text("Pair device")
    }
}
