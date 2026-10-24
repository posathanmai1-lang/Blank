package com.blank.app.dns

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

class UpstreamDnsResolver(private val vpnService: VpnService) {

    companion object {
        private const val TAG = "UpstreamDnsResolver"
        private const val DNS_PORT = 53
        private const val SOCKET_TIMEOUT_MS = 2000
    }

    private val connectivityManager =
        vpnService.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val publicFallbacks: List<InetAddress> by lazy {
        listOfNotNull(
            try { InetAddress.getByName("1.1.1.1") } catch (e: Exception) { null },
            try { InetAddress.getByName("8.8.8.8") } catch (e: Exception) { null },
            try { InetAddress.getByName("1.0.0.1") } catch (e: Exception) { null }
        )
    }

    /**
     * Discovers active physical network DNS servers (e.g. Jio/Airtel 5G, Wi-Fi),
     * filtering out the VPN interface and adding public fallbacks.
     */
    private fun getActiveDnsServers(): List<InetAddress> {
        val servers = mutableListOf<InetAddress>()

        try {
            val cm = connectivityManager
            if (cm != null) {
                // Find all physical networks (exclude the VPN network itself)
                for (network in cm.allNetworks) {
                    val caps = cm.getNetworkCapabilities(network)
                    if (caps != null && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                        val linkProps = cm.getLinkProperties(network)
                        linkProps?.dnsServers?.forEach { address ->
                            val host = address.hostAddress ?: ""
                            if (host != "10.0.0.2" && !address.isLoopbackAddress && !address.isAnyLocalAddress) {
                                servers.add(address)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error discovering physical network DNS servers: ${e.message}")
        }

        // Add public DNS fallbacks
        servers.addAll(publicFallbacks)
        return servers.distinct()
    }

    /**
     * Forwards an allowed DNS query payload to upstream DNS servers using a protected socket.
     * Tries the carrier/Wi-Fi native DNS first, then public fallbacks.
     */
    fun forward(dnsPayload: ByteArray, offset: Int, length: Int): ByteArray? {
        val dnsServers = getActiveDnsServers()
        if (dnsServers.isEmpty()) return null

        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            vpnService.protect(socket)
            socket.soTimeout = SOCKET_TIMEOUT_MS

            val receiveBuffer = ByteArray(4096)
            val receivePacket = DatagramPacket(receiveBuffer, receiveBuffer.size)

            for (server in dnsServers) {
                try {
                    val sendPacket = DatagramPacket(dnsPayload, offset, length, server, DNS_PORT)
                    socket.send(sendPacket)
                    socket.receive(receivePacket)

                    return receiveBuffer.copyOf(receivePacket.length)
                } catch (e: SocketTimeoutException) {
                    // Try next DNS server in list
                    continue
                } catch (e: Exception) {
                    Log.w(TAG, "Query failed on ${server.hostAddress}: ${e.message}")
                    continue
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error opening protected socket for DNS: ${e.message}")
        } finally {
            socket?.close()
        }
        return null
    }
}
