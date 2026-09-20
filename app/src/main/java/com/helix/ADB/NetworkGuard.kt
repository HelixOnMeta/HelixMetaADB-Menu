package com.helix

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// contact blaku64th on discord if you have any issues ^^
object NetworkGuard {
    private const val TAG = "NetworkGuard"

    @Volatile var firewallOn = false
        private set
    @Volatile var interceptOn = false
        private set

    private var maintainJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val blockDomains = mutableSetOf(
        "graph.oculus.com",
        "graph.facebook.com",
        "portal.fb.com",
        "odp.oculus.com",
        "updater.oculus.com",
        "securecdn.oculus.com"
    )

    private val httpRewrites = mutableListOf<Triple<String, String, String>>()

    fun addBlockDomain(domain: String) {
        blockDomains += domain.trim().lowercase().removePrefix(".")
    }

    fun removeBlockDomain(domain: String) {
        blockDomains -= domain.trim().lowercase().removePrefix(".")
    }

    fun addHttpRewrite(host: String, match: String, replace: String) {
        httpRewrites += Triple(host.lowercase(), match, replace)
    }

    fun clearHttpRewrites() {
        httpRewrites.clear()
    }

    fun status(): String = buildString {
        appendLine("firewall=${if (firewallOn) "ON" else "OFF"}")
        appendLine("dns_blocker=${if (DnsBlockerService.active) "ON" else "OFF"}")
        appendLine("intercept=${if (interceptOn) "ON" else "OFF"} (cleartext only)")
        appendLine("block_domains=${blockDomains.size}")
        appendLine("http_rewrites=${httpRewrites.size}")
    }

    fun enableFirewall(ctx: Utils.ActionContext) {
        if (!RootHelper.hasRoot(ctx)) {
            ctx.log("Root required for iptables firewall — falling back to DNS blocker")
            DnsBlockerService.start(AppContext.app)
            firewallOn = true
            ctx.toast("DNS blocker ON (no root firewall)")
            return
        }
        val script = buildString {
            appendLine("#!/system/bin/sh")
            appendLine("iptables -N HELIX_BLOCK 2>/dev/null || iptables -F HELIX_BLOCK")
            appendLine("iptables -C OUTPUT -j HELIX_BLOCK 2>/dev/null || iptables -I OUTPUT -j HELIX_BLOCK")
            for (d in blockDomains) {
                appendLine("# block marker $d")
            }
            appendLine("# hosts file blackhole")
            appendLine("mkdir -p /data/adb/modules/helix_hosts/system/etc")
            appendLine("cat > /data/adb/modules/helix_hosts/system/etc/hosts << 'EOF'")
            appendLine("127.0.0.1 localhost")
            appendLine("::1 localhost")
            for (d in blockDomains) {
                appendLine("127.0.0.1 $d")
                appendLine("::1 $d")
            }
            appendLine("EOF")
            appendLine("touch /data/adb/modules/helix_hosts/auto_mount")
            appendLine("iptables -A HELIX_BLOCK -p udp --dport 53 -m string --string \"oculus\" --algo bm -j REJECT 2>/dev/null || true")
        }
        RootHelper.writeScript(ctx, "helix_net_guard.sh", script)
        RootHelper.runRoot(ctx, "sh /data/local/tmp/helix_net_guard.sh")
        DnsBlockerService.start(AppContext.app)
        firewallOn = true
        startMaintain(ctx)
        ctx.log("Network firewall + hosts + DNS blocker ON")
        ctx.toast("Network guard ON")
    }

    fun disableFirewall(ctx: Utils.ActionContext) {
        maintainJob?.cancel()
        maintainJob = null
        if (RootHelper.hasRoot(ctx)) {
            RootHelper.runRoot(
                ctx,
                "iptables -D OUTPUT -j HELIX_BLOCK 2>/dev/null; iptables -F HELIX_BLOCK 2>/dev/null; iptables -X HELIX_BLOCK 2>/dev/null; " +
                    "rm -rf /data/adb/modules/helix_hosts 2>/dev/null"
            )
        }
        DnsBlockerService.stop(AppContext.app)
        firewallOn = false
        ctx.log("Network guard OFF")
        ctx.toast("Network guard OFF")
    }

    fun enableIntercept(ctx: Utils.ActionContext) {
        interceptOn = true
        RootHelper.runRoot(
            ctx,
            "settings put global http_proxy 127.0.0.1:8888 2>/dev/null || true"
        )
        ctx.log(
            "Intercept flag ON. Cleartext rewrites=${httpRewrites.size}. " +
                "HTTPS MITM requires installing a user CA + external proxy (not bundled)."
        )
        ctx.toast("Intercept ON (cleartext/proxy hint)")
    }

    fun disableIntercept(ctx: Utils.ActionContext) {
        interceptOn = false
        RootHelper.runRoot(ctx, "settings put global http_proxy :0 2>/dev/null || settings delete global http_proxy 2>/dev/null || true")
        ctx.log("Intercept OFF")
        ctx.toast("Intercept OFF")
    }

    fun registerEditRule(host: String, match: String, replace: String, ctx: Utils.ActionContext) {
        addHttpRewrite(host, match, replace)
        ctx.log("Rewrite rule: host=$host match='$match' → '$replace'")
        ctx.toast("Rule added")
    }

    private fun startMaintain(ctx: Utils.ActionContext) {
        maintainJob?.cancel()
        maintainJob = scope.launch {
            while (isActive && firewallOn) {
                try {
                    if (!DnsBlockerService.active) {
                        DnsBlockerService.start(AppContext.app)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "maintain: ${e.message}")
                }
                delay(15_000)
            }
        }
    }
}
