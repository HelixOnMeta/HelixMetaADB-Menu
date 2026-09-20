package com.helix

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// contact blaku64th on discord if you have any issues ^^
data class FanInfo(
    val path: String = "",
    val type: String = "",
    val curState: Int = 0,
    val maxState: Int = 0,
    val available: Boolean = false
)

object FanUtils {
    private const val TAG = "FanUtils"
    const val SCRIPT_NAME = "fan_override.sh"
    const val DEFAULT_SPEED = 255

    @Volatile
    var preferredSpeed: Int = DEFAULT_SPEED

    @Volatile
    private var cachedPath: String? = null

    suspend fun discoverFan(): FanInfo = withContext(Dispatchers.IO) {
        val discoverCmd = """
            for d in /sys/class/thermal/cooling_device*; do
                [ -f "${'$'}d/type" ] || continue
                t=${'$'}(cat "${'$'}d/type" 2>/dev/null)
                if echo "${'$'}t" | grep -qi fan; then
                    echo "${'$'}d|${'$'}t"
                    exit 0
                fi
            done
            # fallback: first writable cur_state
            for d in /sys/class/thermal/cooling_device*; do
                [ -w "${'$'}d/cur_state" ] || continue
                t=${'$'}(cat "${'$'}d/type" 2>/dev/null || echo unknown)
                echo "${'$'}d|${'$'}t"
                exit 0
            done
        """.trimIndent()

        val line = RootHelper.runAsRootLocal(discoverCmd).trim().lines().firstOrNull { it.contains("|") }
        if (line.isNullOrBlank()) {
            return@withContext FanInfo()
        }

        val (path, type) = line.split("|", limit = 2).let { it[0] to it.getOrElse(1) { "unknown" } }
        val max = RootHelper.readSysfs("$path/max_state").toIntOrNull() ?: 255
        val cur = RootHelper.readSysfs("$path/cur_state").toIntOrNull() ?: 0

        cachedPath = path
        FanInfo(path = path, type = type, curState = cur, maxState = max, available = true)
    }

    suspend fun getFanInfo(): FanInfo {
        val path = cachedPath
        if (path != null) {
            val max = RootHelper.readSysfs("$path/max_state").toIntOrNull() ?: 255
            val cur = RootHelper.readSysfs("$path/cur_state").toIntOrNull() ?: 0
            val type = RootHelper.readSysfs("$path/type").ifBlank { "fan" }
            return FanInfo(path, type, cur, max, true)
        }
        return discoverFan()
    }

    suspend fun setFanState(state: Int): Boolean = withContext(Dispatchers.IO) {
        val info = getFanInfo()
        if (!info.available) {
            Log.w(TAG, "No fan node found")
            return@withContext false
        }
        val clamped = state.coerceIn(0, info.maxState.coerceAtLeast(1))
        preferredSpeed = clamped
        val ok = RootHelper.writeSysfs("${info.path}/cur_state", clamped.toString(), verify = true)
        if (ok) Log.d(TAG, "Fan set to $clamped / ${info.maxState} on ${info.path}")
        ok
    }

    fun getFanOverrideScript(speed: Int = preferredSpeed): String {
        val s = speed.coerceIn(0, 255)
        return """
            #!/system/bin/sh
            # Helix Fan Override
            FAN=""
            for d in /sys/class/thermal/cooling_device*; do
                [ -w "${'$'}d/cur_state" ] || continue
                t=${'$'}(cat "${'$'}d/type" 2>/dev/null)
                if echo "${'$'}t" | grep -qi fan; then
                    FAN="${'$'}d"
                    break
                fi
                [ -z "${'$'}FAN" ] && FAN="${'$'}d"
            done
            [ -z "${'$'}FAN" ] && exit 1
            while true; do
                echo $s > "${'$'}FAN/cur_state" 2>/dev/null
                sleep 2
            done
        """.trimIndent()
    }

    suspend fun startOverride(ctx: Utils.ActionContext, speed: Int = preferredSpeed) {
        val script = getFanOverrideScript(speed)
        RootHelper.writeScript(ctx, SCRIPT_NAME, script)
        RootHelper.runRoot(ctx, "pkill -f $SCRIPT_NAME 2>/dev/null; nohup /data/local/tmp/$SCRIPT_NAME >/dev/null 2>&1 &")
        preferredSpeed = speed
        ctx.log("Fan override started @ $speed")
    }

    suspend fun stopOverride(ctx: Utils.ActionContext) {
        RootHelper.runRoot(ctx, "pkill -f $SCRIPT_NAME 2>/dev/null || true")
        ctx.log("Fan override stopped")
    }
}