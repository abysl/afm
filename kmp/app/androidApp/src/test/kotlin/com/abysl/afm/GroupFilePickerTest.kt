package com.abysl.afm

import androidx.lifecycle.SavedStateHandle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GroupFilePickerTest {
    @Test
    fun saveCancellationClearsTheDestinationSelectionAcrossRecreation() {
        val saved = SavedStateHandle()
        val picker = GroupFilePicker(saved)
        picker.selectSave("family", "entry")
        assertNull(GroupFilePicker(saved).consumeSave(null))
        assertNull(saved.get<String>("pendingSaveGroup"))
        assertNull(saved.get<String>("pendingSaveEntry"))
    }

    @Test
    fun cancellationClearsPendingGroupWithoutStartingAnImport() {
        val saved = SavedStateHandle()
        val picker = GroupFilePicker(saved)
        picker.select("first")
        assertEquals("first", saved.get<String>("pendingFileGroup"))
        assertNull(GroupFilePicker(saved).consume(null))
        assertNull(saved.get<String>("pendingFileGroup"))
        picker.select("second")
        assertEquals("second", saved.get<String>("pendingFileGroup"))
        assertNull(picker.consume(null))
    }
}
