package com.abysl.afm

import java.io.File

internal fun openCacheDirectory(cacheDir: File): File = File(cacheDir, "afm-open")

internal fun openCacheFile(cacheDir: File, token: String, name: String): File {
    require(token.matches(Regex("[a-f0-9-]{36}")))
    require(validateFileName(name) == null)
    return File(File(openCacheDirectory(cacheDir), token), name)
}

internal fun fileProviderAuthority(packageName: String): String = "$packageName.files"
