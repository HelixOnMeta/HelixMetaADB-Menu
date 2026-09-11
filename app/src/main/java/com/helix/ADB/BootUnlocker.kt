package com.helix

import android.app.Activity
import android.app.AlertDialog
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume

// contact blaku64th on discord if you have any issues ^^
object QuestBootloaderUnlocker {

    const val target = "49845030443200410"

    private val vulnbuilds = setOf(
        "15849800125100000",
        "16476800119700000",
        "16476800118700000"
    )

    private const val directory = "/sdcard/Download/QuestUnlock"
    private const val batdirectory = "$directory/QuestUnlock.bat"
    private const val zipfile = "platform-tools.zip"

    private val mainHandler = Handler(Looper.getMainLooper())

    fun status(ctx: Utils.ActionContext) {
        val serial = IonStackRoot.getDeviceSerials(ctx)
        if (serial.isEmpty()) {
            ctx.log("❌ No ADB device"); ctx.toast("No device"); return
        }
        val incremental = IonStackRoot.getDeviceIncremental(ctx)
        val product = ctx.run("getprop ro.product.name").trim()
        val unlocked = IonStackRoot.isBootloaderUnlocked(ctx)
        val rooted = IonStackRoot.checkRootAccess(ctx)
        val pcUsb = isPcUsbConnected(ctx)
        ctx.log("── Quest Bootloader status ──")
        ctx.log("Serial      : $serial")
        ctx.log("Product     : $product")
        ctx.log("Incremental : $incremental")
        ctx.log("Unlocked    : $unlocked")
        ctx.log("Root        : $rooted")
        ctx.log("PC USB      : $pcUsb")
        ctx.log("Target FW   : $target")
        when {
            unlocked -> { ctx.log("✅ Bootloader already unlocked"); ctx.toast("Already unlocked") }
            incremental == target -> { ctx.log("On QuestStack target firmware"); ctx.toast("Ready for full chain") }
            incremental in vulnbuilds -> { ctx.log("Vulnerable ABL — unlock-only on PC"); ctx.toast("Unlock-only (PC)") }
            else -> { ctx.log("Firmware not known-vulnerable"); ctx.toast("Unsupported FW for auto path") }
        }
    }

