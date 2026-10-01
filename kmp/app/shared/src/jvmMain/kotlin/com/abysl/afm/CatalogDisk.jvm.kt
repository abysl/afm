package com.abysl.afm

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal actual fun syncCatalogDirectory(directory: File) {
    FileChannel.open(directory.toPath()).use { it.force(true) }
}

internal actual fun atomicReplaceCatalogFile(source: File, destination: File) {
    Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
}
