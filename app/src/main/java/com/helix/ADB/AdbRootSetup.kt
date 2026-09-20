package com.helix

import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

// contact blaku64th on discord if you have any issues ^^
object IonStackRoot {
 
    private const val ionstackurl =
        "https://raw.githubusercontent.com/darknight1050/quest1-bootloader-unlocker-web/513ae57d7ddd0011059df0fb2a875375b3f99dec/binaries/ionstack"
    private const val ionstacksha =
        "ECD366CEEFC0BAFD0CE8C35A9832FFCC425D2E80F915CCAE0E8B48B881977056"
    private const val ionstackpath = "/data/local/tmp/ionstack"
    private const val tmppath = "/data/local/tmp"

    private const val exploitpath = "exploit"
    private const val payloadpath = "exploit/payloads" 
 
    @Volatile
    var cocainetradefirmware: String = "https://files.cocaine.trade/firmware/meta/"

    const val baseincremental = "52168470043600520"

    val patched = setOf(
        "52345320040100520",
        "3697600032300610"
    )

    private val ionstackenv = listOf(
        "IONSTACK_STAGE=full",
        "IONSTACK_SPRAY_STALL=memfd",
        "IONSTACK_KS_CORE=7",
        "IONSTACK_KS_COLLISIONS=4",
        "IONSTACK_FOPS_SAFE_TABLE=0",
        "IONSTACK_TREE_ENTRY_WRITE=1",
        "IONSTACK_FOPS_LOCK_OWNER_MODE=",
        "IONSTACK_PAGE_SETUP_ATTEMPTS=8",
        "IONSTACK_MEMFD_GATE_MAX_FIRES=1",
        "IONSTACK_STALL_HOLD_MS=6000",
        "IONSTACK_PTRACE_ROUTE_ATTEMPTS=1",
        "IONSTACK_MIN_WINDOW=1",
        "IONSTACK_RECLAIM_PERF_COUNTERS=1",
        "IONSTACK_RECLAIM_PFN_IDENTITY=1",
        "IONSTACK_RECLAIM_PHYS_PFN_START=0x80000",
        "IONSTACK_RECLAIM_PHYS_PFN_END=0x180000",
        "IONSTACK_RECLAIM_REQUIRE_PFN_MATCH=1",
        "IONSTACK_PAYLOAD=",
        "IONSTACK_PAYLOAD_TIMEOUT=120",
        "IONSTACK_XRW_KMEM=1",
        "IONSTACK_XRW_ROOT=1",
        "IONSTACK_XRW_HOLD=1",
        "IONSTACK_ROOT_WALK_MAX=110",
        "IONSTACK_SELF_ROOT=0",
        "IONSTACK_KMEM_CFG_BUDGET=20000",
        "IONSTACK_PIPE_ORACLE_FAST=1"
    )

    private val success = listOf(
        "ROOT VERDICT root=1",
        "xrw: ROOT held",
        "root=1",
        "ROOT held"
    )

    private val devicepath = mapOf(
        QuestModel.QUEST_2 to "$exploitpath/quest2pancake",
        QuestModel.QUEST_3 to "$exploitpath/quest3pancake",
        QuestModel.QUEST_3S to "$exploitpath/quest3spancake",
        QuestModel.QUEST_PRO to "$exploitpath/questpropancake"
    )

    @Volatile
    var isRunning = false

    enum class QuestModel {
        QUEST_1, QUEST_2, QUEST_PRO, QUEST_3, QUEST_3S, UNKNOWN
    }
    fun detectQuestModel(ctx: Utils.ActionContext): QuestModel {
        val name = ctx.run("getprop ro.product.name").trim().lowercase()
        val model = ctx.run("getprop ro.product.model").trim().lowercase()
        val device = ctx.run("getprop ro.product.device").trim().lowercase()
        val board = ctx.run("getprop ro.product.board").trim().lowercase()
        val fingerprint = ctx.run("getprop ro.build.fingerprint").trim().lowercase()
        val all = "$name $model $device $board $fingerprint"
        return when {
            "3s" in all || "quest3s" in all || "quest 3s" in all -> QuestModel.QUEST_3S
            "quest 3" in all || "quest3" in all || "eureka" in all || "panther" in all -> QuestModel.QUEST_3
            "quest pro" in all || "questpro" in all || "seacliff" in all -> QuestModel.QUEST_PRO
            "quest 2" in all || "quest2" in all || "hollywood" in all -> QuestModel.QUEST_2
            "quest 1" in all || "quest1" in all ||
                    ("monterey" in all && "pro" !in all && "seacliff" !in all) -> QuestModel.QUEST_1
            else -> QuestModel.UNKNOWN
        }
    }