    fun prepare(ctx: Utils.ActionContext) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val serial = IonStackRoot.getDeviceSerials(ctx)
                if (serial.isEmpty()) {
                    ctx.log("❌ No ADB device"); ctx.toast("Connect device first"); return@launch
                }
                val incremental = IonStackRoot.getDeviceIncremental(ctx)
                ctx.log("Incremental: $incremental")
                if (IonStackRoot.isBootloaderUnlocked(ctx)) {
                    ctx.log("✅ Already unlocked"); ctx.toast("Already unlocked"); return@launch
                }
                if (incremental != target && incremental !in vulnbuilds) {
                    ctx.log("⚠️ Wrong firmware for auto path"); ctx.toast("Wrong firmware"); return@launch
                }
                if (!IonStackRoot.checkRootAccess(ctx)) {
                    IonStackRoot.runBestRoot(ctx)
                    while (IonStackRoot.isRunning) delay(250)
                    delay(1000)
                }
                if (!IonStackRoot.checkRootAccess(ctx)) {
                    ctx.log("❌ Still no root"); ctx.toast("Root failed"); return@launch
                }
                RootHelper.runRoot(ctx, "resetprop ro.boot.flash.locked 0")
                RootHelper.runRoot(ctx, "resetprop ro.boot.vbmeta.device_state unlocked")
                RootHelper.runRoot(ctx, "resetprop ro.boot.verifiedbootstate orange")
                unlockFlow(ctx)
            } catch (e: Exception) {
                ctx.log("❌ prepare failed: ${e.message}"); ctx.toast("Failed – see log")
            }
        }
    }

    fun ensureRootThenStatus(ctx: Utils.ActionContext) {
        CoroutineScope(Dispatchers.IO).launch {
            if (!IonStackRoot.checkRootAccess(ctx)) {
                IonStackRoot.runBestRoot(ctx)
                while (IonStackRoot.isRunning) delay(250)
                delay(800)
            }
            status(ctx)
        }
    }

    fun unlockFlow(ctx: Utils.ActionContext) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (IonStackRoot.getDeviceSerials(ctx).isEmpty()) {
                    ctx.log("❌ No ADB device"); ctx.toast("No device"); return@launch
                }
                val pcConnected = isPcUsbConnected(ctx)
                ctx.log("PC USB detected: $pcConnected")
                if (pcConnected) {
                    val proceed = showCountdownDialog(
                        "Rebooting to boot screen…",
                        "Headset will reboot to the bootloader / boot menu so you can finish unlock on PC with QuestUnlock.bat + platform-tools.\n\nRebooting in",
                        5
                    )
                    if (!proceed) {
                        ctx.log("⏹ Reboot cancelled"); ctx.toast("Cancelled"); return@launch
                    }
                    ctx.log("🔄 Rebooting to bootloader…"); ctx.toast("Rebooting to bootloader")
                    val r = ctx.runDefault("reboot bootloader")
                    if (r.contains("error", ignoreCase = true) || r.contains("failed", ignoreCase = true)) {
                        ctx.runDefault("reboot")
                    }
                } else {
                    val cont = showSimpleDialog(
                        "PC not detected",
                        "PC not detected. Connect to PC then click Continue to upload needed files.\n\nFiles will be placed at:\n$directory/\n• QuestUnlock.bat\n• platform-tools.zip",
                        "Continue",
                        "Cancel"
                    )
                    if (!cont) {
                        ctx.log("⏹ Upload cancelled"); ctx.toast("Cancelled"); return@launch
                    }
                    if (stageUnlockPackage(ctx)) {
                        ctx.log("✅ Uploaded to $directory"); ctx.toast("Files uploaded – pull from Download/QuestUnlock")
                        showSimpleDialog(
                            "Upload complete",
                            "1. Connect USB to PC\n2. adb pull $directory\n3. Extract platform-tools.zip\n4. Run QuestUnlock.bat as Administrator\n5. Leave headset on boot menu (Vol Down + Power)",
                            "OK",
                            null
                        )
                    } else {
                        ctx.log("❌ Failed to stage unlock package"); ctx.toast("Upload failed – see log")
                    }
                }
            } catch (e: Exception) {
                ctx.log("❌ unlockFlow failed: ${e.message}"); ctx.toast("Failed – see log")
            }
        }
    }

    fun isPcUsbConnected(ctx: Utils.ActionContext): Boolean {
        val config = ctx.run("getprop sys.usb.config").trim().lowercase()
        val state = ctx.run("getprop sys.usb.state").trim().uppercase()
        val mode = ctx.run("getprop persist.sys.usb.config").trim().lowercase()
        val all = "$config $mode"
        val hasData = listOf("adb", "mtp", "ptp", "midi", "rndis").any { it in all }
        val configured = state.contains("CONFIGURED") || state.contains("CONNECTED")
        return hasData && (configured || "adb" in config)
    }

    fun stageUnlockPackage(ctx: Utils.ActionContext): Boolean {
        return try {
            ctx.run("mkdir -p $directory")
            val batLocal = File(AppContext.app.cacheDir, "QuestUnlock.bat")
            batLocal.writeText(buildUnlockBat())
            val batPush = ctx.runDefault("push \"${batLocal.absolutePath}\" \"$batdirectory\"")
            if (batPush.contains("error", ignoreCase = true) || batPush.contains("failed", ignoreCase = true)) {
                ctx.log("❌ Failed to push bat: $batPush"); return false
            }
            ctx.log("✅ Pushed QuestUnlock.bat")
            val zipLocal = resolvePlatformToolsZip(ctx) ?: run {
                ctx.log("❌ platform-tools.zip not found in assets or cache"); return false
            }
            ctx.log("📤 Pushing platform-tools.zip (${zipLocal.length() / 1024 / 1024} MiB)…")
            val zipPush = ctx.runDefault("push \"${zipLocal.absolutePath}\" \"$directory/$zipfile\"")
            if (zipPush.contains("error", ignoreCase = true) || zipPush.contains("failed", ignoreCase = true)) {
                ctx.log("❌ Failed to push zip: $zipPush"); return false
            }
            ctx.log("✅ Pushed platform-tools.zip")
            ctx.run("chmod 644 $batdirectory $directory/$zipfile 2>/dev/null")
            true
        } catch (e: Exception) {
            ctx.log("❌ stageUnlockPackage: ${e.message}"); false
        }
    }

    private fun resolvePlatformToolsZip(ctx: Utils.ActionContext): File? {
        val cache = File(AppContext.app.cacheDir, "platform-tools.zip")
        for (assetName in listOf(zipfile)) {
            try {
                AppContext.app.assets.open(assetName).use { input ->
                    FileOutputStream(cache).use { output -> input.copyTo(output) }
                }
                if (cache.length() > 1_000_000) return cache
            } catch (_: Exception) { }
        }
        listOf(cache, File(AppContext.app.filesDir, "platform-tools.zip"), File("/sdcard/Download/platform-tools.zip"))
            .firstOrNull { it.isFile && it.length() > 1_000_000 }?.let { return it }
        ctx.log("⚠️ Place platform-tools.zip in app assets as \"$zipfile\"")
        return null
    }

    fun buildUnlockBat(): String = """
@echo off
setlocal EnableExtensions EnableDelayedExpansion
title Quest Bootloader Unlock
cd /d "%~dp0"
echo =======================
echo   Quest Unlock helper
echo =======================
if not exist "platform-tools.zip" (
  echo [ERROR] platform-tools.zip not found next to this .bat
  echo   adb pull /sdcard/Download/QuestUnlock
  pause
  exit /b 1
)
where tar >nul 2>&1
if errorlevel 1 (
  powershell -NoProfile -Command "Expand-Archive -Force -Path 'platform-tools.zip' -DestinationPath '.'"
) else (
  tar -xf platform-tools.zip 2>nul
  if errorlevel 1 powershell -NoProfile -Command "Expand-Archive -Force -Path 'platform-tools.zip' -DestinationPath '.'"
)
if exist "platform-tools\adb.exe" (set "PATH=%CD%\platform-tools;%PATH%") else if exist "adb.exe" (set "PATH=%CD%;%PATH%") else (
  echo [ERROR] adb.exe not found after extract
  pause
  exit /b 1
)
echo [OK] tools ready
adb wait-for-device 2>nul
adb devices
echo Optional: reboot to bootloader?
choice /C YN /M "Run 'adb reboot bootloader'"
if errorlevel 2 goto skip
if errorlevel 1 (
  adb reboot bootloader
  timeout /t 5 >nul
  fastboot devices
  fastboot oem device-info 2>nul
)
:skip
echo After unlock, fastboot should report Device unlocked: true
pause
endlocal
""".trimIndent()

    private suspend fun showCountdownDialog(title: String, messageBase: String, seconds: Int): Boolean =
        suspendCancellableCoroutine { cont ->
            mainHandler.post {
                val activity = foregroundActivity()
                if (activity == null) {
                    Handler(Looper.getMainLooper()).postDelayed({ if (cont.isActive) cont.resume(true) }, seconds * 1000L)
                    return@post
                }
                var timer: CountDownTimer? = null
                val dialog = AlertDialog.Builder(activity)
                    .setTitle(title)
                    .setMessage("$messageBase $seconds…")
                    .setCancelable(false)
                    .setNegativeButton("Cancel") { d, _ ->
                        timer?.cancel(); d.dismiss(); if (cont.isActive) cont.resume(false)
                    }
                    .create()
                dialog.setOnShowListener {
                    timer = object : CountDownTimer(seconds * 1000L, 250L) {
                        override fun onTick(millisUntilFinished: Long) {
                            dialog.setMessage("$messageBase ${(millisUntilFinished / 1000L).toInt() + 1}…")
                        }
                        override fun onFinish() {
                            if (dialog.isShowing) dialog.dismiss()
                            if (cont.isActive) cont.resume(true)
                        }
                    }.start()
                }
                dialog.setOnDismissListener { timer?.cancel() }
                try { dialog.show() } catch (_: WindowManager.BadTokenException) {
                    timer?.cancel(); if (cont.isActive) cont.resume(true)
                }
            }
        }

    private suspend fun showSimpleDialog(title: String, message: String, positive: String, negative: String?): Boolean =
        suspendCancellableCoroutine { cont ->
            mainHandler.post {
                val activity = foregroundActivity()
                if (activity == null) {
                    if (cont.isActive) cont.resume(true); return@post
                }
                val builder = AlertDialog.Builder(activity)
                    .setTitle(title).setMessage(message).setCancelable(false)
                    .setPositiveButton(positive) { d, _ -> d.dismiss(); if (cont.isActive) cont.resume(true) }
                if (negative != null) {
                    builder.setNegativeButton(negative) { d, _ -> d.dismiss(); if (cont.isActive) cont.resume(false) }
                }
                try { builder.show() } catch (_: WindowManager.BadTokenException) {
                    if (cont.isActive) cont.resume(true)
                }
            }
        }

    private fun foregroundActivity(): Activity? {
        val app = runCatching { AppContext.app }.getOrNull() ?: return null
        if (app is Activity && !app.isFinishing) return app
        return try {
            val atClass = Class.forName("android.app.ActivityThread")
            val current = atClass.getMethod("currentActivityThread").invoke(null)
            val field = atClass.getDeclaredField("mActivities").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val map = field.get(current) as? Map<*, *> ?: return null
            for (record in map.values) {
                if (record == null) continue
                val rClass = record.javaClass
                val paused = rClass.getDeclaredField("paused").apply { isAccessible = true }.getBoolean(record)
                if (paused) continue
                val act = rClass.getDeclaredField("activity").apply { isAccessible = true }.get(record) as? Activity
                if (act != null && !act.isFinishing) return act
            }
            null
        } catch (_: Exception) { null }
    }
}