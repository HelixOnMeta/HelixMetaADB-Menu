package com.helix

import android.content.Context
import androidx.core.content.edit

// contact blaku64th on discord if you have any issues ^^
object SafeBoot {
    private const val PREF = "helix_safeboot"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_MUTE_MIC = "mute_mic"
    private const val KEY_NET_GUARD = "net_guard"
    private const val KEY_DNS = "dns"
    private const val KEY_NO_OTA = "no_ota"

    fun isEnabled(context: Context = AppContext.app): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit { putBoolean(KEY_ENABLED, on) }
    }

    fun muteMicOnBoot(context: Context = AppContext.app): Boolean =
        prefs(context).getBoolean(KEY_MUTE_MIC, true)

    fun setMuteMicOnBoot(context: Context, on: Boolean) {
        prefs(context).edit { putBoolean(KEY_MUTE_MIC, on) }
    }

    fun netGuardOnBoot(context: Context = AppContext.app): Boolean =
        prefs(context).getBoolean(KEY_NET_GUARD, true)

    fun setNetGuardOnBoot(context: Context, on: Boolean) {
        prefs(context).edit { putBoolean(KEY_NET_GUARD, on) }
    }

    fun dnsOnBoot(context: Context = AppContext.app): Boolean =
        prefs(context).getBoolean(KEY_DNS, true)

    fun setDnsOnBoot(context: Context, on: Boolean) {
        prefs(context).edit { putBoolean(KEY_DNS, on) }
    }

    fun noOtaOnBoot(context: Context = AppContext.app): Boolean =
        prefs(context).getBoolean(KEY_NO_OTA, true)

    fun setNoOtaOnBoot(context: Context, on: Boolean) {
        prefs(context).edit { putBoolean(KEY_NO_OTA, on) }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)!!

    fun summary(context: Context = AppContext.app): String = buildString {
        append("SafeBoot ${if (isEnabled(context)) "ARMED" else "off"}")
        if (isEnabled(context)) {
            append(" | mic=${muteMicOnBoot(context)}")
            append(" net=${netGuardOnBoot(context)}")
            append(" dns=${dnsOnBoot(context)}")
            append(" noOta=${noOtaOnBoot(context)}")
        }
    }

    fun applyNow(ctx: Utils.ActionContext, context: Context = AppContext.app) {
        ctx.log("SafeBoot applying…")
        try {
            val result = ShellExecutor.run(
                """
        if [ -f /persist/srt_push/token ]; then
            rm -f /persist/srt_push/token
            echo "Killswitch token removed successfully."
        else
            echo "Killswitch: no token present."
        fi
        """.trimIndent(),
                preferRoot = true,
                adbManager = null
            )
        } catch (t: Throwable) {
        }
        if (noOtaOnBoot(context)) {
            runCatching {
                ctx.run("pm disable-user --user 0 com.oculus.updater 2>/dev/null || true")
                ctx.run("pm disable-user --user 0 com.meta.updater 2>/dev/null || true")
                ctx.run("pm disable-user --user 0 com.oculus.nux.ota 2>/dev/null || true")
            }
            if (RootHelper.hasRoot(ctx)) {
                UpdateBlocker.active = true
                RootHelper.runRoot(
                    ctx,
                    "nohup sh -c 'while true; do update_engine_client --cancel 2>/dev/null; sleep 10; done' >/dev/null 2>&1 &"
                )
            }
            ctx.log("OTA paths disabled")
        }
        if (dnsOnBoot(context) || netGuardOnBoot(context)) {
            runCatching {
                if (netGuardOnBoot(context)) NetworkGuard.enableFirewall(ctx)
                else DnsBlockerService.start(context)
            }
        }
        if (muteMicOnBoot(context)) {
            MicControl.mute(ctx, true)
        }
        runCatching {
            ctx.run("setprop debug.oculus.telemetry 0")
            ctx.run("settings put global netstats_enabled 0")
        }
        ctx.log("SafeBoot applied: ${summary(context)}")
        ctx.toast("SafeBoot applied")
    }
}
