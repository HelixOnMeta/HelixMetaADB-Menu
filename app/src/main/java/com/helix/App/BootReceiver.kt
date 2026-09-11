package com.helix

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

// contact blaku64th on discord if you have any issues ^^
private const val adbwifienabled = "adb_wifi_enabled"

class BootReceiver : BroadcastReceiver() {

    fun Context.enableWirelessAdb() {
        try {
            Settings.Global.putInt(contentResolver, "adb_wifi_enabled", 1)
            Settings.Global.putInt(contentResolver, Settings.Global.ADB_ENABLED, 1)
        } catch (e: Exception) {
            Log.e("BootReceiver", "Missing WRITE_SECURE_SETTINGS permission", e)
        }
        try {
            Settings.Global.putInt(contentResolver, adbwifienabled, 1)
        } catch (_: SecurityException) {
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != "com.htc.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }

        Log.i(TAG, "Boot event: $action")

        val pending = goAsync()
        val appCtx = context.applicationContext

        // Enable wireless ADB ASAP (needs WRITE_SECURE_SETTINGS from prior session)
        appCtx.enableWirelessAdb()

        Handler(Looper.getMainLooper()).postDelayed({
            try {
                // Launch UI so user sees status; also carries from_boot for MainActivity macros
                val launch = Intent(appCtx, MainActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                    putExtra(fromboot, true)
                }
                appCtx.startActivity(launch)
                Log.i(TAG, "MainActivity start requested")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to launch MainActivity on boot", t)
            }

            // Also try running macros here without UI if ADB local shell works later.
            // MainActivity will re-run when connected; this is a best-effort early path.
            try {
                if (Macros.isBootEnabled(appCtx) && Macros.macrosForBoot(appCtx).isNotEmpty()) {
                    Log.i(TAG, "Boot macros enabled — MainActivity will execute after connect")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Boot macros check failed", t)
            } finally {
                pending.finish()
            }
        }, bootms)
    }

    companion object {
        private const val TAG = "BootReceiver"
        const val fromboot = "from_boot"
        private const val bootms = 12_000L
    }
}
