package com.abysl.afm

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
    var launchInFlight by rememberSaveable { mutableStateOf(false) }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        launchInFlight = false
        result.contents?.let(onTicket)
    }
    fun launchScanner() {
        try {
            scanner.launch(scanOptions)
        } catch (_: Exception) {
            launchInFlight = false
            onError("Unable to open the camera. Check camera availability and try again.")
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            launchScanner()
        } else {
            launchInFlight = false
            onError("Camera permission is needed to pair a device. Allow it in Settings, then select Pair device to retry.")
        }
    }

    Button(
        onClick = {
            if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
                onError("No camera is available on this device. Connect or enable a camera, then try again.")
            } else if (!launchInFlight) {
                launchInFlight = true
                if (context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    launchScanner()
                } else {
                    try {
                        permission.launch(Manifest.permission.CAMERA)
                    } catch (_: Exception) {
                        launchInFlight = false
                        onError("Unable to request camera permission. Check app settings and try again.")
                    }
                }
            }
        },
        enabled = enabled && !launchInFlight,
    ) {
        Text("Pair device")
    }
}
