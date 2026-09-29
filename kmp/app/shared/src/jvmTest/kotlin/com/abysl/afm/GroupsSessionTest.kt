package com.abysl.afm

import blue.rae.spirit.sdk.AddDeviceResult
import blue.rae.spirit.sdk.GroupState
import blue.rae.spirit.sdk.MemberStatus
import blue.rae.spirit.sdk.MeshFailure
import blue.rae.spirit.sdk.MeshMember
import blue.rae.spirit.sdk.MeshNode
import blue.rae.spirit.sdk.MeshNodeException
import blue.rae.spirit.sdk.MeshSession
import blue.rae.spirit.sdk.MeshStatus
import blue.rae.spirit.sdk.NodePong
import blue.rae.spirit.sdk.NodeStatus
import blue.rae.spirit.sdk.PairingInvitation
import blue.rae.spirit.sdk.LeftMesh
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GroupsSessionTest {
    @Test
    fun startsEmptyCreatesGroupAndKeepsJoinTicketAvailable() = runBlocking {
        val node = FakeGroupsNode()
        val session = MeshSession({ node })
        val job = launch { session.run() }
        waitUntil { !session.state.value.loading && session.state.value.invitation != null }
        assertTrue(session.state.value.groups.isEmpty())
        assertEquals("spirit1ticket", session.state.value.invitation?.ticket)
        assertEquals("mesh1", session.createGroup("  Personal  "))
        assertEquals("Personal", session.state.value.groups.single().name)
        waitUntil { session.state.value.invitation != null }
        assertEquals("spirit1ticket", session.state.value.invitation?.ticket)
        session.refreshTicket()
        assertEquals("spirit1ticket", session.state.value.invitation?.ticket)
        job.cancelAndJoin()
        assertTrue(node.closed)
    }

    @Test
    fun addsIntoSelectedGroup() = runBlocking {
        val node = FakeGroupsNode()
        val session = MeshSession({ node })
        val job = launch { session.run() }
        waitUntil { !session.state.value.loading }
        session.createGroup("Personal")
        session.createGroup("Work")
        val result = session.addDevice("mesh2", "spirit1anotherdevice")
        assertEquals(AddDeviceResult.Added("New device"), result)
        assertEquals("mesh2", node.addedTo)
        assertEquals(2, session.state.value.groups.size)
        job.cancelAndJoin()
    }

    @Test
    fun seesMigratedLegacyNameWithoutCreatingAnotherGroup() = runBlocking {
        val node = FakeGroupsNode()
        node.meshes += MeshStatus("legacy-id", "AFM mesh", listOf(MeshMember("self", "My device", 0)))
        val session = MeshSession({ node })
        val job = launch { session.run() }
        waitUntil { !session.state.value.loading }
        assertEquals(listOf(GroupState("legacy-id", "AFM mesh", listOf(MemberStatus("self", "My device", true, 0)))), session.state.value.groups)
        job.cancelAndJoin()
    }

    @Test
    fun restoredScanWaitsForInitialStatusBeforeAdding() = runBlocking {
        val opened = CompletableDeferred<MeshNode>()
        val node = FakeGroupsNode().apply { meshes += MeshStatus("mesh1", "Family", listOf(MeshMember("self", "My device", 0))) }
        val mesh = MeshSession({ opened.await() })
        val sessionJob = launch { mesh.run() }
        val actions = GroupActions(mesh, this)
        actions.addScannedTicket("mesh1", "spirit1anotherdevice")
        assertNull(node.addedTo)
        opened.complete(node)
        waitUntil { node.addedTo == "mesh1" }
        assertEquals(null, mesh.state.value.failure)
        sessionJob.cancelAndJoin()
    }

    private suspend fun waitUntil(condition: () -> Boolean) {
        repeat(100) {
            if (condition()) return
            delay(20)
        }
        assertTrue(condition())
    }
}

internal class FakeGroupsNode : MeshNode {
    val meshes = mutableListOf<MeshStatus>()
    var failure: MeshFailure? = null
    var pairGate: CompletableDeferred<Unit>? = null
    var closed = false
    var addedTo: String? = null
    override suspend fun status() = NodeStatus("self", "My device", meshes.toList(), emptyList(), true)
    override suspend fun createMesh(name: String): String {
        failure?.let { throw MeshNodeException(it) }
        meshes += MeshStatus("mesh${meshes.size + 1}", name, listOf(MeshMember("self", "My device", 0)))
        return meshes.last().id
    }
    override suspend fun pair(): PairingInvitation {
        pairGate?.await()
        return PairingInvitation("spirit1ticket", 1, byteArrayOf(1), 300)
    }
    override suspend fun add(meshId: String, ticket: String): String {
        failure?.let { throw MeshNodeException(it) }
        addedTo = meshId
        return "New device"
    }
    override suspend fun ping(device: String) = NodePong("Device", 1)
    override suspend fun leaveMesh(meshId: String): LeftMesh = error("Not used by group add")
    override suspend fun shutdown() { closed = true }
}
