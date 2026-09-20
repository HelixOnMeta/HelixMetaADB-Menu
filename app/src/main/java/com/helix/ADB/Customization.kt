package com.helix

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

// contact blaku64th on discord if you have any issues ^^
class Customization {

    companion object { 
        private const val pref = "payload_pref"
        const val bootmodel = "payload_boot_model"
        const val defaultmodel = "meta-horizonos.glb"

        private const val payload = "exploit/payloads"
        private const val animationpath = "bootanimation"
        private const val magiskpath = "exploit/magisk.apk"
    }

    private fun pushToDevice(
        manager: AppAdbConnectionManager,
        ctx: Utils.ActionContext,
        localFile: File,
        remoteName: String,
        makeExecutable: Boolean
    ): Boolean {
        val remotePath = "/data/local/tmp/$remoteName"
        val pushResult = AdbFileTransfer.pushFile(manager, localFile, remotePath)
        if (pushResult.startsWith("[error]") ||
            pushResult.contains("FAIL", ignoreCase = true)
        ) {
            ctx.log("❌ Push failed: $remoteName — $pushResult")
            return false
        }
        ctx.log("📤 $pushResult")
        if (makeExecutable) {
            ctx.run("chmod 755 \"$remotePath\"")
        }
        return true
    }

    fun listBootModels(assetManager: AssetManager): List<String> {
        return try {
            val names = assetManager.list(animationpath) ?: emptyArray()
            names.filter { it.endsWith(".glb", ignoreCase = true) }.sorted()
        } catch (e: Exception) {
            Log.w("Boot: ", "listBootModels failed", e)
            emptyList()
        }
    }

    fun getSelectedBootModel(context: Context): String {
        val pref = context.getSharedPreferences(pref, 0)
        return pref.getString(bootmodel, defaultmodel) ?: defaultmodel
    }

    fun setSelectedBootModel(context: Context, fileName: String) {
        context.getSharedPreferences(pref, 0)
            .edit()
            .putString(bootmodel, fileName)
            .apply()
    }

    private fun copyBootModel(
        context: Context,
        assetManager: AssetManager,
        cacheDir: File,
        ctx: Utils.ActionContext
    ): File? {
        val selected = getSelectedBootModel(context)
        val candidates = listOf(
            "$animationpath/$selected",
            "$animationpath/$defaultmodel",
            "bootanimation/meta-horizonos.glb"
        ).distinct()

        for (assetPath in candidates) {
            try {
                val outFile = File(cacheDir, defaultmodel)
                assetManager.open(assetPath).use { input ->
                    FileOutputStream(outFile).use { output -> input.copyTo(output) }
                }
                if (outFile.length() > 1000) {
                    ctx.log("🎬 Boot model: $assetPath (${outFile.length() / 1024} KB)")
                    return outFile
                }
            } catch (_: Exception) {
            }
        }
        ctx.log("No boot model .glb found under assets/$animationpath/")
        return null
    }

    private fun copyAssetOrNull(
        assetManager: AssetManager,
        assetPath: String,
        outFile: File,
        ctx: Utils.ActionContext
    ): File? {
        return try {
            assetManager.open(assetPath).use { input ->
                FileOutputStream(outFile).use { output -> input.copyTo(output) }
            }
            outFile
        } catch (e: Exception) {
            ctx.log("Missing asset: $assetPath (${e.message})")
            Log.w("Asset: ", "copyAsset $assetPath", e)
            null
        }
    }

