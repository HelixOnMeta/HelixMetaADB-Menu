package com.helix

import android.content.Context
import android.util.Log

object ChargeLimit {
    private const val TAG = "ChargeLimit"

    private val LIMIT_NODES = listOf(
        "/sys/class/power_supply/battery/charge_capacity_limit",
        "/sys/class/power_supply/battery/charge_control_limit",
        "/sys/class/power_supply/bms/charge_capacity_limit",
        "/sys/devices/platform/soc/soc:qcom,bms/power_supply/bms/charge_capacity_limit"
    )

    data class Result(
        val ok: Boolean,
        val percent: Int,
        val method: String,
        val detail: String
    )

    fun enabled(context: Context = AppContext.app): Boolean =
        Prefs.chargeLimitEnabled(context)

    fun percent(context: Context = AppContext.app): Int =
        Prefs.chargeLimitPercent(context).coerceIn(50, 100)

    fun setEnabled(context: Context, on: Boolean) {
        Prefs.setChargeLimitEnabled(context, on)
    }

    fun setPercent(context: Context, pct: Int) {
        Prefs.setChargeLimitPercent(context, pct.coerceIn(50, 100))
    }

    fun applySaved(context: Context = AppContext.app): Result {
        val on = enabled(context)
        val pct = if (on) percent(context) else 100
        return apply(pct, softFallback = on)
    }

    fun apply(percent: Int, softFallback: Boolean = true): Result {
        val pct = percent.coerceIn(50, 100)

        val root = tryRootWrite(pct)
        if (root.ok) return root

        val adb = tryAdbWrite(pct)
        if (adb.ok) return adb

        if (softFallback) {
            val soft = trySoftFullSpoof(pct)
            if (soft.ok) return soft
        }

        return Result(
            ok = false,
            percent = pct,
            method = "none",
            detail = "Root required for real charge limit. Soft dumpsys also failed. (${root.detail})"
        )
    }

    fun trySoftFullSpoof(percent: Int = 100): Result {
        val pct = percent.coerceIn(0, 100)
        val script = """
            dumpsys battery set level $pct
            dumpsys battery set status 5
            dumpsys battery set ac 1
            dumpsys battery set usb 0
            echo SOFT_OK
        """.trimIndent()
        val out = ShellExecutor.run(script, preferRoot = false, adbManager = null)
        val ok = out.contains("SOFT_OK") || !out.startsWith("ERROR:")
        Log.i(TAG, "soft spoof pct=$pct ok=$ok out=${out.take(200)}")
        return Result(
            ok = ok,
            percent = pct,
            method = "dumpsys-soft",
            detail = if (ok) {
                "Soft spoof ON (level=$pct status=full). Does NOT stop real charging — LED/UI only. Root needed for hardware limit."
            } else {
                "Soft spoof failed: ${out.trim()}"
            }
        )
    }

    fun clearSoftSpoof(): Result {
        val out = ShellExecutor.run(
            "dumpsys battery reset; echo SOFT_RESET",
            preferRoot = false,
            adbManager = null
        )
        val ok = out.contains("SOFT_RESET") || !out.startsWith("ERROR:")
        return Result(ok, 100, "dumpsys-reset", if (ok) "Battery reporting reset to real values" else out.trim())
    }

    private fun tryRootWrite(pct: Int): Result {
        val script = buildString {
            appendLine("PCT=$pct")
            appendLine("ok=0")
            for (node in LIMIT_NODES) {
                appendLine("if [ -f \"$node\" ]; then")
                appendLine("  echo \$PCT > \"$node\" 2>/dev/null && ok=1 && echo \"wrote $node=\$PCT\"")
                appendLine("fi")
            }
            appendLine("if [ \"\$PCT\" -ge 100 ]; then")
            appendLine("  [ -f /sys/class/power_supply/battery/charging_enabled ] && echo 1 > /sys/class/power_supply/battery/charging_enabled 2>/dev/null")
            appendLine("  [ -f /sys/class/power_supply/battery/input_suspend ] && echo 0 > /sys/class/power_supply/battery/input_suspend 2>/dev/null")
            appendLine("fi")
            appendLine("if [ \"\$ok\" = 1 ]; then echo CHARGE_LIMIT_OK; else echo CHARGE_LIMIT_FAIL; ls /sys/class/power_supply/battery/ 2>/dev/null | head -30; fi")
        }
        val out = ShellExecutor.runScriptAsRoot(script)
        val ok = out.contains("CHARGE_LIMIT_OK")
        Log.i(TAG, "root write pct=$pct ok=$ok out=${out.take(300)}")
        return Result(ok, pct, "root-sysfs", out.trim().ifBlank { "empty" })
    }

    private fun tryAdbWrite(pct: Int): Result {
        val mgr = runCatching {
            AppAdbConnectionManager.getInstance(AppContext.app)
        }.getOrNull() ?: return Result(false, pct, "adb", "ADB not available")

        val node = LIMIT_NODES.first()
        val cmd = "echo $pct > $node 2>/dev/null && cat $node || echo ADB_FAIL"
        val out = ShellExecutor.run(cmd, preferRoot = true, adbManager = mgr)
        val ok = out.trim().startsWith(pct.toString()) || (out.contains(pct.toString()) && !out.contains("ADB_FAIL"))
        return Result(ok, pct, "adb-shell", out.trim().ifBlank { "empty" })
    }

    fun readCurrentLimit(): String {
        val script = LIMIT_NODES.joinToString("\n") { node ->
            """if [ -f "$node" ]; then echo "$node=$(cat "$node" 2>/dev/null)"; fi"""
        } + "\necho ---; ls /sys/class/power_supply/battery/ 2>/dev/null | head -40"
        return ShellExecutor.runScriptAsRoot(script).trim()
    }

    fun statusLine(context: Context = AppContext.app): String {
        val on = enabled(context)
        val pct = percent(context)
        return if (on) "Charge limit ON @ $pct% (root if available, else soft spoof)"
        else "Charge limit OFF (charges to 100%)"
    }
}