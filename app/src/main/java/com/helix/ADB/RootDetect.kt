package com.helix

import android.os.Build
import java.io.File

// contact blaku64th on discord if you have any issues ^^
object RootDetect {

    data class Finding(val id: String, val detected: Boolean, val detail: String)

    data class Report(
        val findings: List<Finding>,
        val score: Int,
        val summary: String
    ) {
        val likelyDetected: Boolean get() = score >= 2
    }

    fun run(ctx: Utils.ActionContext? = null): Report {
        val findings = mutableListOf<Finding>()

        findings += checkSuBinary()
        findings += checkMagiskPaths()
        findings += checkBusybox()
        findings += checkWhichSu()
        findings += checkBuildTags()
        findings += checkDangerousProps()
        findings += checkRootPackages()
        findings += checkRwSystem()
        findings += checkSelinux()

        if (ctx != null) {
            findings += checkSuViaShell(ctx)
        }

        val score = findings.count { it.detected }
        val summary = when {
            score == 0 -> "No common root signals (or well hidden)"
            score <= 2 -> "Weak signals ($score) — may still pass casual checks"
            score <= 5 -> "Moderate signals ($score) — many apps will flag root"
            else -> "Strong signals ($score) — root is obvious"
        }
        return Report(findings, score, summary)
    }

    fun format(report: Report): String = buildString {
        appendLine("── Root detection report ──")
        appendLine(report.summary)
        appendLine()
        report.findings.forEach { f ->
            val mark = if (f.detected) "HIT " else "ok  "
            appendLine("[$mark] ${f.id}: ${f.detail}")
        }
    }

    private fun checkSuBinary(): Finding {
        val paths = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su",
            "/su/bin/su", "/data/local/su", "/data/local/bin/su",
            "/data/local/xbin/su", "/system/sd/xbin/su",
            "/system/bin/failsafe/su", "/data/local/tmp/su"
        )
        val hit = paths.filter { File(it).exists() }
        return Finding("su_binary", hit.isNotEmpty(), if (hit.isEmpty()) "none" else hit.joinToString())
    }

    private fun checkMagiskPaths(): Finding {
        val paths = listOf(
            "/sbin/.magisk", "/data/adb/magisk", "/data/adb/modules",
            "/cache/.disable_magisk", "/dev/.magisk.unblock",
            "/data/adb/magisk.db", "/data/adb/ksu", "/data/adb/ap"
        )
        val hit = paths.filter { File(it).exists() }
        return Finding("magisk_paths", hit.isNotEmpty(), if (hit.isEmpty()) "none" else hit.joinToString())
    }

    private fun checkBusybox(): Finding {
        val paths = listOf("/system/xbin/busybox", "/system/bin/busybox", "/data/local/tmp/busybox")
        val hit = paths.any { File(it).exists() }
        return Finding("busybox", hit, if (hit) "present" else "absent")
    }

    private fun checkWhichSu(): Finding {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("which", "su"))
            val out = p.inputStream.bufferedReader().readText().trim()
            p.waitFor()
            Finding("which_su", out.isNotBlank(), out.ifBlank { "not in PATH" })
        } catch (e: Exception) {
            Finding("which_su", false, "error: ${e.message}")
        }
    }

    private fun checkBuildTags(): Finding {
        val tags = Build.TAGS ?: ""
        val testKeys = tags.contains("test-keys", ignoreCase = true)
        return Finding("build_tags", testKeys, tags.ifBlank { "?" })
    }

    private fun checkDangerousProps(): Finding {
        val props = listOf(
            "ro.debuggable", "ro.secure", "service.adb.root"
        )
        val bad = mutableListOf<String>()
        for (prop in props) {
            try {
                val p = Runtime.getRuntime().exec(arrayOf("getprop", prop))
                val v = p.inputStream.bufferedReader().readText().trim()
                p.waitFor()
                when (prop) {
                    "ro.debuggable" -> if (v == "1") bad += "$prop=$v"
                    "ro.secure" -> if (v == "0") bad += "$prop=$v"
                    "service.adb.root" -> if (v == "1") bad += "$prop=$v"
                }
            } catch (_: Exception) {
            }
        }
        return Finding("dangerous_props", bad.isNotEmpty(), bad.joinToString().ifBlank { "clean" })
    }

    private fun checkRootPackages(): Finding {
        val pkgs = listOf(
            "com.topjohnwu.magisk",
            "me.weishu.kernelsu",
            "me.bmax.apatch",
            "eu.chainfire.supersu",
            "com.noshufou.android.su",
            "com.koushikdutta.superuser",
            "com.thirdparty.superuser",
            "com.yellowes.su"
        )
        val pm = AppContext.app.packageManager
        val hit = pkgs.filter {
            try {
                pm.getPackageInfo(it, 0)
                true
            } catch (_: Exception) {
                false
            }
        }
        return Finding("root_packages", hit.isNotEmpty(), hit.joinToString().ifBlank { "none installed" })
    }

    private fun checkRwSystem(): Finding {
        return try {
            val mounts = File("/proc/mounts").readText()
            val rw = mounts.lines().any {
                (it.contains(" /system ") || it.contains(" /system_root ")) && it.contains(" rw,")
            }
            Finding("rw_system", rw, if (rw) "/system mounted rw" else "ro")
        } catch (e: Exception) {
            Finding("rw_system", false, "unreadable")
        }
    }

    private fun checkSelinux(): Finding {
        return try {
            val enforce = File("/sys/fs/selinux/enforce").takeIf { it.canRead() }?.readText()?.trim()
            val permissive = enforce == "0"
            Finding("selinux", permissive, when (enforce) {
                "0" -> "Permissive"
                "1" -> "Enforcing"
                else -> "unknown"
            })
        } catch (_: Exception) {
            Finding("selinux", false, "unknown")
        }
    }

    private fun checkSuViaShell(ctx: Utils.ActionContext): Finding {
        val out = ctx.run("su -c id 2>/dev/null")
        val ok = out.contains("uid=0")
        return Finding("su_exec", ok, if (ok) "uid=0" else "denied/unavailable")
    }
}
