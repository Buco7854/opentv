package com.buco7854.opentv.server

import java.nio.file.Path

/** Declarative process request; keeps command construction separate from JVM execution. */
data class MediaProcessRequest(
    val command: List<String>,
    val workingDirectory: Path? = null,
    val stdoutFile: Path? = null,
    val appendStderrFile: Path? = null,
    val discardStdout: Boolean = false,
    val discardStderr: Boolean = false,
    val mergeErrorIntoStdout: Boolean = false,
)

fun interface MediaProcessRunner {
    fun start(request: MediaProcessRequest): Process
}

object JvmMediaProcessRunner : MediaProcessRunner {
    override fun start(request: MediaProcessRequest): Process {
        val builder = ProcessBuilder(request.command)
        request.workingDirectory?.let { builder.directory(it.toFile()) }
        when {
            request.stdoutFile != null -> builder.redirectOutput(request.stdoutFile.toFile())
            request.discardStdout -> builder.redirectOutput(ProcessBuilder.Redirect.DISCARD)
        }
        when {
            request.appendStderrFile != null ->
                builder.redirectError(ProcessBuilder.Redirect.appendTo(request.appendStderrFile.toFile()))
            request.discardStderr -> builder.redirectError(ProcessBuilder.Redirect.DISCARD)
        }
        if (request.mergeErrorIntoStdout) builder.redirectErrorStream(true)
        return builder.start()
    }
}