    fun supportsIonStack(model: QuestModel): Boolean = model != QuestModel.UNKNOWN

    fun defaultIncrementalFor(model: QuestModel): String? = when (model) {
        QuestModel.QUEST_3 -> baseincremental
        else -> null
    }

    fun firmwareUrl(model: QuestModel, incremental: String): String? {
        val enc = URLEncoder.encode(incremental, StandardCharsets.UTF_8.name())
        return when (model) {
            QuestModel.QUEST_1 -> cocainetradefirmware + "/Quest%201/q1_$enc.zip"
            QuestModel.QUEST_2 -> cocainetradefirmware + "/Quest%202/q2_$enc.zip"
            QuestModel.QUEST_PRO -> cocainetradefirmware + "/Quest%20Pro/qp_$enc.zip"
            QuestModel.QUEST_3 -> cocainetradefirmware + "/Quest%203/q3_$enc.zip"
            QuestModel.QUEST_3S -> cocainetradefirmware + "/Quest%203S/q3s_$enc.zip"
            QuestModel.UNKNOWN -> null
        }
    }

    fun confCdnUrl(model: QuestModel, incremental: String): String? {
        val base = cocainetradefirmware.trim().trimEnd('/')
        if (base.isEmpty()) return null
        val prefix = when (model) {
            QuestModel.QUEST_1 -> "q1"
            QuestModel.QUEST_2 -> "q2"
            QuestModel.QUEST_PRO -> "qp"
            QuestModel.QUEST_3 -> "q3"
            QuestModel.QUEST_3S -> "q3s"
            else -> return null
        }
        return "$base/${prefix}_$incremental.conf"
    }

    fun getDeviceSerials(ctx: Utils.ActionContext): String =
        ctx.run("getprop ro.serialno").trim()

    fun getDeviceIncremental(ctx: Utils.ActionContext): String =
        ctx.run("getprop ro.build.version.incremental").trim().replace("\r", "")

    fun isBootloaderUnlocked(ctx: Utils.ActionContext): Boolean {
        val locked = ctx.run("getprop ro.boot.flash.locked").trim()
        val vbmeta = ctx.run("getprop ro.boot.vbmeta.device_state").trim()
        val verified = ctx.run("getprop ro.boot.verifiedbootstate").trim()
        if (locked == "0") return true
        if (vbmeta.equals("unlocked", ignoreCase = true)) return true
        if (verified.equals("orange", ignoreCase = true) || verified.equals("yellow", ignoreCase = true)) return true
        return false
    }

    fun checkRootAccess(ctx: Utils.ActionContext): Boolean {
        val methods = listOf(
            { RootHelper.runRoot(ctx, "id 2>/dev/null").contains("uid=0") },
            { ctx.run("id").contains("uid=0") },
            { ctx.run("whoami").trim() == "root" },
            { ctx.run("test -f /system/bin/su && echo exists").contains("exists") }
        )
        return methods.any { it() }
    }

    private fun sha256Hex(file: File): String? {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    digest.update(buf, 0, n)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.e("Root Error: ", "sha256 failed", e)
            null
        }
    }

    private fun hashMatches(file: File, expected: String = ionstacksha): Boolean {
        val actual = sha256Hex(file) ?: return false
        return actual.equals(expected, ignoreCase = true)
    }

    fun prepareVerifiedIonstack(ctx: Utils.ActionContext): File? {
        val cacheDir = File(AppContext.app.cacheDir, "queststack").apply { mkdirs() }
        val dest = File(cacheDir, "ionstack")
        val partial = File(cacheDir, "ionstack.partial")

        if (dest.isFile && hashMatches(dest)) {
            ctx.log("✅ Using cached SHA-256 verified ionstack")
            return dest
        }
        if (dest.exists()) {
            ctx.log("⚠️ Cached ionstack failed SHA-256 — replacing")
            dest.delete()
        }
        partial.delete()

        try {
            AppContext.app.assets.open(exploitpath).use { input ->
                FileOutputStream(partial).use { output -> input.copyTo(output) }
            }
            if (hashMatches(partial)) {
                partial.renameTo(dest)
                dest.setExecutable(true)
                ctx.log("✅ ionstack from assets — SHA-256 $ionstacksha")
                return dest
            }
            ctx.log("⚠️ Asset ionstack SHA mismatch — will try download")
            partial.delete()
        } catch (_: Exception) { }

        ctx.log("⬇ Downloading ionstack from QuestStack URL…")
        return try {
            val conn = URL(ionstackurl).openConnection() as HttpURLConnection
            conn.connectTimeout = 30_000
            conn.readTimeout = 120_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "QuestStack/Helix/3.0")
            conn.connect()
            if (conn.responseCode !in 200..299) {
                ctx.log("❌ HTTP ${conn.responseCode} for ionstack")
                return null
            }
            conn.inputStream.use { input ->
                FileOutputStream(partial).use { output -> input.copyTo(output) }
            }
            if (!hashMatches(partial)) {
                val actual = sha256Hex(partial) ?: "unavailable"
                ctx.log("❌ ionstack SHA mismatch. Expected $ionstacksha got $actual")
                partial.delete()
                return null
            }
            partial.renameTo(dest)
            dest.setExecutable(true)
            ctx.log("✅ ionstack verified: SHA-256 $ionstacksha")
            dest
        } catch (e: Exception) {
            partial.delete()
            ctx.log("❌ Could not prepare ionstack: ${e.message}")
            null
        }
    }

    fun pushIonstack(ctx: Utils.ActionContext, local: File): Boolean {
        ctx.log("📤 Pushing ionstack → $ionstackpath")
        val push = ctx.runDefault("push \"${local.absolutePath}\" \"$ionstackpath\"")
        if (push.contains("error", ignoreCase = true) || push.contains("failed", ignoreCase = true)) {
            ctx.log("❌ Push failed: $push")
            return false
        }
        ctx.run("chmod 755 $ionstackpath")
        ctx.log("✅ Ionstack pushed")
        return true
    }

    private fun stageCompanionPayloads(ctx: Utils.ActionContext, model: QuestModel) {
        devicepath[model]?.let { asset ->
            if (pushAssetToDevice(ctx, asset, "$tmppath/$asset")) {
                ctx.run("chmod 755 $tmppath/$asset")
                ctx.log("✅ Staged $asset")
            }
        }
        listOf(
            "$payloadpath/magisk.sh" to "$tmppath/magisk.sh",
            "$exploitpath/busybox" to "$tmppath/busybox",
            "$payloadpath/cheese_root.sh" to "$tmppath/cheese_root.sh",
            "$payloadpath/cheese_launch.sh" to "$tmppath/cheese_launch.sh",
            "$exploitpath/magisk.apk" to "$tmppath/magisk.apk",
            "$exploitpath/Zygisk.ko" to "$tmppath/Zygisk.ko"
        ).forEach { (asset, remote) ->
            if (pushAssetToDevice(ctx, asset, remote)) {
                if (remote.endsWith(".sh") || remote.endsWith("busybox") || !remote.contains(".")) {
                    ctx.run("chmod 755 \"$remote\"")
                }
            }
        }
    }

    private fun downloadToCache(url: String, dest: File): Boolean {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 25_000
            conn.readTimeout = 60_000
            conn.instanceFollowRedirects = true
            conn.connect()
            if (conn.responseCode !in 200..299) {
                Log.e("Root Error: ", "HTTP ${conn.responseCode} for $url")
                return false
            }
            conn.inputStream.use { input -> dest.outputStream().use { output -> input.copyTo(output) } }
            dest.setExecutable(true)
            true
        } catch (e: Exception) {
            Log.e("Root Error: ", "download failed: $url", e)
            false
        }
    }

    fun pushFromUrl(ctx: Utils.ActionContext, url: String, remotePath: String): Boolean {
        val context = AppContext.app
        val name = remotePath.substringAfterLast('/').ifBlank { "dl.bin" }
        val temp = File(context.cacheDir, "dl_${System.currentTimeMillis()}_$name")
        return try {
            ctx.log("⬇ Downloading…\n   $url")
            if (!downloadToCache(url, temp)) {
                ctx.log("❌ Download failed")
                return false
            }
            ctx.log("📤 Pushing → $remotePath (${temp.length()} bytes)")
            val pushResult = ctx.runDefault("push \"${temp.absolutePath}\" \"$remotePath\"")
            if (pushResult.contains("error", ignoreCase = true) || pushResult.contains("failed", ignoreCase = true)) {
                ctx.log("❌ Push failed: $pushResult")
                return false
            }
            if (remotePath.endsWith("preload") || remotePath.endsWith("exploit") || remotePath.endsWith("ionstack")) {
                ctx.run("chmod +x \"$remotePath\"")
            }
            true
        } catch (e: Exception) {
            ctx.log("❌ pushFromUrl: ${e.message}")
            false
        } finally {
            temp.delete()
        }
    }

    fun pushAssetToDevice(ctx: Utils.ActionContext, assetPath: String, remotePath: String): Boolean {
        try {
            val context = AppContext.app
            try { context.assets.open(assetPath).close() } catch (_: Exception) { return false }
            val tempFile = File(context.cacheDir, "adb_push_${System.currentTimeMillis()}")
            context.assets.open(assetPath).use { input -> tempFile.outputStream().use { output -> input.copyTo(output) } }
            if (assetPath.endsWith("preload") || assetPath.endsWith("exploit") || assetPath == "ionstack" || assetPath.endsWith("/ionstack") || assetPath.endsWith("ionstack") ||
                assetPath.contains("pancake") || assetPath.endsWith("busybox")
            ) tempFile.setExecutable(true)
            val pushResult = ctx.runDefault("push \"${tempFile.absolutePath}\" \"$remotePath\"")
            tempFile.delete()
            if (pushResult.contains("error", ignoreCase = true) || pushResult.contains("failed", ignoreCase = true)) return false
            if (assetPath.endsWith("preload") || assetPath.endsWith("exploit") || assetPath == "ionstack" || assetPath.endsWith("/ionstack") || assetPath.endsWith("ionstack") ||
                assetPath.contains("pancake") || assetPath.endsWith("busybox") || assetPath.endsWith(".sh")
            ) ctx.run("chmod +x \"$remotePath\"")
            return true
        } catch (e: Exception) {
            Log.e("Root Error: ", "pushAsset $assetPath", e)
            return false
        }
    }

    fun pushAssetsFromFolder(ctx: Utils.ActionContext, assetFolder: String, remoteDir: String): Boolean {
        return try {
            val assets = AppContext.app.assets.list(assetFolder) ?: emptyArray()
            if (assets.isEmpty()) {
                ctx.log("❌ No assets found in folder: $assetFolder")
                return false
            }
            ctx.log("📦 Found ${assets.size} assets in $assetFolder")
            var successCount = 0
            for (assetName in assets) {
                if (pushAssetToDevice(ctx, "$assetFolder/$assetName", "$remoteDir/$assetName")) successCount++
            }
            ctx.log("✅ Pushed $successCount/${assets.size} files")
            successCount > 0
        } catch (e: Exception) {
            ctx.log("❌ Error pushing assets from folder: ${e.message}")
            false
        }
    }

    fun ensureIonstackConf(ctx: Utils.ActionContext): Boolean {
        val incremental = getDeviceIncremental(ctx)
        val model = detectQuestModel(ctx)
        val defaultInc = defaultIncrementalFor(model)
        if (defaultInc != null && incremental.equals(defaultInc, ignoreCase = true)) {
            ctx.log("✅ $model default incremental ($defaultInc) — built-in offsets OK")
            return true
        }
        val remote = "/data/local/tmp/ionstack.conf"
        confCdnUrl(model, incremental)?.let { url ->
            if (pushFromUrl(ctx, url, remote)) {
                ctx.log("✅ ionstack.conf from CDN ($model)")
                return true
            }
            ctx.log("⚠️ CDN conf miss: $url")
        }
        val assetCandidates = when (model) {
            QuestModel.QUEST_2 -> listOf("$exploitpath/ionstack_q2.conf", "$exploitpath/ionstack.conf", "ionstack_q2.conf", "ionstack.conf")
            QuestModel.QUEST_3S -> listOf("$exploitpath/ionstack_q3s.conf", "$exploitpath/ionstack.conf", "ionstack_q3s.conf", "ionstack.conf")
            QuestModel.QUEST_3 -> listOf("$exploitpath/ionstack.conf", "$exploitpath/ionstack_q3.conf", "ionstack.conf", "ionstack_q3.conf")
            QuestModel.QUEST_1 -> listOf("$exploitpath/ionstack_q1.conf", "$exploitpath/ionstack.conf", "ionstack_q1.conf", "ionstack.conf")
            QuestModel.QUEST_PRO -> listOf("$exploitpath/ionstack_qp.conf", "$exploitpath/ionstack.conf", "ionstack_qp.conf", "ionstack.conf")
            else -> listOf("$exploitpath/ionstack.conf", "ionstack.conf")
        }
        for (asset in assetCandidates) {
            if (pushAssetToDevice(ctx, asset, remote)) {
                ctx.log("⚠️ Using asset $asset (may not match $incremental)")
                return true
            }
        }
        ctx.log("ℹ️ No ionstack.conf for $model / $incremental — continuing (QuestStack binary may still work)")
        return true
    }

    private fun runIonstackAttempts(ctx: Utils.ActionContext, maxAttempts: Int = 15): Boolean {
        val envBlock = ionstackenv.joinToString(" ")
        val cmd = "cd $tmppath && $envBlock $ionstackpath"
        for (attempt in 1..maxAttempts) {
            ctx.log("Root attempt $attempt/$maxAttempts…")
            ctx.toast("Root attempt $attempt/$maxAttempts")
            val output = try { ctx.run(cmd) } catch (e: Exception) {
                ctx.log("⚠️ Shell error: ${e.message}"); ""
            }
            val detected = success.any { p -> output.contains(p, ignoreCase = true) }
            if (detected) {
                ctx.log("Exploit reported success. Confirming ADB shell UID…")
                repeat(10) {
                    Thread.sleep(1_000)
                    if (checkRootAccess(ctx)) {
                        ctx.log("🎉 Confirmed: shell is root")
                        ctx.toast("Root successful!")
                        return true
                    }
                }
                ctx.log("⚠️ Exploit success string seen but shell not root — retrying…")
            } else {
                if (checkRootAccess(ctx)) {
                    ctx.log("🎉 Root confirmed via id (no banner)")
                    ctx.toast("Root successful!")
                    return true
                }
                ctx.log("⚠️ No success pattern this attempt. Retry in 5s…")
                Thread.sleep(5_000)
            }
        }
        ctx.log("❌ Failed after $maxAttempts attempts")
        return false
    }

    fun runIonStack(ctx: Utils.ActionContext, rebootFirst: Boolean = false) {
        if (isRunning) {
            ctx.log("⚠️ IonStack already running"); ctx.toast("Already running"); return
        }
        isRunning = true
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ctx.log("🔍 Starting QuestStack ionstack…"); ctx.toast("IonStack starting…")
                val serial = getDeviceSerials(ctx)
                if (serial.isEmpty()) {
                    ctx.log("❌ No device connected"); ctx.toast("No device connected"); return@launch
                }
                ctx.log("✅ Device: $serial")
                val model = detectQuestModel(ctx)
                val incremental = getDeviceIncremental(ctx)
                val unlocked = isBootloaderUnlocked(ctx)
                ctx.log("📱 Model: $model  incremental=$incremental  unlocked=$unlocked")
                if (model == QuestModel.UNKNOWN) ctx.log("⚠️ Unknown model — still attempting ionstack")
                if (incremental in patched && !unlocked) {
                    ctx.log("❌ Build patched and bootloader locked"); ctx.toast("Device is patched!"); return@launch
                }
                if (incremental in patched && unlocked) ctx.log("⚠️ Patched build but unlocked — attempting anyway")
                if (rebootFirst) {
                    ctx.log("🔄 Rebooting for higher success rate…")
                    ctx.runDefault("reboot"); ctx.run("wait-for-device"); delay(12_000)
                    ctx.log("✅ Device back online")
                }
                ensureIonstackConf(ctx)
                val local = prepareVerifiedIonstack(ctx)
                if (local == null) {
                    ctx.log("❌ Could not prepare ionstack binary"); ctx.toast("ionstack missing"); return@launch
                }
                if (!pushIonstack(ctx, local)) {
                    ctx.toast("ionstack push failed"); return@launch
                }
                stageCompanionPayloads(ctx, model)
                ctx.log("🚀 Running ionstack (QuestStack env, up to 15 attempts)…")
                val ok = runIonstackAttempts(ctx, maxAttempts = 15)
                if (ok) {
                    ctx.log("Shell: ${ctx.run("id")}")
                    ctx.log("SELinux: ${ctx.run("getenforce")}")
                } else {
                    ctx.log("⚠️ Root not confirmed — verify manually / try reboot + re-run")
                    ctx.toast("Root not confirmed")
                }
            } catch (e: Exception) {
                ctx.log("❌ IonStack failed: ${e.message}"); ctx.toast("IonStack failed"); e.printStackTrace()
            } finally {
                isRunning = false
            }
        }
    }

    fun runV79Root(ctx: Utils.ActionContext) {
        if (isRunning) {
            ctx.log("⚠️ Already running an exploit"); ctx.toast("Already running"); return
        }
        isRunning = true
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ctx.log("🔍 Starting V79 Legacy Root…"); ctx.toast("V79 root starting…")
                val serial = getDeviceSerials(ctx)
                if (serial.isEmpty()) {
                    ctx.log("❌ No device connected"); ctx.toast("No device connected"); return@launch
                }
                ctx.log("✅ Device found: $serial")
                val pushed = pushAssetsFromFolder(ctx, "OldRoot.Exploit", "/data/local/tmp/")
                if (!pushed) {
                    ctx.log("❌ Failed to push V79 exploit assets"); ctx.toast("V79 push failed"); return@launch
                }
                val exploitPaths = listOf(
                    "/data/local/tmp/exploit",
                    "/data/local/tmp/OldRoot.Exploit/exploit",
                    "/data/local/tmp/OldRootExploit/exploit"
                )
                var exploitRun = false
                for (path in exploitPaths) {
                    if (ctx.run("test -f $path && echo exists").contains("exists")) {
                        ctx.log("✅ Found exploit at: $path")
                        ctx.run("chmod +x $path"); ctx.run(path)
                        exploitRun = true; break
                    }
                }
                if (!exploitRun) ctx.log("❌ Could not find exploit binary on device")
                delay(1000)
                if (checkRootAccess(ctx)) {
                    ctx.log("🎉 Root access obtained!"); ctx.toast("Root successful!")
                } else {
                    ctx.log("⚠️ Root not detected — verify manually"); ctx.toast("Check if root was successful")
                }
            } catch (e: Exception) {
                ctx.log("❌ V79 root failed: ${e.message}"); ctx.toast("V79 root failed"); e.printStackTrace()
            } finally {
                isRunning = false
            }
        }
    }

    fun runBestRoot(ctx: Utils.ActionContext) {
        if (isRunning) {
            ctx.log("⚠️ Already running an exploit"); ctx.toast("Already running"); return
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val serial = getDeviceSerials(ctx)
                if (serial.isEmpty()) {
                    ctx.log("❌ No device connected"); ctx.toast("No device"); return@launch
                }
                val model = detectQuestModel(ctx)
                val incremental = getDeviceIncremental(ctx)
                val unlocked = isBootloaderUnlocked(ctx)
                ctx.log("📱 $model  incremental=$incremental  unlocked=$unlocked")
                val skipIon = incremental in patched && !unlocked
                if (!skipIon) {
                    ctx.log("🔍 Trying QuestStack ionstack (supports Q1/Q2/Q3/3S/Pro)…")
                    runIonStack(ctx, rebootFirst = false)
                    while (isRunning) delay(200)
                    if (checkRootAccess(ctx)) {
                        ctx.log("✅ IonStack succeeded!"); return@launch
                    }
                } else {
                    ctx.log("⏭ Skipping IonStack (patched + locked bootloader)")
                }
                ctx.log("🔄 Trying V79 legacy root…")
                runV79Root(ctx)
            } catch (e: Exception) {
                ctx.log("❌ All root attempts failed: ${e.message}"); ctx.toast("All root attempts failed")
            }
        }
    }

    fun Utils.ActionContext.checkRoot(): Boolean {
        val methods = listOf(
            { RootHelper.runRoot(this, "id 2>/dev/null").contains("uid=0") },
            { run("id").contains("uid=0") },
            { run("whoami").trim() == "root" },
            { run("test -f /system/bin/su && echo exists").contains("exists") }
        )
        return methods.any { it() }
    }
}