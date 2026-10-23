package me.pinfort.tsvideos.processing.api

import org.springframework.stereotype.Component

@Component
class RunningPlatformComponent {
    private val os = System.getProperty("os.name").lowercase() // java.lang.Systemはmockkstaticできないのでテストしない

    fun runningOnWindows(): Boolean = os.startsWith("windows")
}
