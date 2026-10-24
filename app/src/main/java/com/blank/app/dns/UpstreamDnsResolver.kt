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
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList

class UpstreamDnsResolver(private val vpnService: VpnService) {

    companion object {
        private const val TAG = "UpstreamDnsResolver"
        private const val DNS_PORT = 53
        private const val SOCKET_TIMEOUT_MS = 1500 // per-server timeout
        // VPN loopback addresses that must never be used as upstream DNS servers
        private val VPN_LOOPBACK_ADDRESSES = setOf("10.0.0.2", "fd00::2", "::1", "127.0.0.1")
    }

    private val connectivityManager =
        vpnService.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    @Volatile
    private var activePhysicalNetwork: Network? = null

    // Carrier/Wi-Fi DNS servers discovered from the active physical network
    private val cachedCarrierServers = CopyOnWriteArrayList<InetAddress>()

    // Public fallbacks used only when carrier DNS is unavailable
    private val publicFallbacks: List<InetAddress> by lazy {
        listOfNotNull(
            safeByAddress(byteArrayOf(1, 1, 1, 1)),   // Cloudflare
            safeByAddress(byteArrayOf(8, 8, 8, 8)),   // Google
            safeByAddress(byteArrayOf(9, 9, 9, 9)),   // Quad9
            safeByAddress(byteArrayOf(1, 0, 0, 1))    // Cloudflare secondary
        )
    }

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
            // Only process physical (non-VPN) networks that have confirmed internet
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                handleNetworkUpdate(network)
            }
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            // Guard against VPN network updates leaking in here
            val cm = connectivityManager ?: return
            val caps = cm.getNetworkCapabilities(network) ?: return
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                handleNetworkUpdate(network)
            }
        }

        override fun onLost(network: Network) {
            if (activePhysicalNetwork == network) {
                Log.w(TAG, "Active physical network lost — falling back to public DNS")
                activePhysicalNetwork = null
                cachedCarrierServers.clear()
            }
        }
    }

    init {
        registerNetworkCallback()
        // Eagerly seed DNS servers so first query doesn't wait for callback
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
            // Never use the VPN tunnel itself as a physical upstream
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return

            activePhysicalNetwork = network
            // Inform the VPN service which physical network carries its traffic
            // (helps battery accounting and routing on Android 8+)
            vpnService.setUnderlyingNetworks(arrayOf(network))

            val linkProps = cm.getLinkProperties(network)
            val newServers = mutableListOf<InetAddress>()
            linkProps?.dnsServers?.forEach { address ->
                val host = address.hostAddress ?: ""
                if (host !in VPN_LOOPBACK_ADDRESSES &&
                    !address.isLoopbackAddress &&
                    !address.isAnyLocalAddress) {
                    newServers.add(address)
                }
            }

            cachedCarrierServers.clear()
            cachedCarrierServers.addAll(newServers)
            Log.d(TAG, "Physical network updated. Carrier DNS: ${cachedCarrierServers.map { it.hostAddress }}")
        } catch (e: Exception) {
            Log.w(TAG, "Error handling network update: ${e.message}")
        }
    }

    /**
     * Eagerly discovers DNS servers from the active network before the callback fires.
     */
    private fun refreshDnsServers() {
        val cm = connectivityManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val activeNet = cm.activeNetwork ?: return
                val caps = cm.getNetworkCapabilities(activeNet) ?: return
                // Skip if this is the VPN interface itself
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return

                activePhysicalNetwork = activeNet
                vpnService.setUnderlyingNetworks(arrayOf(activeNet))

                val servers = mutableListOf<InetAddress>()
                cm.getLinkProperties(activeNet)?.dnsServers?.forEach { address ->
                    val host = address.hostAddress ?: ""
                    if (host !in VPN_LOOPBACK_ADDRESSES && !address.isLoopbackAddress) {
                        servers.add(address)
                    }
                }
                cachedCarrierServers.clear()
                cachedCarrierServers.addAll(servers)
                Log.d(TAG, "Initial carrier DNS: ${cachedCarrierServers.map { it.hostAddress }}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error in refreshDnsServers: ${e.message}")
        }
    }

    /**
     * Forwards a raw DNS query payload to upstream DNS servers over the physical network.
     *
     * Strategy:
     *  1. Try each carrier/Wi-Fi DNS server with its own socket (fast, avoids cross-server contamination).
     *  2. If all carrier servers fail, try each public fallback server.
     *  3. Return null only if every server and fallback times out.
     *
     * Each server gets a fresh socket so a stale or stuck send/receive on one server
     * cannot block forward progress to the next server.
     */
    fun forward(dnsPayload: ByteArray, offset: Int, length: Int): ByteArray? {
        val network = activePhysicalNetwork

        // Build the ordered server list: carrier first, then public fallbacks
        val servers = buildList {
            addAll(cachedCarrierServers)
            // Add public fallbacks that are not already in carrier list
            val carrierHosts = cachedCarrierServers.map { it.hostAddress }.toSet()
            publicFallbacks.forEach { fb ->
                if (fb.hostAddress !in carrierHosts) add(fb)
            }
        }

        if (servers.isEmpty()) {
            Log.w(TAG, "No DNS servers available — using public fallbacks only")
            return tryServers(publicFallbacks, network, dnsPayload, offset, length)
        }

        return tryServers(servers, network, dnsPayload, offset, length)
    }

    /**
     * Tries each server in order, using a fresh socket per server to prevent cross-contamination.
     */
    private fun tryServers(
        servers: List<InetAddress>,
        network: Network?,
        dnsPayload: ByteArray,
        offset: Int,
        length: Int
    ): ByteArray? {
        for (server in servers) {
            val result = trySingleServer(server, network, dnsPayload, offset, length)
            if (result != null) return result
        }
        return null
    }

    private fun trySingleServer(
        server: InetAddress,
        network: Network?,
        dnsPayload: ByteArray,
        offset: Int,
        length: Int
    ): ByteArray? {
        var socket: DatagramSocket? = null
        return try {
            socket = DatagramSocket()
            // Bind to the physical network to bypass VPN routing entirely.
            // Fall back to vpnService.protect() if no network reference available.
            if (network != null) {
                network.bindSocket(socket)
            } else {
                vpnService.protect(socket)
            }
            socket.soTimeout = SOCKET_TIMEOUT_MS

            val sendPacket = DatagramPacket(dnsPayload, offset, length, server, DNS_PORT)
            socket.send(sendPacket)

            val receiveBuffer = ByteArray(4096)
            val receivePacket = DatagramPacket(receiveBuffer, receiveBuffer.size)
            socket.receive(receivePacket)

            receiveBuffer.copyOf(receivePacket.length)
        } catch (e: SocketTimeoutException) {
            Log.d(TAG, "DNS timeout from ${server.hostAddress}")
            null
        } catch (e: Exception) {
            Log.d(TAG, "DNS error from ${server.hostAddress}: ${e.message}")
            null
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    /**
     * Unregisters the network callback. Must be called when the VPN is stopped.
     */
    fun release() {
        try {
            connectivityManager?.unregisterNetworkCallback(networkCallback)
            Log.d(TAG, "Network callback unregistered")
        } catch (e: Exception) {
            // Already unregistered or never registered — safe to ignore
        }
        activePhysicalNetwork = null
        cachedCarrierServers.clear()
    }
}
