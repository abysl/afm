package com.abysl.afm

fun desktopOpenAvailable(osName: String): Boolean = osName.lowercase().startsWith("linux")
