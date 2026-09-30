package com.abysl.afm

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenFilePolicyTest {
    @Test
    fun blocksExecutableAndScriptExtensionsRegardlessOfCase() {
        for (extension in listOf("exe", "msi", "bat", "cmd", "com", "scr", "ps1", "vbs", "js", "jar",
            "sh", "bash", "run", "bin", "appimage", "desktop", "deb", "rpm", "apk", "lnk", "py", "pl", "rb")) {
            assertFalse(openFileTypeAllowed("peer.$extension"), extension)
            assertFalse(openFileTypeAllowed("peer.${extension.uppercase()}"), extension)
        }
    }

    @Test
    fun blocksExtensionlessNamesButAllowsDocuments() {
        for (name in listOf("README", ".bashrc", "trailing.")) assertFalse(openFileTypeAllowed(name), name)
        for (name in listOf("report.pdf", "family photo.JPG", "archive.tar.gz")) assertTrue(openFileTypeAllowed(name), name)
    }
}
