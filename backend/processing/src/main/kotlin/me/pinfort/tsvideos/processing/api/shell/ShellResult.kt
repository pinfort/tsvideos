package me.pinfort.tsvideos.processing.api.shell

data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)
