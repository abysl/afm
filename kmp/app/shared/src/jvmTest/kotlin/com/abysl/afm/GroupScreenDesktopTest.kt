package com.abysl.afm

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import blue.rae.spirit.sdk.GroupState
import blue.rae.spirit.sdk.MemberStatus
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class GroupScreenDesktopTest {
    private val group = GroupState("chosen", "Family", listOf(
        MemberStatus("self", "Laptop", true, 0),
        MemberStatus("remote", "Phone", false, 0),
    ))

    @Test
    fun clearsPastedCodeOnlyAfterSuccessfulAdd() = runComposeUiTest {
        val added = androidx.compose.runtime.mutableStateOf<String?>(null)
        setContent { MaterialTheme { GroupScreen(group, "self", true, false, {}, { _, _ -> }, addedTicket = added.value) } }
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
                GroupScreen(group, "self", true, false, {}, { id, ticket ->
                    added = id to ticket
                })
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

}
