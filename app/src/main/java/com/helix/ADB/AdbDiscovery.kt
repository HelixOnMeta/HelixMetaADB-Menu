package com.helix

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.coroutines.resume

// contact blaku64th on discord if you have any issues ^^
object AdbDiscovery {
    // find pairing data
    const val pairingtype = "_adb-tls-pairing._tcp."
    const val connecttype = "_adb-tls-connect._tcp."

    const val discoveryms = 8000L

    data class Endpoint(
        val host: String,
        val port: Int
    )

    suspend fun discoverPairingEndpoint(context: Context): Endpoint? {
        return discoverService(
            context = context,
            serviceType = pairingtype,
            verifySocket = false
        )
    }

    suspend fun discoverConnectEndpoint(context: Context): Endpoint? {
        return discoverService(
            context = context,
            serviceType = connecttype,
            verifySocket = true
        )
    }

    private suspend fun discoverService(
        context: Context,
        serviceType: String,
        verifySocket: Boolean
    ): Endpoint? {

        val nsdManager =
            context.getSystemService(Context.NSD_SERVICE) as? NsdManager
                ?: return null

        val wifiManager =
            context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager

        val multicastLock = wifiManager
            ?.createMulticastLock("adbconsole_mdns")
            ?.apply {
                setReferenceCounted(true)
                acquire()
            }

        return try {

            withTimeoutOrNull(discoveryms) {

                suspendCancellableCoroutine { continuation ->

                    var finished = false

                    lateinit var discoveryListener: NsdManager.DiscoveryListener

                    fun finish(endpoint: Endpoint?) {
                        if (finished) return

                        finished = true

                        runCatching {
                            nsdManager.stopServiceDiscovery(discoveryListener)
                        }

                        if (continuation.isActive) {
                            continuation.resume(endpoint)
                        }
                    }

                    val resolveListener =
                        object : NsdManager.ResolveListener {

                            override fun onResolveFailed(
                                serviceInfo: NsdServiceInfo?,
                                errorCode: Int
                            ) {
                            }

                            override fun onServiceResolved(
                                serviceInfo: NsdServiceInfo
                            ) {

                                val host =
                                    serviceInfo.host?.hostAddress
                                        ?: return

                                val port = serviceInfo.port

                                if (port !in 1..65535) return

                                if (!verifySocket) {
                                    finish(
                                        Endpoint(
                                            host = host,
                                            port = port
                                        )
                                    )
                                    return
                                }

                                CoroutineScope(Dispatchers.IO).launch {

                                    val alive = runCatching {

                                        Socket().use { socket ->

                                            socket.connect(
                                                InetSocketAddress(
                                                    host,
                                                    port
                                                ),
                                                500
                                            )

                                            true
                                        }

                                    }.getOrDefault(false)

                                    if (alive) {
                                        finish(
                                            Endpoint(
                                                host = host,
                                                port = port
                                            )
                                        )
                                    }
                                }
                            }
                        }

                    discoveryListener =
                        object : NsdManager.DiscoveryListener {

                            override fun onStartDiscoveryFailed(
                                serviceType: String?,
                                errorCode: Int
                            ) {
                                finish(null)
                            }

                            override fun onStopDiscoveryFailed(
                                serviceType: String?,
                                errorCode: Int
                            ) {
                            }

                            override fun onDiscoveryStarted(
                                serviceType: String?
                            ) {
                            }

                            override fun onDiscoveryStopped(
                                serviceType: String?
                            ) {
                            }

                            override fun onServiceFound(
                                serviceInfo: NsdServiceInfo
                            ) {

                                if (
                                    serviceInfo.serviceType
                                        ?.equals(
                                            serviceType,
                                            ignoreCase = true
                                        ) == true
                                ) {
                                    runCatching {
                                        nsdManager.resolveService(
                                            serviceInfo,
                                            resolveListener
                                        )
                                    }
                                }
                            }

                            override fun onServiceLost(
                                serviceInfo: NsdServiceInfo
                            ) {
                            }
                        }

                    continuation.invokeOnCancellation {
                        runCatching {
                            nsdManager.stopServiceDiscovery(
                                discoveryListener
                            )
                        }
                    }

                    runCatching {

                        nsdManager.discoverServices(
                            serviceType,
                            NsdManager.PROTOCOL_DNS_SD,
                            discoveryListener
                        )

                    }.onFailure {
                        finish(null)
                    }

                    Handler(Looper.getMainLooper()).postDelayed(
                        {
                            finish(null)
                        },
                        discoveryms
                    )
                }
            }

        } finally {

            runCatching {
                multicastLock?.release()
            }
        }
    }
}