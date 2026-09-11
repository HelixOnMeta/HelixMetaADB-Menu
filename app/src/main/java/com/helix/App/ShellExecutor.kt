package com.helix

import java.io.DataOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs real Linux shell commands on-device (like EventHorizon RootUtils),
 * with ADB shell as fallback when local execution is unavailable.
 *
 * Priority:
 *  1. Local `su -c` if root works
 *  2. Local `/system/bin/sh -c`
 *  3. ADB wireless shell via [AppAdbConnectionManager]
 */
object ShellExecutor {

    private const val DEFAULT_TIMEOUT_MS = 30_000L

    @Volatile private var rootChecked = false
    @Volatile private var rootAvailable = false

    fun hasLocalRoot(): Boolean {
        if (rootChecked) return rootAvailable
        rootAvailable = runCatching {
            val out = execLocal(arrayOf("su", "-c", "id"), timeoutMs = 4_000L)
            out.contains("uid=0")
        }.getOrDefault(false)
        rootChecked = true
        return rootAvailable
    }

    /** Force re-probe next time (e.g. after Magisk grant). */
    fun invalidateRootCache() {
        rootChecked = false
    }

    /**
     * Run [command] as a shell line.
     * @param preferRoot try su first when true (default)
     * @param adbManager optional ADB fallback
     */
    fun run(
        command: String,
        preferRoot: Boolean = true,
        adbManager: AppAdbConnectionManager? = null,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): String {
        val cmd = command.trim()
        if (cmd.isEmpty()) return ""

        // 1) Local root
        if (preferRoot && hasLocalRoot()) {
            val out = execLocal(arrayOf("su", "-c", cmd), timeoutMs)
            if (!out.startsWith("ERROR:")) return out
        }

        // 2) Local non-root shell
        val local = execLocal(arrayOf("/system/bin/sh", "-c", cmd), timeoutMs)
        if (!local.startsWith("ERROR:")) return local
        // Some devices only have sh on PATH
        val local2 = execLocal(arrayOf("sh", "-c", cmd), timeoutMs)
        if (!local2.startsWith("ERROR:")) return local2

        // 3) ADB shell
        if (adbManager != null) {
            val adb = runCatching {
                val stream = adbManager.openStream("shell:$cmd")
                try {
                    stream.openInputStream().bufferedReader().use { it.readText() }
                } finally {
                    runCatching { stream.close() }
                }
            }.getOrElse { "ERROR: adb ${it.message}" }
            return adb
        }

        return local2.ifBlank { local }
    }

    /**
     * Interactive-style multi-line script via su stdin (closer to a real terminal).
     */
    fun runScriptAsRoot(script: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): String {
        if (!hasLocalRoot()) {
            return execLocal(arrayOf("/system/bin/sh", "-c", script), timeoutMs)
        }
        return execSuScript(script, timeoutMs)
    }

    private fun execSuScript(script: String, timeoutMs: Long): String {
        val output = StringBuilder()
        var process: Process? = null
        try {
            process = Runtime.getRuntime().exec(arrayOf("su"))
            DataOutputStream(process.outputStream).use { os ->
                script.lineSequence().forEach { line ->
                    os.writeBytes(line)
                    os.writeBytes("\n")
                }
                os.writeBytes("exit\n")
                os.flush()
            }
            drainProcess(process, output, timeoutMs)
        } catch (e: Exception) {
            output.append("ERROR: ${e.message}")
        } finally {
            runCatching { process?.destroy() }
        }
        return output.toString().trim()
    }

    private fun execLocal(argv: Array<String>, timeoutMs: Long): String {
        val output = StringBuilder()
        var process: Process? = null
        try {
            process = Runtime.getRuntime().exec(argv)
            drainProcess(process, output, timeoutMs)
        } catch (e: Exception) {
            return "ERROR: ${e.message}"
        } finally {
            runCatching { process?.destroy() }
        }
        return output.toString().trim()
    }

    private fun drainProcess(process: Process, output: StringBuilder, timeoutMs: Long) {
        val stdoutDone = AtomicBoolean(false)
        val stderrDone = AtomicBoolean(false)

        val outThread = Thread {
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { output.append(it).append('\n') }
                }
            } catch (_: Exception) {
            } finally {
                stdoutDone.set(true)
            }
        }
        val errThread = Thread {
            try {
                process.errorStream.bufferedReader().useLines { lines ->
                    lines.forEach { output.append(it).append('\n') }
                }
            } catch (_: Exception) {
            } finally {
                stderrDone.set(true)
            }
        }
        outThread.isDaemon = true
        errThread.isDaemon = true
        outThread.start()
        errThread.start()

        val finished = process.waitFor(timeoutMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            output.append("ERROR: Process killed (timeout ${timeoutMs}ms)\n")
        }
        outThread.join(1_500)
        errThread.join(1_500)
    }
}
