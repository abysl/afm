package com.abysl.afm

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import blue.rae.spirit.sdk.GroupState
import blue.rae.spirit.sdk.MemberStatus
import blue.rae.spirit.sdk.MeshState
import blue.rae.spirit.sdk.LeftMesh
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
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
    fun duplicateNamesAndRemoveRequireConfirmation() = runComposeUiTest {
        val rows = listOf(FileRow("a", "same.txt", 3, "Laptop", true), FileRow("b", "same.txt", 8, "Phone", false))
        var removed: String? = null
        setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            GroupScreen(group, "self", true, false, false, {}, { _, _ -> }, {},
                files = FilesState(entries = rows), onRemoveFile = { _, id -> removed = id })
        } } }
        onNodeWithText("3 B · Added by Laptop · On this device").assertIsDisplayed()
        onNodeWithText("8 B · Added by Phone · Not on this device").performScrollTo().assertIsDisplayed()
        onAllNodesWithText("Remove same.txt")[0].performScrollTo().performClick()
        onNodeWithText("This removes the group entry, not the file itself. Members who downloaded it keep their copies.").assertIsDisplayed()
        onNodeWithText("Cancel").performClick()
        runOnIdle { assertEquals(null, removed) }
    }

    @Test
    fun importingAndCatalogFailureHaveDistinctMessages() = runComposeUiTest {
        setContent { MaterialTheme { GroupScreen(group, "self", true, false, false, {}, { _, _ -> }, {},
            files = FilesState(busy = true, importing = true, catalogProblem = "Could not save group files.")) } }
        onNodeWithText("Importing file…").assertExists()
        onNodeWithText("Could not save group files.").assertExists()
        onNodeWithText("Dismiss file message").assertExists()
    }

    @Test
    fun desktopOpenIsLinuxOnlyAndOtherPlatformsKeepSave() = runComposeUiTest {
        assertTrue(desktopOpenAvailable("Linux"))
        assertFalse(desktopOpenAvailable("Windows 11"))
        assertFalse(desktopOpenAvailable("Mac OS X"))
        val enabled = mutableStateOf(false)
        val rows = FilesState(entries = listOf(FileRow("local", "photo.jpg", 2, "Laptop", true)))
        setContent { MaterialTheme { GroupScreen(group, "self", true, false, false, {}, { _, _ -> }, {},
            files = rows, canOpenFiles = enabled.value) } }
        onNodeWithText("Save photo.jpg").assertExists()
        onNodeWithText("Open photo.jpg").assertDoesNotExist()
        runOnIdle { enabled.value = true }
        onNodeWithText("Open photo.jpg").assertExists()
    }

    @Test
    fun remoteShowsTransferAndLocalSave() = runComposeUiTest {
        val remote = FileRow("remote", "report.pdf", 1024, "Phone", false)
        val local = FileRow("local", "photo.jpg", 100, "Laptop", true)
        val state = mutableStateOf(FilesState(entries = listOf(remote, local)))
        var requested: String? = null
        setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            GroupScreen(group, "self", true, false, false, {}, { _, _ -> }, {},
                files = state.value, onDownload = { _, id -> requested = id })
        } } }
        onNodeWithText("Download report.pdf").performClick()
        runOnIdle { assertEquals("remote", requested); state.value = state.value.copy(transfers = mapOf("remote" to FileTransfer.Queued)) }
        onNodeWithText("Queued for download").assertIsDisplayed()
        onNodeWithText("Cancel download").assertIsDisplayed()
        runOnIdle { state.value = state.value.copy(transfers = mapOf("remote" to FileTransfer.Transferring(512, 1024))) }
        onNodeWithText("Downloading: 512 B / 1 KiB").assertIsDisplayed()
        runOnIdle { state.value = state.value.copy(transfers = mapOf("remote" to FileTransfer.SourceUnavailable)) }
        onNodeWithText("Retry download").assertIsDisplayed()
        runOnIdle { state.value = state.value.copy(transfers = mapOf("remote" to FileTransfer.Failed(blue.rae.spirit.sdk.MeshFailure.Corrupt, true))) }
        onNodeWithText("Download failed: The file failed verification on every available member. Ask a member to re-add it or retry later.").assertExists()
        onNodeWithText("Save photo.jpg").performScrollTo().assertIsDisplayed()
        onNodeWithText("Save report.pdf").assertDoesNotExist()
    }

    @Test
    fun transferDisplaySurvivesScreenRecreation() = runComposeUiTest {
        val generation = mutableIntStateOf(0)
        val files = mutableStateOf(FilesState(entries = listOf(FileRow("remote", "report.pdf", 1024, "Phone", false)),
            transfers = mapOf("remote" to FileTransfer.Queued)))
        setContent { key(generation.intValue) { MaterialTheme {
            GroupScreen(group, "self", true, false, false, {}, { _, _ -> }, {}, files = files.value)
        } } }
        onNodeWithText("Queued for download").assertIsDisplayed()
        runOnIdle { generation.intValue++ }
        onNodeWithText("Queued for download").assertIsDisplayed()
        runOnIdle { files.value = files.value.copy(transfers = mapOf("remote" to FileTransfer.Verifying)) }
        onNodeWithText("Verifying file…").assertIsDisplayed()
    }

    @Test
    fun typedFileFailuresStayActionable() {
        assertEquals("The file failed verification. Try another member.", fileFailureMessage(blue.rae.spirit.sdk.MeshFailure.Corrupt))
        assertEquals("Could not write to the chosen location. Check available space and permissions, then try again.", fileFailureMessage(blue.rae.spirit.sdk.MeshFailure.Destination))
        assertEquals("AFM file storage is not set up. Restart AFM; if this continues, report the problem.", fileFailureMessage(blue.rae.spirit.sdk.MeshFailure.StoreNotConfigured))
    }

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
        onNodeWithText("No files in this group yet.").assertIsDisplayed()
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
