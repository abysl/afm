package com.abysl.afm

import blue.rae.spirit.sdk.FakeMeshFiles
import blue.rae.spirit.sdk.ImportedBlob
import blue.rae.spirit.sdk.LeftMesh
import blue.rae.spirit.sdk.MeshFiles
import blue.rae.spirit.sdk.MeshMember
import blue.rae.spirit.sdk.MeshNode
import blue.rae.spirit.sdk.MeshNodeException
import blue.rae.spirit.sdk.MeshFailure
import blue.rae.spirit.sdk.MeshSession
import blue.rae.spirit.sdk.MeshStatus
import blue.rae.spirit.sdk.NodePeer
import blue.rae.spirit.sdk.NodePong
import blue.rae.spirit.sdk.NodeStatus
import blue.rae.spirit.sdk.PairingInvitation
import java.nio.file.Files
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GroupCatalogsTest {
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)
    private val c = "c".repeat(64)
    private val mesh = "mesh1_${"A".repeat(43)}"
    private val other = "mesh1_${"B".repeat(42)}A"
    private val hash = "f".repeat(64)

    @Test
    fun boundsGapsAndInvalidBatchContinuation(): Unit = runBlocking {
        val root = Files.createTempDirectory("wire-").toFile()
        try {
            val files = FakeMeshFiles(a).also { it.admit(mesh, a, 0) }
            val store = CatalogStore(root, mesh)
            val first = signed(files, mesh, a, 0, 1)
            val second = signed(files, mesh, a, 0, 2)
            val huge = CatalogOp(mesh, a, 0, 1_000_000_000_000, first.body, first.signature)
            assertTrue(store.acceptBatch(listOf(second, huge, first, second), files).rejected.isNotEmpty())
            assertEquals(2L, store.vector()[CatalogClock(a, 0)])
            repeat(78) { store.own(a, 0, CatalogBody.Add(EntryId.new(), "name", hash, 1), files) }
            val (batch, more) = CatalogWire.missing(store.snapshot(), emptyMap())
            assertTrue(more)
            assertEquals(64, batch.size)
            assertEquals(64, CatalogWire.decode(CatalogWire.encode(CatalogWire.Message(store.vector(), batch), true), true).ops.size)
            assertTrue(runCatching { CatalogWire.encode(CatalogWire.Message(emptyMap(), List(65) { first }), false) }.isFailure)
            assertEquals(80, store.entries.value.size)
        } finally { root.deleteRecursively() }
    }

    private suspend fun signed(files: FakeMeshFiles, meshId: String, author: String, generation: Long, seq: Long, name: String = "name"): CatalogOp {
        val op = CatalogOp(meshId, author, generation, seq, CatalogBody.Add(EntryId.new(), name, hash, 1), "")
        return op.copy(signature = files.signApp(CatalogCodec.domain, CatalogCodec.signed(op)))
    }

    private suspend fun waitFor(condition: suspend () -> Boolean) {
        repeat(150) { if (condition()) return; delay(20) }
        assertTrue(condition())
    }

    private inner class CatalogNode(val id: String, private val network: Map<String, CatalogNode>, val fake: FakeMeshFiles = FakeMeshFiles(id)) : MeshNode, MeshFiles by fake {
        var joined = true
        var generation = 0L
        override suspend fun status() = NodeStatus(id, id,
            if (joined) listOf(MeshStatus(mesh, "Group", listOf(a, b).map { MeshMember(it, it, if (it == id) generation else 0) }), MeshStatus(other, "Other", listOf(a, b).map { MeshMember(it, it, 0) })) else emptyList(),
            network.keys.filter { it != id }.map { NodePeer(it, it, true, null, null) }, true)
        override suspend fun appRequest(meshId: String, peer: String, protocol: String, bytes: ByteArray): ByteArray =
            network[peer]?.fake?.appRequest(meshId, id, protocol, bytes) ?: throw MeshNodeException(MeshFailure.Unavailable)
        override suspend fun createMesh(name: String) = mesh
        override suspend fun pair() = PairingInvitation("spirit1ticket", 1, byteArrayOf(1), 300)
        override suspend fun add(meshId: String, ticket: String) = b
        override suspend fun ping(device: String) = NodePong(device, 1)
        override suspend fun leaveMesh(meshId: String): LeftMesh { joined = false; return LeftMesh(meshId, "Group", 1, 0) }
        override suspend fun shutdown() = Unit
    }
}
