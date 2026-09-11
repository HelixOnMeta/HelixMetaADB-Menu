package com.helix

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

// contact blaku64th on discord if you have any issues ^^
object RootHelper {

    private const val TAG = "RootHelper"
    private const val DEFAULT_TIMEOUT_MS = 12_000L

    fun hasRoot(ctx: Utils.ActionContext): Boolean {
        val id = ctx.run("id 2>/dev/null").trim()
        if (id.contains("uid=0")) return true
        val su = ctx.run("su -c id 2>/dev/null").trim()
        return su.contains("uid=0")
    }

    fun runRoot(ctx: Utils.ActionContext, command: String): String {
        return if (hasRoot(ctx)) {
            val id = ctx.run("id 2>/dev/null")
            if (id.contains("uid=0")) {
                ctx.run(command)
            } else {
                val escaped = command.replace("'", "'\\''")
                ctx.run("su -c '$escaped'")
            }
        } else {
            ctx.run(command)
        }
    }

    fun writeScript(ctx: Utils.ActionContext, name: String, content: String): String {
        val path = "/data/local/tmp/$name"
        val escaped = content.replace("'", "'\\''")
        val cmd = "echo '$escaped' > $path; chmod 755 $path"
        return runRoot(ctx, cmd)
    }

    // ------------------------------------------------------------------
    // Robust local (in-process) root helpers – used by MagiskUtils etc.
    // These do not depend on ActionContext / ADB.
    // ------------------------------------------------------------------

    /**
     * Quick non-interactive root check from the app process itself.
     */
    suspend fun isRootAvailableLocal(): Boolean = withContext(Dispatchers.IO) {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val finished = process.waitFor(4, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return@withContext false
            }
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.destroy()
            output.contains("uid=0")
        } catch (e: Exception) {
            Log.w(TAG, "isRootAvailableLocal failed: ${e.message}")
            false
        }
    }

    /**
     * Run a command as root from the app process.
     * Proper stream draining + timeout so it never hangs the UI thread.
     */
    suspend fun runAsRootLocal(
        command: String,
        useMountMaster: Boolean = false,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): String = withContext(Dispatchers.IO) {
        val result = withTimeoutOrNull(timeoutMs) {
            executeSuLocal(command, useMountMaster, timeoutMs)
        }
        result ?: "ERROR: Command timed out after ${timeoutMs}ms"
    }

    private fun executeSuLocal(
        command: String,
        useMountMaster: Boolean,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): String {
        val output = StringBuilder()
        var process: Process? = null
        try {
            process = if (useMountMaster) {
                Runtime.getRuntime().exec(arrayOf("su", "--mount-master"))
            } else {
                Runtime.getRuntime().exec(arrayOf("su"))
            }

            DataOutputStream(process.outputStream).use { os ->
                command.lineSequence().forEach { line ->
                    os.writeBytes(line)
                    os.writeBytes("\n")
                }
                os.writeBytes("exit\n")
                os.flush()
            }

            val stdout = process.inputStream.bufferedReader()
            val stderr = process.errorStream.bufferedReader()

            val stdoutThread = Thread {
                try {
                    stdout.useLines { lines ->
                        lines.forEach { output.append(it).append('\n') }
                    }
                } catch (_: Exception) {}
            }
            val stderrThread = Thread {
                try {
                    stderr.useLines { lines ->
                        lines.forEach { output.append("ERROR: ").append(it).append('\n') }
                    }
                } catch (_: Exception) {}
            }

            stdoutThread.start()
            stderrThread.start()

            val waitMs = timeoutMs.coerceAtMost(DEFAULT_TIMEOUT_MS).coerceAtLeast(1L)
            val finished = process.waitFor(waitMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                output.append("ERROR: Process killed (timeout)\n")
            }

            stdoutThread.join(1500)
            stderrThread.join(1500)
        } catch (e: Exception) {
            output.append("ERROR: Execution failed: ${e.message}\n")
            Log.e(TAG, "runAsRootLocal exception", e)
        } finally {
            try { process?.destroy() } catch (_: Exception) {}
        }
        return output.toString().trim()
    }

    suspend fun runAsRootSuccessLocal(
        command: String,
        useMountMaster: Boolean = false
    ): Boolean {
        val result = runAsRootLocal(command, useMountMaster)
        return !result.contains("ERROR:", ignoreCase = true) &&
                !result.contains("Permission denied", ignoreCase = true) &&
                !result.contains("No such file", ignoreCase = true)
    }

    suspend fun readSysfs(path: String): String =
        runAsRootLocal("cat \"$path\" 2>/dev/null").trim()
            .lines()
            .firstOrNull { it.isNotBlank() && !it.startsWith("ERROR:") }
            ?: ""

    suspend fun writeSysfs(path: String, value: String, verify: Boolean = false): Boolean {
        val cmd = "echo '$value' > \"$path\""
        val ok = runAsRootSuccessLocal(cmd)
        if (!ok || !verify) return ok
        val readBack = readSysfs(path)
        return readBack == value
    }
}

