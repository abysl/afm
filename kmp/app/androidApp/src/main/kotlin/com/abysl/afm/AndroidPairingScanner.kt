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
fun AndroidPairDeviceButton(enabled: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled) {
        Text("Pair device")
    }
}

@Composable
fun rememberPairingScannerLauncher(model: PairingScannerModel): () -> Unit {
    val context = LocalContext.current
    val options = remember { pairingScanOptions() }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        model.onScanResult(result.contents)
    }
    val launchCamera = {
        launchScannerStep(PairingScanStep.Camera, model::onLaunchFailed) { scanner.launch(options) }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (model.onPermissionResult(granted) == PairingScanStep.Camera) launchCamera()
    }
    return {
        val step = model.requestScan(
            hasCamera = context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY),
            permissionGranted = context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
        when (step) {
            PairingScanStep.Permission -> launchScannerStep(step, model::onLaunchFailed) {
                permission.launch(Manifest.permission.CAMERA)
            }
            PairingScanStep.Camera -> launchCamera()
            null -> Unit
        }
    }
}

private fun launchScannerStep(step: PairingScanStep, onFailure: (PairingScanStep) -> Unit, launch: () -> Unit) {
    try {
        launch()
    } catch (_: Exception) {
        onFailure(step)
    }
}

private fun pairingScanOptions() = ScanOptions().apply {
    setDesiredBarcodeFormats(ScanOptions.QR_CODE)
    setBeepEnabled(false)
    setOrientationLocked(false)
    setPrompt("Scan a device pairing QR code")
}
