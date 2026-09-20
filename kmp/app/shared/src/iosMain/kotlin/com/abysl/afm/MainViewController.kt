package com.abysl.afm

import androidx.compose.ui.window.ComposeUIViewController
import platform.Foundation.NSHomeDirectory

fun MainViewController() = ComposeUIViewController {
    App(openBlobStore(NSHomeDirectory() + "/Documents/afm"))
}