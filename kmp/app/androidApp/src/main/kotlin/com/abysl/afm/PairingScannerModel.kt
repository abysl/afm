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
    private val onTicket: (String, String) -> Unit,
    private val onError: (String) -> Unit,
) {
    val pendingStep: StateFlow<PairingScanStep?> = savedState.getStateFlow(PENDING_STEP, null)

    fun requestScan(meshId: String, hasCamera: Boolean, permissionGranted: Boolean): PairingScanStep? {
        if (!canStart() || pendingStep.value != null || meshId.isBlank()) return null
        if (!hasCamera) {
            onError("No camera is available on this device. Connect or enable a camera, then try again.")
            return null
        }
        savedState[PENDING_GROUP] = meshId
        return begin(if (permissionGranted) PairingScanStep.Camera else PairingScanStep.Permission)
    }

    fun onPermissionResult(granted: Boolean): PairingScanStep? {
        if (pendingStep.value != PairingScanStep.Permission) return null
        if (granted) return begin(PairingScanStep.Camera)
        fail("Camera permission is needed to add a device. Allow it in Settings, then select Scan QR code to retry.")
        return null
    }

    fun onScanResult(ticket: String?) {
        if (pendingStep.value != PairingScanStep.Camera) return
        val group = savedState.get<String>(PENDING_GROUP)
        savedState[PENDING_STEP] = null
        savedState[PENDING_GROUP] = null
        if (group != null && ticket != null) onTicket(group, ticket)
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
        savedState[PENDING_GROUP] = null
        onError(message)
    }

    private companion object {
        const val PENDING_GROUP = "pairingScanner.pendingGroup"
        const val PENDING_STEP = "pairingScanner.pendingStep"
    }
}
