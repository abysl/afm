package com.abysl.afm

import androidx.lifecycle.SavedStateHandle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PairingScannerModelTest {
    private val savedState = SavedStateHandle()
    private val tickets = mutableListOf<String>()
    private val errors = mutableListOf<String>()
    private var canStart = true
    private val model = createModel(savedState)

    @Test
    fun grantedPermissionStartsCameraAndIgnoresRepeatedRequests() {
        assertEquals(PairingScanStep.Camera, model.requestScan(true, true))
        assertEquals(PairingScanStep.Camera, model.pendingStep.value)
        assertNull(model.requestScan(true, true))
        assertTrue(errors.isEmpty())
    }

    @Test
    fun requestsPermissionBeforeCamera() {
        assertEquals(PairingScanStep.Permission, model.requestScan(true, false))
        assertNull(model.requestScan(true, false))
        assertEquals(PairingScanStep.Camera, model.onPermissionResult(true))
        assertEquals(PairingScanStep.Camera, model.pendingStep.value)
        assertNull(model.onPermissionResult(true))
    }

    @Test
    fun deniedPermissionAllowsRetryAndReportsActionableError() {
        model.requestScan(true, false)
        assertNull(model.onPermissionResult(false))
        assertNull(model.pendingStep.value)
        assertEquals(
            listOf("Camera permission is needed to pair a device. Allow it in Settings, then select Pair device to retry."),
            errors,
        )
        assertEquals(PairingScanStep.Permission, model.requestScan(true, false))
    }

    @Test
    fun missingCameraDoesNotStartAnOperation() {
        assertNull(model.requestScan(false, true))
        assertNull(model.pendingStep.value)
        assertEquals(listOf("No camera is available on this device. Connect or enable a camera, then try again."), errors)
    }

    @Test
    fun disabledPairingDoesNotStartAnOperation() {
        canStart = false
        assertNull(model.requestScan(true, true))
        assertNull(model.requestScan(false, false))
        assertTrue(errors.isEmpty())
        assertNull(model.pendingStep.value)
    }

    @Test
    fun decodedTicketIsDeliveredExactlyOnceAndClearsPendingState() {
        model.requestScan(true, true)
        model.onScanResult("spirit1ticket")
        model.onScanResult("spirit1ticket")
        assertEquals(listOf("spirit1ticket"), tickets)
        assertNull(model.pendingStep.value)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun cancelledScanDoesNotPairOrReportAnErrorAndAllowsRetry() {
        model.requestScan(true, true)
        model.onScanResult(null)
        assertNull(model.pendingStep.value)
        assertTrue(tickets.isEmpty())
        assertTrue(errors.isEmpty())
        assertEquals(PairingScanStep.Camera, model.requestScan(true, true))
    }

    @Test
    fun permissionLaunchFailureClearsStateAndAllowsRetry() {
        model.requestScan(true, false)
        model.onLaunchFailed(PairingScanStep.Permission)
        assertNull(model.pendingStep.value)
        assertEquals(listOf("Unable to request camera permission. Check app settings and try again."), errors)
        assertEquals(PairingScanStep.Permission, model.requestScan(true, false))
    }

    @Test
    fun cameraLaunchFailureClearsStateAndAllowsRetry() {
        model.requestScan(true, true)
        model.onLaunchFailed(PairingScanStep.Camera)
        assertNull(model.pendingStep.value)
        assertEquals(listOf("Unable to open the camera. Check camera availability and try again."), errors)
        assertEquals(PairingScanStep.Camera, model.requestScan(true, true))
    }

    @Test
    fun resultsForInactiveStepsAreIgnored() {
        assertNull(model.onPermissionResult(false))
        model.onLaunchFailed(PairingScanStep.Camera)
        model.onScanResult("spirit1ticket")
        model.requestScan(true, false)
        model.onScanResult("spirit1ticket")
        model.onLaunchFailed(PairingScanStep.Camera)
        assertEquals(PairingScanStep.Permission, model.pendingStep.value)
        assertTrue(tickets.isEmpty())
        assertTrue(errors.isEmpty())
    }

    @Test
    fun restoredPermissionStepWaitsForResultInsteadOfLaunchingAgain() {
        model.requestScan(true, false)
        val restored = restoredModel()
        assertEquals(PairingScanStep.Permission, restored.pendingStep.value)
        assertNull(restored.requestScan(true, false))
        assertEquals(PairingScanStep.Camera, restored.onPermissionResult(true))
        restored.onScanResult("spirit1restored")
        assertEquals(listOf("spirit1restored"), tickets)
        assertNull(restored.pendingStep.value)
    }

    @Test
    fun restoredCameraStepCanBeCancelledAndRetried() {
        model.requestScan(true, true)
        val restored = restoredModel()
        assertEquals(PairingScanStep.Camera, restored.pendingStep.value)
        assertNull(restored.requestScan(true, true))
        restored.onScanResult(null)
        assertNull(restored.pendingStep.value)
        assertEquals(PairingScanStep.Camera, restored.requestScan(true, true))
    }

    private fun restoredModel(): PairingScannerModel = createModel(
        SavedStateHandle(savedState.keys().associateWith { savedState.get<Any?>(it) }),
    )

    private fun createModel(state: SavedStateHandle) = PairingScannerModel(
        savedState = state,
        canStart = { canStart },
        onTicket = tickets::add,
        onError = errors::add,
    )
}
