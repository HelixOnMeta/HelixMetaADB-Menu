package com.helix

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

// contact blaku64th on discord if you have any issues ^^
object FridaServer {
    private const val TAG = "FridaServer"
    const val REMOTE_PATH = "/data/local/tmp/frida-server"
    const val DEFAULT_PORT = 27042
    const val SCRIPT_NAME = "frida_keepalive.sh"
    @Volatile
    var preferredVersion: String = "16.5.9"

    fun isRunning(ctx: Utils.ActionContext): Boolean {
        val out = RootHelper.runRoot(ctx, "pgrep -f frida-server 2>/dev/null || true")
        return out.lines().any { it.trim().toIntOrNull() != null }
    }

    fun status(ctx: Utils.ActionContext): String {
        val running = isRunning(ctx)
        val exists = RootHelper.runRoot(ctx, "test -f $REMOTE_PATH && echo yes || echo no").contains("yes")
        val port = RootHelper.runRoot(ctx, "ss -ltnp 2>/dev/null | grep frida || netstat -ltnp 2>/dev/null | grep frida || true")
        return buildString {
            appendLine("frida-server binary: ${if (exists) "present" else "missing"} ($REMOTE_PATH)")
            appendLine("running: $running")
            if (port.isNotBlank()) appendLine("listen:\n$port")
        }
    }

    suspend fun ensureBinary(ctx: Utils.ActionContext, forceDownload: Boolean = false): Boolean =
        withContext(Dispatchers.IO) {
            val exists = RootHelper.runRoot(ctx, "test -x $REMOTE_PATH && echo yes || echo no").contains("yes")
            if (exists && !forceDownload) {
                ctx.log("frida-server already on device")
                return@withContext true
            }
            val assetNames = listOf(
                "exploit/frida-server",
                "frida-server",
                "bin/frida-server"
            )
            for (asset in assetNames) {
                try {
                    AppContext.app.assets.open(asset).use { input ->
                        val tmp = File(AppContext.app.cacheDir, "frida-server")
                        tmp.outputStream().use { input.copyTo(it) }
                        tmp.setExecutable(true)
                        val push = ctx.runDefault("push \"${tmp.absolutePath}\" \"$REMOTE_PATH\"")
                        RootHelper.runRoot(ctx, "chmod 755 $REMOTE_PATH")
                        if (!push.contains("error", true) && !push.contains("FAIL", true)) {
                            ctx.log("Pushed frida-server from assets/$asset")
                            return@withContext true
                        }
                    }
                } catch (_: Exception) {
                }
            }
            val url =
                "https://github.com/frida/frida/releases/download/${preferredVersion}/frida-server-${preferredVersion}-android-arm64.xz"
            ctx.log("Downloading frida-server $preferredVersion…")
            val xz = File(AppContext.app.cacheDir, "frida-server.xz")
            val raw = File(AppContext.app.cacheDir, "frida-server")
            return@withContext try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 30_000
                conn.readTimeout = 120_000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "Helix/Frida")
                conn.connect()
                if (conn.responseCode !in 200..299) {
                    ctx.log("HTTP ${conn.responseCode} downloading frida-server")
                    false
                } else {
                    conn.inputStream.use { input -> xz.outputStream().use { input.copyTo(it) } }
                    ctx.runDefault("push \"${xz.absolutePath}\" \"/data/local/tmp/frida-server.xz\"")
                    val extract = RootHelper.runRoot(
                        ctx,
                        """
                        cd /data/local/tmp
                        (command -v xz >/dev/null && xz -d -f frida-server.xz) || \
                        (busybox xz -d -f frida-server.xz 2>/dev/null) || \
                        (toybox xz -d -f frida-server.xz 2>/dev/null) || true
                        if [ -f frida-server ]; then
                          chmod 755 frida-server
                          echo OK
                        else
                          echo FAIL
                        fi
                        """.trimIndent()
                    )
                    val ok = extract.contains("OK")
                    if (ok) ctx.log("frida-server installed at $REMOTE_PATH")
                    else ctx.log("Extract failed — push an arm64 frida-server manually to $REMOTE_PATH")
                    ok
                }
            } catch (e: Exception) {
                ctx.log("Download failed: ${e.message}")
                false
            }
        }

    fun start(ctx: Utils.ActionContext, listenAll: Boolean = true) {
        if (!RootHelper.hasRoot(ctx)) {
            ctx.log("Root required for frida-server")
            ctx.toast("Root required")
            return
        }
        val host = if (listenAll) "0.0.0.0" else "127.0.0.1"
        RootHelper.runRoot(ctx, "pkill -f frida-server 2>/dev/null || true")
        val script = """
            #!/system/bin/sh
            while true; do
              if ! pgrep -f frida-server >/dev/null 2>&1; then
                $REMOTE_PATH -l $host:$DEFAULT_PORT -D >/dev/null 2>&1
              fi
              sleep 3
            done
        """.trimIndent()
        RootHelper.writeScript(ctx, SCRIPT_NAME, script)
        RootHelper.runRoot(
            ctx,
            "chmod 755 $REMOTE_PATH 2>/dev/null; nohup /data/local/tmp/$SCRIPT_NAME >/dev/null 2>&1 &"
        )
        RootHelper.runRoot(ctx, "nohup $REMOTE_PATH -l $host:$DEFAULT_PORT -D >/dev/null 2>&1 &")
        val running = isRunning(ctx)
        ctx.log(if (running) "frida-server started on $host:$DEFAULT_PORT" else "start attempted — check status")
        ctx.toast(if (running) "Frida ON :$DEFAULT_PORT" else "Frida start failed")
    }

    fun stop(ctx: Utils.ActionContext) {
        RootHelper.runRoot(ctx, "pkill -f $SCRIPT_NAME 2>/dev/null || true")
        RootHelper.runRoot(ctx, "pkill -f frida-server 2>/dev/null || true")
        ctx.log("frida-server stopped")
        ctx.toast("Frida OFF")
    }
}
