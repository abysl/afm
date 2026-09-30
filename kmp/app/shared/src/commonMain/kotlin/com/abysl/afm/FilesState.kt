package com.abysl.afm

data class FileRow(val id: String, val name: String, val size: Long, val author: String, val local: Boolean)

data class FilesState(
    val entries: List<FileRow> = emptyList(),
    val busy: Boolean = false,
    val importing: Boolean = false,
    val catalogProblem: String? = null,
    val message: String? = null,
)

internal fun fileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KiB"
    else -> "${bytes / (1024 * 1024)} MiB"
}