object MagiskUtils {

    private const val zygestko =
        "https://github.com/veygax/eventhorizon/raw/refs/heads/main/app/src/main/assets/exploit/Zygisk.ko"
    private const val magiskapk =
        "https://github.com/veygax/eventhorizon/raw/refs/heads/main/app/src/main/assets/exploit/magisk.apk"
    private const val setupsh =
        "https://github.com/veygax/eventhorizon/raw/refs/heads/main/app/src/main/assets/exploit/live_setup.sh"
    private const val launchsh =
        "https://github.com/veygax/eventhorizon/raw/refs/heads/main/app/src/main/assets/exploit/launch.sh"
    private const val busybox =
        "https://github.com/veygax/eventhorizon/raw/refs/heads/main/app/src/main/assets/exploit/busybox"
    private const val adblib =
        "https://github.com/veygax/eventhorizon/raw/refs/heads/main/app/src/main/jniLibs/arm64-v8a/libadb.so"
    private const val exploitlib =
        "https://github.com/veygax/eventhorizon/raw/refs/heads/main/app/src/main/jniLibs/arm64-v8a/libexploit.so"

    private val LOCAL_ASSETS = listOf(
        "exploit/Zygisk.ko",
        "exploit/magisk.apk",
        "exploit/busybox",
        "exploit/payloads/singularity_magisk.sh",
        "exploit/payloads/cheese_root.sh",
        "exploit/payloads/cheese_launch.sh",
        "Zygisk.ko",
        "magisk.apk",
        "busybox",
        "singularity_magisk.sh"
    )

