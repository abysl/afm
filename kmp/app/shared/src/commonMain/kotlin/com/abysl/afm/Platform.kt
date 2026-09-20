package com.abysl.afm

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform