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
        private const val SOCKET_TIMEOUT_MS = 800 // 800ms per server for fast resolution
    }

    private val connectivityManager =
        vpnService.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    @Volatile
    private var activePhysicalNetwork: Network? = null

    private val cachedDnsServers = CopyOnWriteArrayList<InetAddress>()

    private val publicFallbacks: List<InetAddress> by lazy {
        listOfNotNull(
            try { InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1)) } catch (e: Exception) { null },
            try { InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8)) } catch (e: Exception) { null },
            try { InetAddress.getByAddress(byteArrayOf(9, 9, 9, 9)) } catch (e: Exception) { null },
            try { InetAddress.getByAddress(byteArrayOf(1, 0, 0, 1)) } catch (e: Exception) { null }
        )
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
            handleNetworkUpdate(network)
        }

        override fun onLost(network: Network) {
            if (activePhysicalNetwork == network) {
                activePhysicalNetwork = null
                refreshDnsServers()
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
                val host = address.hostAddress ?: ""
                if (host != "10.0.0.2" && !address.isLoopbackAddress && !address.isAnyLocalAddress) {
                    newServers.add(address)
                }
            }
            newServers.addAll(publicFallbacks)

            cachedDnsServers.clear()
            cachedDnsServers.addAll(newServers.distinct())
            Log.d(TAG, "Active physical network updated: ${network}, DNS: ${cachedDnsServers.map { it.hostAddress }}")
        } catch (e: Exception) {
            Log.w(TAG, "Error processing network update: ${e.message}")
        }
    }

    /**
     * Fallback discovery in case callback hasn't fired yet
     */
    private fun refreshDnsServers() {
        val cm = connectivityManager ?: return
        val servers = mutableListOf<InetAddress>()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val activeNet = cm.activeNetwork
                if (activeNet != null) {
                    val caps = cm.getNetworkCapabilities(activeNet)
                    if (caps != null && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                        activePhysicalNetwork = activeNet
                        vpnService.setUnderlyingNetworks(arrayOf(activeNet))
                        cm.getLinkProperties(activeNet)?.dnsServers?.forEach { address ->
                            if (address.hostAddress != "10.0.0.2" && !address.isLoopbackAddress) {
                                servers.add(address)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error in refreshDnsServers: ${e.message}")
        }

        servers.addAll(publicFallbacks)
        cachedDnsServers.clear()
        cachedDnsServers.addAll(servers.distinct())
    }

    /**
     * Forwards an allowed DNS query payload to upstream DNS servers.
     * Binds the socket to the underlying physical network and tries carrier DNS first, then public fallbacks.
     */
    fun forward(dnsPayload: ByteArray, offset: Int, length: Int): ByteArray? {
        val servers = if (cachedDnsServers.isNotEmpty()) cachedDnsServers else publicFallbacks
        val network = activePhysicalNetwork

        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            
            // Explicitly bind socket to the physical network so it bypasses VPN routing entirely
            if (network != null) {
                network.bindSocket(socket)
            } else {
                vpnService.protect(socket)
            }

            socket.soTimeout = SOCKET_TIMEOUT_MS

            val receiveBuffer = ByteArray(4096)
            val receivePacket = DatagramPacket(receiveBuffer, receiveBuffer.size)

            for (server in servers) {
                try {
                    val sendPacket = DatagramPacket(dnsPayload, offset, length, server, DNS_PORT)
                    socket.send(sendPacket)
                    socket.receive(receivePacket)

                    return receiveBuffer.copyOf(receivePacket.length)
                } catch (e: SocketTimeoutException) {
                    continue
                } catch (e: Exception) {
                    continue
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error in upstream forward: ${e.message}")
        } finally {
            try {
                socket?.close()
            } catch (e: Exception) {
                // ignore
            }
        }
        return null
    }

    fun release() {
        try {
            connectivityManager?.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            // ignore
        }
    }
}
