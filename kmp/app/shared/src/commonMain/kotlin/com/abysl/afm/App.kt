package com.abysl.afm

import blue.rae.spirit.sdk.PairingState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
@Preview
fun App(
    store: BlobStore? = null,
    pairing: PairingState? = null,
    onRefreshTicket: () -> Unit = {},
    pairDeviceButton: (@Composable () -> Unit)? = null,
) {
    MaterialTheme {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.primaryContainer)
                .safeContentPadding()
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("AFM on ${getPlatform().name}", style = MaterialTheme.typography.titleMedium)
            pairing?.let {
                PairingPanel(
                    pairing = it,
                    onRefreshTicket = onRefreshTicket,
                    pairDeviceButton = pairDeviceButton,
                )
                Spacer(Modifier.height(24.dp))
            }
            if (store == null) {
                Text("file storage is not available on this platform")
            } else {
                AfmPanel(store)
            }
        }
    }
}

@Composable
private fun AfmPanel(store: BlobStore) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("hello, AFM") }
    var hash by remember { mutableStateOf("") }
    var fetched by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun attempt(block: suspend () -> Unit) = scope.launch {
        error = null
        try {
            block()
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("bytes to store") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row {
            Button(onClick = { attempt { hash = store.put(text.encodeToByteArray()) } }) {
                Text("put")
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { attempt { fetched = store.get(hash).decodeToString() } },
                enabled = hash.isNotEmpty(),
            ) {
                Text("get")
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = hash,
            onValueChange = { hash = it.trim() },
            label = { Text("blob hash") },
            modifier = Modifier.fillMaxWidth(),
        )
        fetched?.let {
            Spacer(Modifier.height(8.dp))
            Text("got back: $it")
        }
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
    }
}
