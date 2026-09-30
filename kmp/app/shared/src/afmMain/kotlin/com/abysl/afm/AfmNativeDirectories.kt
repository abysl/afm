package com.abysl.afm

import java.io.File

fun afmNodeDirectory(root: File): File = File(root, "afm-node")
fun afmStoreDirectory(root: File): File = File(root, "afm-store")
