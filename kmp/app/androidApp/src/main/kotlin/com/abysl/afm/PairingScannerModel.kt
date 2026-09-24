package com.abysl.afm

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.flow.StateFlow

enum class PairingScanStep {
    Permission,
    Camera,
}

class PairingScannerModel(
    private val savedState: SavedStateHandle,
    private val canStart: () -> Boolean,
    private val onTicket: (String) -> Unit,
    private val onError: (String) -> Unit,
) {
    val pendingStep: StateFlow<PairingScanStep?> = savedState.getStateFlow(PENDING_STEP, null)

    fun requestScan(hasCamera: Boolean, permissionGranted: Boolean): PairingScanStep? {
        if (!canStart() || pendingStep.value != null) return null
        if (!hasCamera) {
            onError("No camera is available on this device. Connect or enable a camera, then try again.")
            return null
        }
        return begin(if (permissionGranted) PairingScanStep.Camera else PairingScanStep.Permission)
    }

    fun onPermissionResult(granted: Boolean): PairingScanStep? {
        if (pendingStep.value != PairingScanStep.Permission) return null
        if (granted) return begin(PairingScanStep.Camera)
        fail("Camera permission is needed to pair a device. Allow it in Settings, then select Pair device to retry.")
        return null
    }

    fun onScanResult(ticket: String?) {
        if (pendingStep.value != PairingScanStep.Camera) return
        savedState[PENDING_STEP] = null
        ticket?.let(onTicket)
    }

    fun onLaunchFailed(step: PairingScanStep) {
        if (pendingStep.value != step) return
        val message = when (step) {
            PairingScanStep.Permission -> "Unable to request camera permission. Check app settings and try again."
            PairingScanStep.Camera -> "Unable to open the camera. Check camera availability and try again."
        }
        fail(message)
    }

    private fun begin(step: PairingScanStep): PairingScanStep {
        savedState[PENDING_STEP] = step
        return step
    }

    private fun fail(message: String) {
        savedState[PENDING_STEP] = null
        onError(message)
    }

    private companion object {
        const val PENDING_STEP = "pairingScanner.pendingStep"
    }
}
