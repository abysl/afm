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
    fun mapsTypedErrorsAndClearsThem() = runBlocking {
        val node = FakeGroupsNode()
        val session = MeshSession({ node })
        val job = launch { session.run() }
        waitUntil { !session.state.value.loading }
        node.failure = MeshFailure.MeshLimit
        assertNull(session.createGroup("Other"))
        assertEquals("This device has reached the 64-group limit. Leave a group before creating or joining another group.", failureMessage(session.state.value.failure, session.state.value.error))
        session.clearMessages()
        assertNull(session.state.value.failure)
        assertNull(session.state.value.error)
        job.cancelAndJoin()
        assertEquals("Ticket rejected. Ask for a fresh QR code.", failureMessage(MeshFailure.TicketRejected, null))
        assertEquals("Device unreachable. Check its connection and try again.", failureMessage(MeshFailure.Unavailable, null))
        assertEquals("AFM's device data is in use by another AFM window or process. Close it and restart AFM.", failureMessage(MeshFailure.NodeBusy, null))
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

    @Test
    fun scannedTicketQueuesWhileRefreshIsBusy() = runBlocking {
        val node = FakeGroupsNode().apply { meshes += MeshStatus("mesh1", "Family", listOf(MeshMember("self", "My device", 0))) }
        val mesh = MeshSession({ node })
        val sessionJob = launch { mesh.run() }
        waitUntil { !mesh.state.value.loading && mesh.state.value.invitation != null }
        node.pairGate = CompletableDeferred()
        val refresh = launch { mesh.refreshTicket() }
        waitUntil { mesh.state.value.busy }
        val actions = GroupActions(mesh, this)
        actions.addScannedTicket("mesh1", "spirit1anotherdevice")
        assertNull(node.addedTo)
        node.pairGate?.complete(Unit)
        refresh.join()
        waitUntil { node.addedTo == "mesh1" }
        sessionJob.cancelAndJoin()
    }

    @Test
    fun rejectsAddWhenNodeIsUnavailableOrAnotherActionIsBusy() = runBlocking {
        val node = FakeGroupsNode().apply { meshes += MeshStatus("mesh1", "Family", listOf(MeshMember("self", "My device", 0))) }
        val mesh = MeshSession({ node })
        val actions = GroupActions(mesh, this)
        actions.addDevice("mesh1", "spirit1anotherdevice")
        assertEquals("Device is not ready. Reopen AFM and try again.", mesh.state.value.error)
        val sessionJob = launch { mesh.run() }
        waitUntil { !mesh.state.value.loading }
        node.pairGate = CompletableDeferred()
        val refresh = launch { mesh.refreshTicket() }
        waitUntil { mesh.state.value.busy }
        actions.addDevice("mesh1", "spirit1anotherdevice")
        assertEquals("Another action is in progress. Try again when it finishes.", mesh.state.value.error)
        assertNull(node.addedTo)
        node.pairGate?.complete(Unit)
        refresh.join()
        sessionJob.cancelAndJoin()
    }

    @Test
    fun ownerKeepsAddingAfterScreenIsRecreated() = runBlocking {
        val node = FakeGroupsNode().apply { meshes += MeshStatus("mesh1", "Family", listOf(MeshMember("self", "My device", 0))) }
        val mesh = MeshSession({ node })
        val sessionJob = launch { mesh.run() }
        waitUntil { !mesh.state.value.loading }
        val actions = GroupActions(mesh, this)
        val previousScreenAction = actions::addDevice
        previousScreenAction("mesh1", "spirit1anotherdevice")
        val recreatedScreenState = actions.state
        waitUntil { node.addedTo == "mesh1" && !recreatedScreenState.value.adding }
        assertEquals("mesh1", node.addedTo)
        assertEquals("spirit1anotherdevice", actions.state.value.addedTicket)
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
    var leaveGate: CompletableDeferred<Unit>? = null
    var pairGate: CompletableDeferred<Unit>? = null
    var closed = false
    var addedTo: String? = null
    var left: String? = null
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
    override suspend fun leaveMesh(meshId: String): LeftMesh {
        leaveGate?.await()
        failure?.let { throw MeshNodeException(it) }
        left = meshId
        val mesh = meshes.single { it.id == meshId }
        meshes.remove(mesh)
        return LeftMesh(meshId, mesh.name, 2, 1)
    }
    override suspend fun shutdown() { closed = true }
}
