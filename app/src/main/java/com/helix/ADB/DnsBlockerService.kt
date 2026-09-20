package com.helix

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

// contact blaku64th on discord if you have any issues ^^
class DnsBlockerService : VpnService() {

    companion object {
        private const val TAG = "DnsBlocker"
        const val CHANNEL = "helix_dns_blocker"
        const val NOTIF_ID = 88
        const val ACTION_STOP = "com.helix.dns.STOP"

        @Volatile
        var instance: DnsBlockerService? = null
            private set

        @Volatile
        var active: Boolean = false
            private set

        val DEFAULT_BLOCKS = listOf(
            "graph.oculus.com",
            "graph.facebook.com",
            "portal.fb.com",
            "facebook.com",
            "fbcdn.net",
            "oculus.com",
            "meta.com",
            "cdn.oculus.com",
            "securecdn.oculus.com",
            "updater.oculus.com",
            "odp.oculus.com"
        )

        private val extraBlocks = mutableSetOf<String>()

        fun addBlock(domain: String) {
            extraBlocks += domain.trim().lowercase().removePrefix(".")
        }

        fun removeBlock(domain: String) {
            extraBlocks -= domain.trim().lowercase().removePrefix(".")
        }

        fun allBlocks(): Set<String> = (DEFAULT_BLOCKS + extraBlocks).toSet()

        fun prepareIntent(context: Context): Intent? = prepare(context)

        fun start(context: Context) {
            val i = Intent(context, DnsBlockerService::class.java)
            try {
                context.startForegroundService(i)
            } catch (_: Exception) {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, DnsBlockerService::class.java).setAction(ACTION_STOP))
        }
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private val running = AtomicBoolean(false)
    private var loopThread: Thread? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(
            NotificationChannel(CHANNEL, "DNS Blocker", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        startForeground(NOTIF_ID, notif("DNS blocker running"))
        if (!running.get()) startVpn()
        return START_STICKY
    }

    override fun onDestroy() {
        shutdown()
        instance = null
        super.onDestroy()
    }

    private fun shutdown() {
        running.set(false)
        active = false
        try {
            vpnInterface?.close()
        } catch (_: Exception) {
        }
        vpnInterface = null
        loopThread = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startVpn() {
        try {
            val builder = Builder()
                .setSession("Helix DNS Blocker")
                .addAddress("10.31.0.1", 32)
                .addDnsServer("10.31.0.1")
                .addRoute("10.31.0.1", 32)
                .setMtu(1500)
                .setBlocking(true)

            try {
                builder.addDisallowedApplication(packageName)
            } catch (_: Exception) {
            }

            vpnInterface = builder.establish()
            if (vpnInterface == null) {
                Log.e(TAG, "VPN establish returned null — permission missing?")
                stopSelf()
                return
            }
            running.set(true)
            active = true
            loopThread = thread(name = "helix-dns-loop", isDaemon = true) {
                packetLoop(vpnInterface!!)
            }
            Log.i(TAG, "DNS blocker VPN up")
        } catch (e: Exception) {
            Log.e(TAG, "startVpn failed", e)
            shutdown()
        }
    }

    /**
     * Extremely small DNS responder:
     * - Reads IP packets from the TUN
     * - If UDP/53 query for a blocked name → write NXDOMAIN-ish reply (best effort)
     * - Otherwise drop (VPN only owns the DNS address)
     */
    private fun packetLoop(pfd: ParcelFileDescriptor) {
        val input = FileInputStream(pfd.fileDescriptor)
        val output = FileOutputStream(pfd.fileDescriptor)
        val buf = ByteArray(32767)
        while (running.get()) {
            try {
                val n = input.read(buf)
                if (n <= 0) continue
                if (n < 28) continue
                val version = (buf[0].toInt() shr 4) and 0xF
                if (version != 4) continue
                val ihl = (buf[0].toInt() and 0xF) * 4
                val protocol = buf[9].toInt() and 0xFF
                if (protocol != 17) continue
                if (n < ihl + 8) continue
                val destPort = ((buf[ihl + 2].toInt() and 0xFF) shl 8) or (buf[ihl + 3].toInt() and 0xFF)
                if (destPort != 53) continue
                val dnsOff = ihl + 8
                if (n < dnsOff + 12) continue
                val qname = parseQName(buf, dnsOff + 12, n) ?: continue
                if (isBlocked(qname)) {
                    Log.d(TAG, "block DNS $qname")
                    val reply = buildNxDomain(buf, n, ihl)
                    if (reply != null) {
                        try {
                            output.write(reply)
                        } catch (_: Exception) {
                        }
                    }
                }
            } catch (e: Exception) {
                if (running.get()) Log.w(TAG, "loop: ${e.message}")
                break
            }
        }
    }

    private fun isBlocked(qname: String): Boolean {
        val host = qname.trimEnd('.').lowercase()
        return allBlocks().any { block ->
            host == block || host.endsWith(".$block")
        }
    }

    private fun parseQName(pkt: ByteArray, start: Int, end: Int): String? {
        var i = start
        val parts = mutableListOf<String>()
        while (i < end) {
            val len = pkt[i].toInt() and 0xFF
            if (len == 0) break
            if (len and 0xC0 != 0) break
            if (i + 1 + len > end) return null
            parts += String(pkt, i + 1, len, Charsets.US_ASCII)
            i += 1 + len
        }
        return if (parts.isEmpty()) null else parts.joinToString(".")
    }

    private fun buildNxDomain(req: ByteArray, length: Int, ihl: Int): ByteArray? {
        if (length < ihl + 8 + 12) return null
        val out = req.copyOf(length)
        for (k in 0 until 4) {
            val a = out[12 + k]
            out[12 + k] = out[16 + k]
            out[16 + k] = a
        }
        val sp0 = out[ihl]; val sp1 = out[ihl + 1]
        out[ihl] = out[ihl + 2]
        out[ihl + 1] = out[ihl + 3]
        out[ihl + 2] = sp0
        out[ihl + 3] = sp1
        val dns = ihl + 8
        out[dns + 2] = (0x80 or (out[dns + 2].toInt() and 0x01)).toByte()
        out[dns + 3] = 0x03
        out[dns + 6] = 0; out[dns + 7] = 0
        out[dns + 8] = 0; out[dns + 9] = 0
        out[dns + 10] = 0; out[dns + 11] = 0
        val udpLen = length - ihl
        out[ihl + 4] = ((udpLen shr 8) and 0xFF).toByte()
        out[ihl + 5] = (udpLen and 0xFF).toByte()
        out[2] = ((length shr 8) and 0xFF).toByte()
        out[3] = (length and 0xFF).toByte()
        out[10] = 0; out[11] = 0
        out[ihl + 6] = 0; out[ihl + 7] = 0
        return out
    }

    private fun notif(text: String): Notification =
        Notification.Builder(this, CHANNEL)
            .setContentTitle("Helix DNS Blocker")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .build()
}
