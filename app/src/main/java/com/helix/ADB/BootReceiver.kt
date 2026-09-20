package com.helix

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.content.res.AssetFileDescriptor
import android.media.AudioAttributes
import android.media.MediaPlayer
import java.io.File
import java.io.FileOutputStream

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

        Log.i("Boot: ", "Boot event: $action")

        val pending = goAsync()
        val appCtx = context.applicationContext

        appCtx.enableWirelessAdb()
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
            Log.i("Boot:", "Killswitch: $result")
        } catch (t: Throwable) {
            Log.e("Boot:", "Killswitch failed", t)
        }
        try {
            Handler(Looper.getMainLooper()).post {
                BootSound.play(appCtx)
            }
        } catch (t: Throwable) {
            Log.e("Boot: ", "Boot sound failed", t)
        }

        try {
            if (Prefs.disableWifiOnBoot(appCtx)) {
                Log.i("Boot: ", "Disable Wi-Fi on boot enabled")
                ShellExecutor.run("svc wifi disable", preferRoot = true, adbManager = null)
            }
        } catch (t: Throwable) {
            Log.e("Boot: ", "Disable Wi-Fi on boot failed", t)
        }

        try {
            if (Prefs.chargeLimitEnabled(appCtx)) {
                val r = ChargeLimit.applySaved(appCtx)
                Log.i("Boot: ", "ChargeLimit: ${r.method} ok=${r.ok} ${r.detail}")
            }
        } catch (t: Throwable) {
            Log.e("Boot: ", "ChargeLimit failed", t)
        }

        Handler(Looper.getMainLooper()).postDelayed({
            try {
                val launch = Intent(appCtx, MainActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                    putExtra(fromboot, true)
                }
                appCtx.startActivity(launch)
                Log.i("Boot: ", "MainActivity start requested")
            } catch (t: Throwable) {
                Log.e("Boot: ", "Failed to launch MainActivity on boot", t)
            }

            try {
                if (Macros.isBootEnabled(appCtx) && Macros.macrosForBoot(appCtx).isNotEmpty()) {
                    Log.i("Boot: ", "Boot macros enabled — MainActivity will execute after connect")
                }
            } catch (t: Throwable) {
                Log.e("Boot: ", "Boot macros check failed", t)
            }

            try {
                if (SafeBoot.isEnabled(appCtx)) {
                    Log.i("Boot: ", "SafeBoot armed — applying privacy baseline")
                    val bootCtx = object : Utils.ActionContext {
                        override fun run(command: String): String =
                            ShellExecutor.run(command, preferRoot = true, adbManager = null)
                        override fun log(message: String) {
                            Log.i("SafeBoot", message)
                        }
                        override fun toast(message: String) {}
                    }
                    SafeBoot.applyNow(bootCtx, appCtx)
                }
            } catch (t: Throwable) {
                Log.e("Boot: ", "SafeBoot apply failed", t)
            } finally {
                pending.finish()
            }
        }, bootms)
    }

    companion object {
        const val fromboot = "from_boot"
        private const val bootms = 12_000L
    }
}

object BootSound {
    private const val SoundName = "boot_sound.m4a"

    @Volatile
    private var player: MediaPlayer? = null

    fun play(context: Context) {
        val app = context.applicationContext
        try {
            stop()
            val mp = MediaPlayer()
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            mp.setAudioAttributes(attrs)

            val path = openFromAssets(app, mp)
            val opened = path != null || openFromFilesDir(app, mp)
            if (!opened) {
                Log.w("BootSound: ", "No boot sound found under assets/bootanimation/ or fallbacks")
                mp.release()
                return
            }

            mp.setOnCompletionListener { stop() }
            mp.setOnErrorListener { _, what, extra ->
                Log.e("BootSound: ", "MediaPlayer error what=$what extra=$extra")
                stop()
                true
            }
            mp.prepare()
            mp.start()
            player = mp
            Log.i("BootSound: ", "Boot sound playing (${path ?: "filesDir/$SoundName"})")
        } catch (t: Throwable) {
            Log.e("BootSound: ", "Failed to play boot sound", t)
            stop()
        }
    }

    fun stop() {
        runCatching {
            player?.stop()
            player?.release()
        }
        player = null
    }

    fun installFromFile(context: Context, source: File): Boolean {
        return try {
            val dest = File(context.filesDir, SoundName)
            source.inputStream().use { input ->
                FileOutputStream(dest).use { output -> input.copyTo(output) }
            }
            dest.isFile && dest.length() > 0
        } catch (t: Throwable) {
            Log.e("BootSound: ", "installFromFile failed", t)
            false
        }
    }

    private fun openFromAssets(context: Context, mp: MediaPlayer): String? {
        for (path in listOf("bootanimation/" + SoundName, SoundName)) {
            try {
                val afd: AssetFileDescriptor = context.assets.openFd(path)
                mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                return path
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun openFromFilesDir(context: Context, mp: MediaPlayer): Boolean {
        val f = File(context.filesDir, SoundName)
        if (!f.isFile || f.length() == 0L) return false
        return try {
            mp.setDataSource(f.absolutePath)
            true
        } catch (_: Exception) {
            false
        }
    }
}