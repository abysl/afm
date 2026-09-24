package com.abysl.afm

import blue.rae.spirit.sdk.PairingSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class PairingOwnerLifetimeDesktopTest {
    @Test
    fun keepsStoreOpenAfterPairingNodeOpenFailureAndClosesItOnOwnerTeardown() = runBlocking {
        val pairingNodeOpenFailed = CompletableDeferred<Unit>()
        val pairing = PairingSession({ error("Node directory is unavailable") }, "AFM mesh")
        val store = CloseTrackingBlobStore()
        val owner = launch {
            runPairingUntilOwnerCancellation(
                runPairing = {
                    pairing.run()
                    pairingNodeOpenFailed.complete(Unit)
                },
                onOwnerTeardown = { store.close() },
            )
        }

        pairingNodeOpenFailed.await()
        assertEquals("Could not open node", pairing.state.value.error)
        assertEquals("blob", store.put("blob".encodeToByteArray()))
        assertEquals("blob", store.get("blob").decodeToString())
        assertEquals(0, store.closeCalls)

        owner.cancelAndJoin()

        assertEquals(1, store.closeCalls)
    }

    private class CloseTrackingBlobStore : BlobStore {
        var closeCalls = 0
            private set
        private var closed = false

        override suspend fun put(bytes: ByteArray): String {
            check(!closed)
            return bytes.decodeToString()
        }

        override suspend fun get(hash: String): ByteArray {
            check(!closed)
            return hash.encodeToByteArray()
        }

        override suspend fun has(hash: String): Boolean {
            check(!closed)
            return false
        }

        override fun close() {
            closeCalls++
            closed = true
        }
    }
}
