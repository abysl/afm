package com.abysl.afm

import blue.rae.spirit.sdk.MeshSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class MeshOwnerLifetimeDesktopTest {
    @Test
    fun keepsStoreOpenAfterNodeOpenFailureAndClosesItOnOwnerTeardown() = runBlocking {
        val nodeOpenFailed = CompletableDeferred<Unit>()
        val mesh = MeshSession({ error("Node directory is unavailable") })
        val store = CloseTrackingBlobStore()
        val owner = launch {
            runMeshUntilOwnerCancellation(
                runMesh = {
                    mesh.run()
                    nodeOpenFailed.complete(Unit)
                },
                onOwnerTeardown = { store.close() },
            )
        }

        nodeOpenFailed.await()
        assertEquals("Could not open node", mesh.state.value.error)
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
