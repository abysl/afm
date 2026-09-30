package com.abysl.afm

import android.system.Os
import android.system.OsConstants
import java.io.File

internal actual fun syncCatalogDirectory(directory: File) {
    val fd = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
    try { Os.fsync(fd) } finally { Os.close(fd) }
}

internal actual fun atomicReplaceCatalogFile(source: File, destination: File) {
    Os.rename(source.absolutePath, destination.absolutePath)
}
