package com.abysl.afm

import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    val home = System.getProperty("user.home")
    val store = remember { openBlobStore("$home/.spirit2/afm") }
    Window(
        onCloseRequest = ::exitApplication,
        title = "AFM",
    ) {
        App(store)
    }
}