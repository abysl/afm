package com.abysl.afm

import android.app.Application
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import blue.rae.spirit.sdk.AndroidNodeContext
import blue.rae.spirit.sdk.PairingSession
import blue.rae.spirit.sdk.SpiritNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val model = ViewModelProvider(this)[AfmViewModel::class.java]
        setContent {
            val pairing by model.pairing.state.collectAsState()
            App(
                store = model.store,
                pairing = pairing,
                onRefreshTicket = model::refreshTicket,
                pairDeviceButton = {
                    AndroidPairDeviceButton(
                        enabled = !pairing.loading && !pairing.busy,
                        onTicket = model::pair,
                        onError = model.pairing::reportError,
                    )
                },
            )
        }
    }
}

class AfmViewModel(application: Application) : AndroidViewModel(application) {
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

    init {
        viewModelScope.launch {
            try {
                pairing.run()
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { store?.close() }
            }
        }
    }

    fun pair(ticket: String) {
        viewModelScope.launch { pairing.pair(ticket) }
    }

    fun refreshTicket() {
        viewModelScope.launch { pairing.refreshTicket() }
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}