package me.pinfort.tsvideos.processing.api.shell

import me.pinfort.tsvideos.processing.api.RunningPlatformComponent
import me.pinfort.tsvideos.processing.domain.InvalidEnvironmentException
import org.springframework.stereotype.Component
import java.io.File

@Component
class ShellComponent(
    private val runningPlatformComponent: RunningPlatformComponent,
    private val shellClient: ShellClient,
) {
    fun executeOnWindows(
        workingDir: File,
        commands: List<String>,
        timeout: Long = 600,
    ): Int {
        if (!runningPlatformComponent.runningOnWindows()) {
            throw InvalidEnvironmentException("This function must be called on Windows only.")
        }

        return shellClient.execute(workingDir, commands, timeout)
    }

    fun executeOnWindowsCapturingOutput(
        workingDir: File,
        commands: List<String>,
        timeout: Long = 600,
    ): ShellResult {
        if (!runningPlatformComponent.runningOnWindows()) {
            throw InvalidEnvironmentException("This function must be called on Windows only.")
        }

        return shellClient.executeCapturingOutput(workingDir, commands, timeout)
    }
}
