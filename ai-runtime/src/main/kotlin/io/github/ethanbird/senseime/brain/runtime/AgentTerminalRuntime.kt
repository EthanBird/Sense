package io.github.ethanbird.senseime.brain.runtime

import android.content.Context
import io.github.ethanbird.senseime.brain.api.AgentToolArguments
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class AgentTerminalResult(
    val command: String,
    val cwd: String,
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
    val stdoutTruncated: Boolean,
    val stderrTruncated: Boolean,
) {
    val succeeded: Boolean
        get() = !timedOut && exitCode == 0
}

/**
 * Session-scoped Android shell runtime.
 *
 * Commands run under the app UID with a workspace-owned HOME and a finite timeout. Each call owns
 * a fresh `/system/bin/sh`; state that should survive calls lives in the workspace and callers pass
 * an explicit workspace-relative [AgentToolArguments.TerminalExec.cwd].
 */
class AgentTerminalRuntime(context: Context) {
    private val applicationContext = context.applicationContext
    private val sessionsRoot = File(applicationContext.filesDir, "agent/sessions")

    fun execute(
        sessionId: String,
        arguments: AgentToolArguments.TerminalExec,
    ): AgentTerminalResult {
        val workspace = workspace(sessionId)
        val cwd = AgentTerminalWorkspacePolicy.resolve(workspace, arguments.cwd)
        if (!cwd.exists() && !cwd.mkdirs()) {
            error("Terminal cwd could not be created")
        }
        require(cwd.isDirectory) { "Terminal cwd is not a directory" }

        val process = ProcessBuilder(SHELL, "-c", arguments.command)
            .directory(cwd)
            .redirectErrorStream(false)
            .apply {
                environment()["HOME"] = workspace.absolutePath
                environment()["PWD"] = cwd.absolutePath
                environment()["TMPDIR"] = applicationContext.cacheDir.absolutePath
                environment()["SENSE_AGENT_SESSION"] = AgentTerminalWorkspacePolicy.token(sessionId)
            }
            .start()
        val stdout = BoundedProcessStream(process.inputStream, MAX_STREAM_BYTES)
        val stderr = BoundedProcessStream(process.errorStream, MAX_STREAM_BYTES)
        val stdoutThread = stdout.start("sense-terminal-stdout")
        val stderrThread = stderr.start("sense-terminal-stderr")
        val finished = try {
            process.waitFor(arguments.timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        } catch (interrupted: InterruptedException) {
            abandon(process)
            Thread.currentThread().interrupt()
            throw interrupted
        }
        if (!finished) {
            terminateTimedOutProcess(process)
        }
        stdoutThread.join(STREAM_JOIN_MS)
        stderrThread.join(STREAM_JOIN_MS)
        return AgentTerminalResult(
            command = arguments.command,
            cwd = workspace.toPath().relativize(cwd.toPath()).toString().ifBlank { "." },
            exitCode = if (finished) process.exitValue() else null,
            stdout = stdout.text(),
            stderr = stderr.text(),
            timedOut = !finished,
            stdoutTruncated = stdout.truncated,
            stderrTruncated = stderr.truncated,
        )
    }

    fun executeForTool(
        sessionId: String,
        arguments: AgentToolArguments.TerminalExec,
    ): String = execute(sessionId, arguments).toToolJson()

    fun workspace(sessionId: String): File {
        val directory = File(
            File(sessionsRoot, AgentTerminalWorkspacePolicy.token(sessionId)),
            "workspace",
        )
        if (!directory.exists() && !directory.mkdirs()) {
            error("Agent workspace could not be created")
        }
        return directory.canonicalFile
    }

    private fun AgentTerminalResult.toToolJson(): String = buildString {
        append("{\"command\":").appendJson(command)
        append(",\"cwd\":").appendJson(cwd)
        append(",\"exit_code\":")
        exitCode?.let(::append) ?: append("null")
        append(",\"timed_out\":").append(timedOut)
        append(",\"stdout\":").appendJson(stdout)
        append(",\"stderr\":").appendJson(stderr)
        append(",\"stdout_truncated\":").append(stdoutTruncated)
        append(",\"stderr_truncated\":").append(stderrTruncated)
        append('}')
    }

    private fun StringBuilder.appendJson(value: String) {
        append('"')
        value.forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) {
                    append("\\u").append(char.code.toString(16).padStart(4, '0'))
                } else {
                    append(char)
                }
            }
        }
        append('"')
    }

    private fun terminateTimedOutProcess(process: Process) {
        process.destroy()
        try {
            if (!process.waitFor(DESTROY_GRACE_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                process.waitFor(DESTROY_GRACE_MS, TimeUnit.MILLISECONDS)
            }
        } catch (interrupted: InterruptedException) {
            abandon(process)
            Thread.currentThread().interrupt()
            throw interrupted
        }
    }

    private fun abandon(process: Process) {
        process.destroyForcibly()
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
        runCatching { process.outputStream.close() }
    }

    private class BoundedProcessStream(
        private val input: java.io.InputStream,
        private val limit: Int,
    ) {
        private val output = ByteArrayOutputStream(limit.coerceAtMost(8_192))
        @Volatile
        var truncated: Boolean = false
            private set

        fun start(name: String): Thread = Thread({ read() }, name).apply {
            isDaemon = true
            start()
        }

        fun text(): String = output.toByteArray().toString(StandardCharsets.UTF_8)

        private fun read() {
            input.use { stream ->
                val buffer = ByteArray(4_096)
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) return
                    if (read == 0) continue
                    val remaining = limit - output.size()
                    if (remaining > 0) output.write(buffer, 0, minOf(read, remaining))
                    if (read > remaining) truncated = true
                }
            }
        }
    }

    private companion object {
        const val SHELL = "/system/bin/sh"
        const val MAX_STREAM_BYTES = 24 * 1024
        const val DESTROY_GRACE_MS = 500L
        const val STREAM_JOIN_MS = 1_000L
    }
}

internal object AgentTerminalWorkspacePolicy {
    fun token(sessionId: String): String = MessageDigest.getInstance("SHA-256")
        .digest(sessionId.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        .take(24)

    fun resolve(workspace: File, requested: String): File {
        require(requested.isNotBlank())
        val relative = File(requested)
        require(!relative.isAbsolute) { "Terminal cwd must be workspace-relative" }
        val root = workspace.canonicalFile
        val resolved = File(root, requested).canonicalFile
        require(resolved == root || resolved.toPath().startsWith(root.toPath())) {
            "Terminal cwd leaves the session workspace"
        }
        return resolved
    }
}
