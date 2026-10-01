package com.abysl.afm

import android.net.Uri
import androidx.lifecycle.SavedStateHandle

internal class GroupFilePicker(private val savedState: SavedStateHandle) {
    fun select(meshId: String) { savedState["pendingFileGroup"] = meshId }

    fun selectSave(meshId: String, entryId: String) {
        savedState["pendingSaveGroup"] = meshId
        savedState["pendingSaveEntry"] = entryId
    }

    fun consumeSave(uri: Uri?): Triple<String, String, Uri>? {
        val group = savedState.get<String>("pendingSaveGroup")
        val entry = savedState.get<String>("pendingSaveEntry")
        savedState["pendingSaveGroup"] = null
        savedState["pendingSaveEntry"] = null
        return if (group != null && entry != null && uri != null) Triple(group, entry, uri) else null
    }

    fun consume(uri: Uri?): Pair<String, Uri>? {
        val group = savedState.get<String>("pendingFileGroup")
        savedState["pendingFileGroup"] = null
        return if (group != null && uri != null) group to uri else null
    }
}
