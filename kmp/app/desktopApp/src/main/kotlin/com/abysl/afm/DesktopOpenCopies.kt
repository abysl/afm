package com.abysl.afm

import blue.rae.spirit.sdk.MeshFiles
import java.awt.Desktop
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class DesktopOpenCopies {
    private val lock = Mutex()
    private var previous: File? = null

    suspend fun open(files: MeshFiles, entry: CatalogEntry) = withContext(Dispatchers.IO) {
        lock.withLock {
            if (!openFileTypeAllowed(entry.name)) throw FilePresentationException(blockedOpenMessage)
            previous?.deleteRecursively()
            previous = null
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN))
                throw FilePresentationException("Open isn't available here; use Save.")
            val directory = Files.createTempDirectory("afm-open-").toFile().apply { deleteOnExit() }
            previous = directory
            val output = File(directory, entry.name).apply { deleteOnExit() }
            try {
                files.exportFile(entry.hash, output.absolutePath)
                Desktop.getDesktop().open(output)
            } catch (failure: IOException) {
                directory.deleteRecursively()
                previous = null
                throw FilePresentationException("Open isn't available here; use Save.")
            } catch (failure: Exception) {
                directory.deleteRecursively()
                previous = null
                throw failure
            }
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        lock.withLock { previous?.deleteRecursively(); previous = null }
    }
}
