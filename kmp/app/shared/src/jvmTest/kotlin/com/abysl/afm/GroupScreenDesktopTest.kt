package com.abysl.afm

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import blue.rae.spirit.sdk.GroupState
import blue.rae.spirit.sdk.MemberStatus
import blue.rae.spirit.sdk.MeshState
import blue.rae.spirit.sdk.LeftMesh
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import blue.rae.spirit.sdk.MeshMember
import blue.rae.spirit.sdk.MeshSession
import blue.rae.spirit.sdk.MeshStatus

@OptIn(ExperimentalTestApi::class)
class GroupScreenDesktopTest {
    private val group = GroupState("chosen", "Family", listOf(
        MemberStatus("self", "Laptop", true, 0),
        MemberStatus("remote", "Phone", false, 0),
    ))

    @Test
    fun clearsPastedCodeOnlyAfterSuccessfulAdd() = runComposeUiTest {
        val added = androidx.compose.runtime.mutableStateOf<String?>(null)
        setContent { MaterialTheme { GroupScreen(group, "self", true, false, false, {}, { _, _ -> }, {}, addedTicket = added.value) } }
        onNodeWithText("Paste code").performTextInput("spirit1otherdevice")
        onNodeWithText("spirit1otherdevice").assertExists()
        runOnIdle { added.value = "spirit1otherdevice" }
        onNodeWithText("spirit1otherdevice").assertDoesNotExist()
    }

    @Test
    fun showsMemberPresenceAndAddsPastedTicketToChosenGroup() = runComposeUiTest {
        var added: Pair<String, String>? = null
        setContent {
            MaterialTheme {
                GroupScreen(group, "self", true, false, false, {}, { id, ticket ->
                    added = id to ticket
                }, {})
            }
        }
        onNodeWithText("Laptop (this device)").assertIsDisplayed()
        onNodeWithText("Online").assertIsDisplayed()
        onNodeWithText("Offline").assertIsDisplayed()
        onNodeWithText("Files coming soon. Group files will be visible to all current and future members.").assertIsDisplayed()
        onNodeWithText("Paste code").performTextInput("spirit1otherdevice")
        onNodeWithText("Scan QR code").assertDoesNotExist()
        onNodeWithText("Add pasted code").performClick()
        runOnIdle { assertEquals("chosen" to "spirit1otherdevice", added) }
    }

    @Test
    fun leaveRequiresConfirmationAndShowsOnlyOneDepartureMessage() = runComposeUiTest {
        val state = mutableStateOf(MeshState(loading = false, nodeId = "self", groups = listOf(group)))
        var leaves = 0
        val left = LeftMesh("chosen", "Family", 2, 1)
        setContent { App(mesh = state.value, actions = GroupActionState(departure = if (leaves > 0) left else null), onLeaveGroup = {
            leaves++
            state.value = state.value.copy(groups = emptyList(), notice = "Left Family. Notified 1 of 2 devices; notified devices relay the departure")
        }) }
        onNodeWithText("Family · 2 members · 1 online").performClick()
        onNodeWithText("Group menu").performClick()
        onNodeWithText("Leave group").performClick()
        onNodeWithText("Files only this device holds become unavailable to the group. Changes not yet synced to another member are lost.").assertIsDisplayed()
        onNodeWithText("Cancel").performClick()
        runOnIdle { assertEquals(0, leaves) }
        onNodeWithText("Group menu").performClick()
        onNodeWithText("Leave group").performClick()
        onNodeWithText("Leave group").performClick()
        onNodeWithText(departureMessage(left)).assertIsDisplayed()
        onNodeWithText("Left Family. Notified 1 of 2 devices; notified devices relay the departure").assertDoesNotExist()
        runOnIdle { assertEquals(1, leaves) }
    }

    @Test
    fun departureCountsAndSoloWarning() = runComposeUiTest {
        assertEquals("Left Family. Notified 1 of 1 member.", departureMessage(LeftMesh("chosen", "Family", 1, 1)))
        assertEquals("Left Family. Notified 1 of 2 members. Others learn of your departure from a notified member or when they next reach this device while it keeps the departed copy.", departureMessage(LeftMesh("chosen", "Family", 2, 1)))
        assertEquals("Left Family. No other members were notified. They can learn of your departure when they next reach this device while it keeps the departed copy.", departureMessage(LeftMesh("chosen", "Family", 2, 0)))
        setContent { MaterialTheme { GroupScreen(group.copy(members = group.members.take(1)), "self", true, false, false, {}, { _, _ -> }, {}) } }
        onNodeWithText("Group menu").performClick()
        onNodeWithText("Leave group").performClick()
        onNodeWithText("This device is the last member. Leaving deletes the group.").assertIsDisplayed()
        assertEquals("Left Family. The last member left, so the group was deleted.", departureMessage(LeftMesh("chosen", "Family", 0, 0)))
    }
    @Test
    fun confirmedLeaveContinuesAcrossScreenRecreation() = runComposeUiTest {
        val node = FakeGroupsNode().apply {
            meshes += MeshStatus("mesh1", "Family", listOf(MeshMember("self", "Laptop", 0), MeshMember("remote", "Phone", 0)))
            leaveGate = CompletableDeferred()
        }
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val mesh = MeshSession({ node })
        val job = owner.launch { mesh.run() }
        mesh.state.first { !it.loading }
        val actions = GroupActions(mesh, owner)
        val generation = mutableIntStateOf(0)
        setContent { key(generation.intValue) {
            val state by mesh.state.collectAsState()
            val result by actions.state.collectAsState()
            App(mesh = state, actions = result, onLeaveGroup = actions::leaveGroup)
        } }
        onNodeWithText("Family · 2 members · 1 online").performClick()
        onNodeWithText("Group menu").performClick()
        onNodeWithText("Leave group").performClick()
        onNodeWithText("Leave group").performClick()
        actions.state.first { it.leaving }
        runOnIdle { generation.intValue++ }
        onNodeWithText("Family · 2 members · 1 online").performClick()
        onNodeWithText("Leaving…").assertIsDisplayed()
        node.leaveGate?.complete(Unit)
        actions.state.first { it.departure != null }
        onNodeWithText("Left Family. Notified 1 of 2 members. Others learn of your departure from a notified member or when they next reach this device while it keeps the departed copy.").assertIsDisplayed()
        job.cancelAndJoin()
        owner.cancel()
    }
}
