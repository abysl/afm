package com.abysl.afm

private val blockedOpenExtensions = setOf(
    "exe", "msi", "bat", "cmd", "com", "scr", "ps1", "vbs", "js", "jar", "sh", "bash", "run",
    "bin", "appimage", "desktop", "deb", "rpm", "apk", "lnk", "py", "pl", "rb",
)

const val blockedOpenMessage = "Opening this file type isn't allowed; use Save instead"

fun openFileTypeAllowed(name: String): Boolean {
    val dot = name.lastIndexOf('.')
    return dot > 0 && dot < name.lastIndex && name.substring(dot + 1).lowercase() !in blockedOpenExtensions
}
