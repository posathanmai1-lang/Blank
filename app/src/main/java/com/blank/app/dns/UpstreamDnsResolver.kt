package com.blank.app.dns

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class UpstreamDnsResolver(private val vpnService: VpnService) {

    companion object {
        private const val TAG = "UpstreamDnsResolver"
        private const val DNS_PORT = 53
        private const val PRIMARY_TIMEOUT_MS = 600
        private const val FALLBACK_TIMEOUT_MS = 800
        private val VPN_LOOPBACK_ADDRESSES = setOf("10.0.0.2", "127.0.0.1", "::1", "fd00::2")
    }

    private val connectivityManager =
        vpnService.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    @Volatile
    var activePhysicalNetwork: Network? = null
        private set

    // Discovered IPv4 carrier/Wi-Fi DNS servers
    private val cachedCarrierServers = CopyOnWriteArrayList<InetAddress>()

    // Global Anycast high-performance DNS resolvers
    private val publicPrimary: List<InetAddress> by lazy {
        listOfNotNull(
            safeByAddress(byteArrayOf(1, 1, 1, 1)),   // Cloudflare Primary
            safeByAddress(byteArrayOf(8, 8, 8, 8))    // Google Primary
        )
    }

    private val publicSecondary: List<InetAddress> by lazy {
        listOfNotNull(
            safeByAddress(byteArrayOf(1, 0, 0, 1)),   // Cloudflare Secondary
            safeByAddress(byteArrayOf(8, 8, 4, 4)),   // Google Secondary
            safeByAddress(byteArrayOf(9, 9, 9, 9))    // Quad9
        )
    }

    private val upstreamExecutor = Executors.newFixedThreadPool(6)

    private fun safeByAddress(bytes: ByteArray): InetAddress? = try {
        InetAddress.getByAddress(bytes)
    } catch (e: Exception) {
        null
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            handleNetworkUpdate(network)
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                handleNetworkUpdate(network)
            }
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            val cm = connectivityManager ?: return
            val caps = cm.getNetworkCapabilities(network) ?: return
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                handleNetworkUpdate(network)
            }
        }

        override fun onLost(network: Network) {
            if (activePhysicalNetwork == network) {
                Log.w(TAG, "Active physical network lost — resetting carrier DNS")
                activePhysicalNetwork = null
                cachedCarrierServers.clear()
            }
        }
    }

    init {
        registerNetworkCallback()
        refreshDnsServers()
    }

    private fun registerNetworkCallback() {
        try {
            val cm = connectivityManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                cm.registerDefaultNetworkCallback(networkCallback)
                Log.d(TAG, "Registered default network callback")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register network callback: ${e.message}")
        }
    }

    private fun handleNetworkUpdate(network: Network) {
        val cm = connectivityManager ?: return
        try {
            val caps = cm.getNetworkCapabilities(network) ?: return
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return

            activePhysicalNetwork = network
            vpnService.setUnderlyingNetworks(arrayOf(network))

            val linkProps = cm.getLinkProperties(network)
            val newServers = mutableListOf<InetAddress>()
            linkProps?.dnsServers?.forEach { address ->
                // Filter strictly to IPv4 addresses to avoid IPv6 timeout delays
                if (address is Inet4Address) {
                    val host = address.hostAddress ?: ""
                    if (host !in VPN_LOOPBACK_ADDRESSES &&
                        !address.isLoopbackAddress &&
                        !address.isAnyLocalAddress) {
                        newServers.add(address)
                    }
                }
            }

            cachedCarrierServers.clear()
            cachedCarrierServers.addAll(newServers)
            Log.d(TAG, "Physical network updated. Carrier IPv4 DNS: ${cachedCarrierServers.map { it.hostAddress }}")
        } catch (e: Exception) {
            Log.w(TAG, "Error handling network update: ${e.message}")
        }
    }

    private fun refreshDnsServers() {
        val cm = connectivityManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val activeNet = cm.activeNetwork ?: return
                val caps = cm.getNetworkCapabilities(activeNet) ?: return
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return

                activePhysicalNetwork = activeNet
                vpnService.setUnderlyingNetworks(arrayOf(activeNet))

                val servers = mutableListOf<InetAddress>()
                cm.getLinkProperties(activeNet)?.dnsServers?.forEach { address ->
                    if (address is Inet4Address) {
                        val host = address.hostAddress ?: ""
                        if (host !in VPN_LOOPBACK_ADDRESSES && !address.isLoopbackAddress) {
                            servers.add(address)
                        }
                    }
                }
                cachedCarrierServers.clear()
                cachedCarrierServers.addAll(servers)
                Log.d(TAG, "Initial carrier IPv4 DNS: ${cachedCarrierServers.map { it.hostAddress }}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error in refreshDnsServers: ${e.message}")
        }
    }

    /**
     * Forwards a raw DNS query payload concurrently to fast upstream DNS servers.
     * Uses Cloudflare (1.1.1.1) and Google (8.8.8.8) in parallel (racing for fastest response),
     * with instant fallback to secondary and carrier IPv4 DNS.
     */
    fun forward(dnsPayload: ByteArray, offset: Int, length: Int): ByteArray? {
        val network = activePhysicalNetwork

        // 1. Race primary ultra-fast servers (1.1.1.1 + 8.8.8.8) in parallel with 700ms timeout
        val primaryResult = raceServers(publicPrimary, network, dnsPayload, offset, length, PRIMARY_TIMEOUT_MS)
        if (primaryResult != null) {
            return primaryResult
        }

        // 2. If primary timed out, race secondary (1.0.0.1, 8.8.4.4, 9.9.9.9) + carrier DNS
        val fallbacks = buildList {
            addAll(publicSecondary)
            val addedHosts = map { it.hostAddress }.toSet()
            cachedCarrierServers.forEach { carrier ->
                if (carrier.hostAddress !in addedHosts) add(carrier)
            }
        }

        return raceServers(fallbacks, network, dnsPayload, offset, length, FALLBACK_TIMEOUT_MS)
    }

    private fun raceServers(
        servers: List<InetAddress>,
        network: Network?,
        dnsPayload: ByteArray,
        offset: Int,
        length: Int,
        timeoutMs: Int
    ): ByteArray? {
        if (servers.isEmpty()) return null

        val payloadCopy = dnsPayload.copyOfRange(offset, offset + length)
        val completionService = ExecutorCompletionService<ByteArray?>(upstreamExecutor)

        for (server in servers) {
            completionService.submit(Callable {
                trySingleServer(server, network, payloadCopy, 0, payloadCopy.size, timeoutMs)
            })
        }

        val startTime = System.currentTimeMillis()
        for (i in servers.indices) {
            val remaining = timeoutMs - (System.currentTimeMillis() - startTime)
            if (remaining <= 0) break
            try {
                val future = completionService.poll(remaining, TimeUnit.MILLISECONDS)
                if (future != null) {
                    val res = future.get()
                    if (res != null && res.isNotEmpty()) {
                        return res
                    }
                }
            } catch (e: Exception) {
                // Try next completed future
            }
        }

        return null
    }

    private fun trySingleServer(
        server: InetAddress,
        network: Network?,
        dnsPayload: ByteArray,
        offset: Int,
        length: Int,
        timeoutMs: Int
    ): ByteArray? {
        var socket: DatagramSocket? = null
        return try {
            socket = DatagramSocket()
            // CRITICAL: Always protect the socket so its packets bypass VPN routing
            vpnService.protect(socket)

            if (network != null) {
                try {
                    network.bindSocket(socket)
                } catch (e: Exception) {
                    // Ignore bind error — vpnService.protect() already handles bypass
                }
            }
            socket.soTimeout = timeoutMs

            val sendPacket = DatagramPacket(dnsPayload, offset, length, server, DNS_PORT)
            socket.send(sendPacket)

            val receiveBuffer = ByteArray(4096)
            val receivePacket = DatagramPacket(receiveBuffer, receiveBuffer.size)
            socket.receive(receivePacket)

            if (receivePacket.length > 0) {
                receiveBuffer.copyOf(receivePacket.length)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    fun release() {
        try {
            connectivityManager?.unregisterNetworkCallback(networkCallback)
            Log.d(TAG, "Network callback unregistered")
        } catch (e: Exception) {
            // Already unregistered
        }
        try {
            upstreamExecutor.shutdownNow()
        } catch (e: Exception) {
            // Ignore
        }
        activePhysicalNetwork = null
        cachedCarrierServers.clear()
    }
}