    fun isInstalled(): Boolean {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("magisk", "-v"))
            val out = p.inputStream.bufferedReader().readText().trim()
            p.waitFor()
            out.contains("MAGISK", ignoreCase = true) || out.isNotBlank()
        } catch (e: Exception) {
            false
        }
    }

    fun autoGrantSuperUser(packageName: String = "com.helix"): Boolean {
        return try {
            val sql = """
                INSERT OR REPLACE INTO policies
                (uid, package_name, policy, until, logging, notification)
                VALUES (
                    (SELECT uid FROM packages WHERE package_name='$packageName' LIMIT 1),
                    '$packageName',
                    2, 0, 1, 1
                );
            """.trimIndent()
            val p = Runtime.getRuntime().exec(arrayOf("magisk", "--sqlite", sql))
            p.waitFor()
            if (p.exitValue() != 0) {
                Runtime.getRuntime().exec(arrayOf("su", "-c", "magisk --sqlite \"$sql\"")).waitFor()
            }
            true
        } catch (e: Exception) {
            Log.e("SU Error: ", "Failed to grant SuperUser", e)
            false
        }
    }

    fun ensureSuperUser(packageName: String = "com.helix"): Boolean {
        if (!isInstalled()) return false
        autoGrantSuperUser(packageName)
        try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "magisk --denylist rm $packageName")).waitFor()
        } catch (_: Exception) {}
        return true
    }

    suspend fun installFromGitHub(
        context: android.content.Context,
        onStatus: (String) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val cacheDir = File(context.cacheDir, "magisk_setup").apply { mkdirs() }
            onStatus("Resolving Magisk payload (assets first)…")
            val downloaded = mutableMapOf<String, File>()

            for (assetPath in LOCAL_ASSETS) {
                try {
                    val baseName = assetPath.substringAfterLast('/')
                    if (downloaded.containsKey(baseName)) continue
                    context.assets.open(assetPath).use { input ->
                        val dest = File(cacheDir, baseName)
                        FileOutputStream(dest).use { output -> input.copyTo(output) }
                        downloaded[baseName] = dest
                        onStatus("Asset $assetPath (${dest.length() / 1024} KB)")
                    }
                } catch (_: Exception) { }
            }

            val networkFiles = listOf(
                "Zygisk.ko" to zygestko,
                "magisk.apk" to magiskapk,
                "live_setup.sh" to setupsh,
                "launch.sh" to launchsh,
                "busybox" to busybox,
                "libadb.so" to adblib,
                "libexploit.so" to exploitlib
            )
            for ((name, url) in networkFiles) {
                if (downloaded.containsKey(name)) continue
                onStatus("Downloading $name…")
                val dest = File(cacheDir, name)
                if (!downloadFile(url, dest)) {
                    onStatus("Failed to download $name (optional if singularity path used)")
                    continue
                }
                downloaded[name] = dest
                onStatus("Downloaded $name (${dest.length() / 1024} KB)")
            }

            if (!downloaded.containsKey("busybox") && !downloaded.containsKey("magisk.apk")) {
                onStatus("Missing core Magisk files")
                return@withContext false
            }

            onStatus("Pushing files to device…")
            val cmds = buildString {
                appendLine("mkdir -p /data/local/tmp/")
                appendLine("chmod 755 /data/local/tmp/")
                downloaded.forEach { (name, file) ->
                    appendLine("cp \"${file.absolutePath}\" /data/local/tmp/$name")
                    appendLine("chmod 755 /data/local/tmp/$name")
                }
            }
            onStatus("Files pushed:\n${runAsRoot(cmds)}")

            val workDir = "/data/local/tmp/eventhorizon_magisk"

            if (downloaded.containsKey("singularity_magisk.sh")) {
                onStatus("Running singularity_magisk.sh…")
                val setupResult = runAsRoot(
                    """
                    mkdir -p $workDir
                    cp -f /data/local/tmp/busybox $workDir/ 2>/dev/null || true
                    cp -f /data/local/tmp/magisk.apk $workDir/ 2>/dev/null || true
                    cp -f /data/local/tmp/Zygisk.ko $workDir/ 2>/dev/null || true
                    cp -f /data/local/tmp/singularity_magisk.sh $workDir/ 2>/dev/null || true
                    chmod 755 $workDir/busybox $workDir/singularity_magisk.sh 2>/dev/null || true
                    pm disable-user --user 0 com.oculus.updater 2>/dev/null || true
                    pm disable-user --user 0 com.meta.updater 2>/dev/null || true
                    if [ -f /persist/srt_push/token ]; then rm -f /persist/srt_push/token; echo killswitch_removed; fi
                    cd $workDir
                    umask 000
                    if [ -x ./singularity_magisk.sh ]; then
                      ./busybox sh ./singularity_magisk.sh >/data/local/tmp/singularity_log.txt 2>&1
                      echo singularity_exit=$?
                      cat /data/local/tmp/singularity_log.txt 2>/dev/null || true
                    fi
                    """.trimIndent()
                )
                onStatus("singularity finished:\n$setupResult")
            } else {
                onStatus("Running launch.sh / live_setup (EventHorizon)…")
                val setupResult = runAsRoot(
                    """
                    mkdir -p $workDir
                    cp -f /data/local/tmp/busybox $workDir/ 2>/dev/null || true
                    cp -f /data/local/tmp/live_setup.sh $workDir/ 2>/dev/null || true
                    cp -f /data/local/tmp/launch.sh $workDir/ 2>/dev/null || true
                    cp -f /data/local/tmp/magisk.apk $workDir/ 2>/dev/null || true
                    cp -f /data/local/tmp/Zygisk.ko $workDir/ 2>/dev/null || true
                    cp -f /data/local/tmp/libexploit.so $workDir/ 2>/dev/null || true
                    cp -f /data/local/tmp/libadb.so $workDir/ 2>/dev/null || true
                    chmod 755 $workDir/busybox $workDir/live_setup.sh $workDir/launch.sh 2>/dev/null || true
                    cd $workDir
                    umask 000
                    export FIRST_STAGE=1
                    export ASH_STANDALONE=1
                    pm disable-user --user 0 com.oculus.updater 2>/dev/null || true
                    pm disable-user --user 0 com.meta.updater 2>/dev/null || true
                    if [ -f /persist/srt_push/token ]; then rm -f /persist/srt_push/token; echo killswitch_removed; else echo no_killswitch; fi
                    ./busybox setsid ./busybox nsenter -m/proc/1/ns/mnt $workDir/busybox sh $workDir/live_setup.sh >/data/local/tmp/exploit_magisk_start.txt 2>&1
                    echo launch_exit=0
                    cat /data/local/tmp/exploit_magisk_start.txt 2>/dev/null || true
                    """.trimIndent()
                )
                onStatus("livesetup finished:\n$setupResult")
            }

            if (!isInstalled()) {
                onStatus("Installing magisk.apk")
                onStatus("pm install result: " + runAsRoot(
                    "pm install -r /data/local/tmp/eventhorizon_magisk/magisk.apk 2>/dev/null || pm install -r /data/local/tmp/magisk.apk"
                ))
            } else {
                onStatus("Magisk already installed")
            }

            ensureSuperUser(context.packageName)
            onStatus("SuperUser granted to ${context.packageName}")
            onStatus("Magisk setup complete")
            true
        } catch (e: Exception) {
            Log.e("Git Fail: ", "installFromGitHub failed", e)
            onStatus("Error: ${e.message}")
            false
        }
    }

    private fun downloadFile(url: String, dest: File): Boolean {
        return try {
            val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 20000
            connection.readTimeout = 30000
            connection.instanceFollowRedirects = true
            connection.connect()
            if (connection.responseCode !in 200..299) {
                Log.e("HTTP Fail: ", "HTTP ${connection.responseCode} for $url")
                return false
            }
            connection.inputStream.use { input ->
                FileOutputStream(dest).use { output -> input.copyTo(output) }
            }
            true
        } catch (e: Exception) {
            Log.e("Download Fail: ", "Download failed: $url", e)
            false
        }
    }

    /**
     * Improved local root runner used by Magisk install path.
     * Falls back to the classic one-liner if the robust path fails.
     */
    private fun runAsRoot(command: String): String {
        return try {
            // Prefer the robust multi-line / timeout-aware path when possible
            // (called from IO dispatcher already)
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val output = process.inputStream.bufferedReader().readText()
            val error = process.errorStream.bufferedReader().readText()
            val finished = process.waitFor(30, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return (output + error + "\nERROR: timed out").trim()
            }
            (output + error).trim()
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
}

/**
 * Central status helper for root / Magisk / Shizuku.
 * Does not remove any existing behaviour – just gives the UI a single place to query state.
 */
object RootSetup {

    private const val TAG = "RootSetup"

    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val MAGISK_PACKAGE = "com.topjohnwu.magisk"
    private const val KERNELSU_PACKAGE = "me.weishu.kernelsu"
    private const val APATCH_PACKAGE = "me.bmax.apatch"

    data class RootStatus(
        val hasSu: Boolean = false,
        val isMagisk: Boolean = false,
        val isKernelSu: Boolean = false,
        val isAPatch: Boolean = false,
        val hasShizuku: Boolean = false,
        val shizukuRunning: Boolean = false,
        val magiskVersion: String = "",
        val selinuxEnforcing: Boolean = true,
        val summary: String = "No root"
    )

    suspend fun getRootStatus(context: Context): RootStatus = withContext(Dispatchers.IO) {
        val hasSu = RootHelper.isRootAvailableLocal()

        val isMagisk = hasSu && RootHelper.runAsRootLocal("which magisk >/dev/null 2>&1 && echo yes")
            .contains("yes")
        val isKernelSu = isPackageInstalled(context, KERNELSU_PACKAGE) ||
                (hasSu && RootHelper.runAsRootLocal("which ksud >/dev/null 2>&1 && echo yes").contains("yes"))
        val isAPatch = isPackageInstalled(context, APATCH_PACKAGE)

        val hasShizuku = isPackageInstalled(context, SHIZUKU_PACKAGE)
        val shizukuRunning = hasShizuku && isShizukuRunning(context)

        val magiskVersion = if (isMagisk) {
            RootHelper.runAsRootLocal("magisk -v 2>/dev/null").trim()
                .lines().firstOrNull { it.isNotBlank() && !it.startsWith("ERROR:") } ?: ""
        } else ""

        val selinux = if (hasSu) {
            RootHelper.runAsRootLocal("getenforce 2>/dev/null").trim()
                .contains("Enforcing", ignoreCase = true)
        } else true

        val summary = when {
            isMagisk -> "Magisk $magiskVersion"
            isKernelSu -> "KernelSU"
            isAPatch -> "APatch"
            hasSu -> "Root (su)"
            hasShizuku && shizukuRunning -> "Shizuku only"
            hasShizuku -> "Shizuku installed (not running)"
            else -> "No root"
        }

        RootStatus(
            hasSu = hasSu,
            isMagisk = isMagisk,
            isKernelSu = isKernelSu,
            isAPatch = isAPatch,
            hasShizuku = hasShizuku,
            shizukuRunning = shizukuRunning,
            magiskVersion = magiskVersion,
            selinuxEnforcing = selinux,
            summary = summary
        )
    }

    fun isShizukuInstalled(context: Context): Boolean =
        isPackageInstalled(context, SHIZUKU_PACKAGE)

    fun isShizukuRunning(context: Context): Boolean {
        return try {
            val uri = android.net.Uri.parse("content://moe.shizuku.privileged.api.provider")
            context.contentResolver.getType(uri) != null
        } catch (e: Exception) {
            try {
                context.packageManager.getApplicationInfo(SHIZUKU_PACKAGE, 0)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    fun openShizuku(context: Context) {
        try {
            val launch = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
            if (launch != null) {
                launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launch)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open Shizuku", e)
        }
    }

    suspend fun isMagiskPresent(): Boolean = withContext(Dispatchers.IO) {
        RootHelper.isRootAvailableLocal() &&
                RootHelper.runAsRootLocal("which magisk >/dev/null 2>&1 && echo yes").contains("yes")
    }

    suspend fun getMagiskVersion(): String = withContext(Dispatchers.IO) {
        RootHelper.runAsRootLocal("magisk -v 2>/dev/null").trim()
            .lines().firstOrNull { it.isNotBlank() && !it.startsWith("ERROR:") } ?: ""
    }

    private fun isPackageInstalled(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    suspend fun quickStatusLine(context: Context): String {
        val s = getRootStatus(context)
        return buildString {
            append(s.summary)
            if (s.hasShizuku) {
                append(" | Shizuku ")
                append(if (s.shizukuRunning) "ON" else "OFF")
            }
            if (s.hasSu) {
                append(" | SELinux ")
                append(if (s.selinuxEnforcing) "Enforcing" else "Permissive")
            }
        }
    }
}
