package com.helix

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.os.Build

// contact blaku64th on discord if you have any issues ^^
private const val adbwifienabled = "adb_wifi_enabled"
class BootReceiver : BroadcastReceiver() {

    fun Context.enableWirelessAdb() {
        try {
            Settings.Global.putInt(contentResolver, "adb_wifi_enabled", 1)
            Settings.Global.putInt(contentResolver, Settings.Global.ADB_ENABLED, 1)
        } catch (e: Exception) {
            Log.e("Error", "Missing WRITE_SECURE_SETTINGS permission", e)
        }
        try {
            Settings.Global.putInt(
                contentResolver,
                adbwifienabled,
                1
            )
        } catch (e: SecurityException) {
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

        Log.i("Boot: ", "Boot event: $action")

        val pending = goAsync()
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                val launch = Intent(context, MainActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                    putExtra(fromboot, true)
                }
                context.startActivity(launch)
                Log.i("Boot: ", "MainActivity start requested")
            } catch (t: Throwable) {
                Log.e("Boot: ", "Failed to launch MainActivity on boot", t)
            } finally { 
                context.enableWirelessAdb()
                pending.finish()
            }
        }, bootms)
    }

    companion object { 
        const val fromboot = "from_boot"
        private const val bootms = 12_000L
    }
}
