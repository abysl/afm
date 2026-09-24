package com.abysl.afm

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import blue.rae.spirit.sdk.AndroidNodeContext
import blue.rae.spirit.sdk.PairingSession
import blue.rae.spirit.sdk.SpiritNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AfmViewModel(application: Application, savedStateHandle: SavedStateHandle) : AndroidViewModel(application) {
    val store = openBlobStore(application.filesDir.resolve("afm").path)
    val pairing = PairingSession(
        nodeFactory = {
            withContext(Dispatchers.IO) { AndroidNodeContext.initialize(application) }
            SpiritNode.open(
                application.noBackupFilesDir.resolve("afm-node").path,
                "Android ${Build.MODEL.filter { it.isLetterOrDigit() || it == ' ' }.take(24)}",
            )
        },
        meshName = "AFM mesh",
    )
    val scanner = PairingScannerModel(
        savedState = savedStateHandle,
        canStart = { pairing.state.value.let { !it.loading && !it.busy } },
        onTicket = ::pair,
        onError = pairing::reportError,
    )

    init {
        viewModelScope.launch {
            runPairingUntilOwnerCancellation(
                pairing::run,
                onOwnerTeardown = {
                    withContext(Dispatchers.IO) { store?.close() }
                },
            )
        }
    }

    fun pair(ticket: String) {
        viewModelScope.launch { pairing.pair(ticket) }
    }

    fun refreshTicket() {
        viewModelScope.launch { pairing.refreshTicket() }
    }
}