    @Throws(IOException::class)
    fun stagePayloadChain(
        context: Context,
        ctx: Utils.ActionContext,
        adbFile: File,
        adbHomeDir: String,
        assetManager: AssetManager
    ) {
        val manager = AppAdbConnectionManager.getInstance(context)
        val pref = context.getSharedPreferences(pref, 0)

        val enableMagisk = pref.getBoolean("payload_magisk", true)
        val enablePersistentPort = pref.getBoolean("payload_persistent_port", true)
        val enableServicesPatch = pref.getBoolean("payload_services_patched", true)
        val enableSoftReboot = pref.getBoolean("payload_soft_reboot", true)
        val enableBootAnimation = pref.getBoolean("payload_bootanimation", true)

        ctx.log("Pushing Magisk assets and scripts")
        if (enableBootAnimation) {
            ctx.log("Boot model preference: ${getSelectedBootModel(context)}")
            val models = listBootModels(assetManager)
            if (models.isNotEmpty()) {
                ctx.log("Available boot models: ${models.joinToString()}")
            }
        }

        val cacheDir = context.cacheDir
        val staged = mutableListOf<File>()

        fun track(f: File?): File? {
            if (f != null) staged += f
            return f
        }

        val magiskScript = track(
            copyAssetOrNull(
                assetManager, "$payload/magisk.sh",
                File(cacheDir, "magisk.sh"), ctx
            )
        )
        val persistentPort = track(
            copyAssetOrNull(
                assetManager, "$payload/persistent_port.sh",
                File(cacheDir, "persistent_port.sh"), ctx
            )
        )
        val softReboot = track(
            copyAssetOrNull(
                assetManager, "$payload/soft_reboot.sh",
                File(cacheDir, "soft_reboot.sh"), ctx
            )
        )
        val servicesPatched = track(
            copyAssetOrNull(
                assetManager, "$payload/services_patched.sh",
                File(cacheDir, "services_patched.sh"), ctx
            )
        )
        val bootAnimationSh = track(
            copyAssetOrNull(
                assetManager, "$payload/bootanimation.sh",
                File(cacheDir, "bootanimation.sh"), ctx
            )
        )

        val horizonGlb = if (enableBootAnimation) {
            track(copyBootModel(context, assetManager, cacheDir, ctx))
        } else null

        val magiskApk = track(
            copyAssetOrNull(
                assetManager, magiskpath,
                File(cacheDir, "Magisk.apk"), ctx
            )
        )

        val payloadChain = File(cacheDir, "payload_chain.sh")
        val chain = StringBuilder().apply {
            appendLine("#!/system/bin/sh")
            appendLine("if [ -f /persist/srt_push/token ]; then rm -f /persist/srt_push/token; echo 'Killswitch token removed successfully.'; fi")
            if (enableMagisk && magiskScript != null) {
                appendLine("/data/local/tmp/magisk.sh")
            }
            if (enablePersistentPort && persistentPort != null) {
                appendLine("/data/local/tmp/persistent_port.sh")
            }
            if (enableServicesPatch && servicesPatched != null) {
                appendLine("/data/local/tmp/services_patched.sh")
            }
            if (enableBootAnimation && bootAnimationSh != null && horizonGlb != null) {
                appendLine("/data/local/tmp/bootanimation.sh")
            }
            if (enableSoftReboot && softReboot != null) {
                appendLine("/data/local/tmp/soft_reboot.sh")
            }
        }
        payloadChain.writeText(chain.toString())
        staged += payloadChain

        magiskScript?.let { pushToDevice(manager, ctx, it, "magisk.sh", true) }
        persistentPort?.let { pushToDevice(manager, ctx, it, "persistent_port.sh", true) }
        softReboot?.let { pushToDevice(manager, ctx, it, "soft_reboot.sh", true) }
        servicesPatched?.let { pushToDevice(manager, ctx, it, "services_patched.sh", true) }
        bootAnimationSh?.let { pushToDevice(manager, ctx, it, "bootanimation.sh", true) }
        horizonGlb?.let {
            pushToDevice(manager, ctx, it, defaultmodel, false)
            ctx.log("Boot model pushed /data/local/tmp/$defaultmodel")
        }
        payloadChain.let { pushToDevice(manager, ctx, it, "payload_chain.sh", true) }
        magiskApk?.let { pushToDevice(manager, ctx, it, "Magisk.apk", false) }

        staged.forEach { runCatching { it.delete() } }

        ctx.log("Payload chain pushed")
        if (enableBootAnimation && horizonGlb == null) {
            ctx.log("Boot animation enabled but no .glb was pushed — add a model under assets/bootanimation/")
        }
    }
}