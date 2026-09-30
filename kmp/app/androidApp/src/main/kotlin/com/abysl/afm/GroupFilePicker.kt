package com.abysl.afm

import android.net.Uri
import androidx.lifecycle.SavedStateHandle

internal class GroupFilePicker(private val savedState: SavedStateHandle) {
    fun select(meshId: String) { savedState["pendingFileGroup"] = meshId }

    fun consume(uri: Uri?): Pair<String, Uri>? {
        val group = savedState.get<String>("pendingFileGroup")
        savedState["pendingFileGroup"] = null
        return if (group != null && uri != null) group to uri else null
    }
}
