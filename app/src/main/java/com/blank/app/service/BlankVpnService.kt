package com.blank.app.service

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.blank.app.dns.BlocklistMatcher
import com.blank.app.dns.DnsPacketParser
import com.blank.app.dns.DnsPacketSynthesizer
import com.blank.app.dns.UpstreamDnsResolver
import com.blank.app.util.StatsManager
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class BlankVpnService : VpnService(), Runnable {

    companion object {
        const val TAG = "BlankVpnService"
        const val ACTION_START = "com.blank.app.START"
        const val ACTION_STOP = "com.blank.app.STOP"
        const val VPN_ADDRESS = "10.0.0.2"
        const val VPN_PREFIX = 32

        val isRunning = AtomicBoolean(false)
        var stateChangeListener: ((Boolean) -> Unit)? = null
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var workerThread: Thread? = null
    private var dnsExecutor: ExecutorService? = null
    private var upstreamResolver: UpstreamDnsResolver? = null
    private var outStream: FileOutputStream? = null
    private val writeLock = Any()

    override fun onCreate() {
        super.onCreate()
        StatsManager.init(applicationContext)
        if (!BlocklistMatcher.isLoaded) {
            BlocklistMatcher.load(applicationContext)
        }
        upstreamResolver = UpstreamDnsResolver(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopVpn()
            else -> startVpn()
        }
        return START_STICKY
    }

    private fun startVpn() {
        if (isRunning.get()) {
            Log.d(TAG, "Blank VPN is already running")
            return
        }

        try {
            if (!BlocklistMatcher.isLoaded) {
                BlocklistMatcher.load(applicationContext)
            }

            val builder = Builder()
                .setSession("Blank")
                .addAddress(VPN_ADDRESS, VPN_PREFIX)
                .addDnsServer(VPN_ADDRESS) // Intercept all system DNS queries
                .addRoute(VPN_ADDRESS, VPN_PREFIX)
                .setMtu(1500)
                .setBlocking(true)

            // Exclude apps with strict anti-VPN checks (JioHotstar, JioCinema, Netflix, banking)
            // This strips the TRANSPORT_VPN flag for these apps, preventing NET_100 errors.
            val antiVpnBypassApps = listOf(
                "in.startv.hotstar",
                "in.startv.hotstar.dplus",
                "com.jio.media.ondemand",
                "com.netflix.ninja",
                "com.netflix.mediaclient",
                "com.amazon.avod.thirdpartyclient",
                "com.google.android.apps.nbu.paisa.user",
                "net.one97.paytm",
                "com.phonepe.app"
            )
            for (pkg in antiVpnBypassApps) {
                try {
                    builder.addDisallowedApplication(pkg)
                    Log.i(TAG, "Exempted anti-VPN app from tunnel: $pkg")
                } catch (e: Exception) {
                    // App is not installed on this device, safely ignore
                }
            }

            // Configure pending intent to open MainActivity from notification
            val configureIntent = packageManager.getLaunchIntentForPackage(packageName)?.let {
                PendingIntent.getActivity(
                    this,
                    0,
                    it,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            }
            if (configureIntent != null) {
                builder.setConfigureIntent(configureIntent)
            }

            vpnInterface = builder.establish()
            if (vpnInterface == null) {
                Log.e(TAG, "Failed to establish VPN interface")
                return
            }

            dnsExecutor = Executors.newFixedThreadPool(4)
            isRunning.set(true)
            stateChangeListener?.invoke(true)

            workerThread = Thread(this, "BlankVpnThread").apply { start() }
            Log.i(TAG, "Blank VPN active with ${BlocklistMatcher.blocklistCount} blocked domains")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Blank VPN", e)
            stopVpn()
        }
    }

    override fun run() {
        val pfd = vpnInterface ?: return
        val inStream = FileInputStream(pfd.fileDescriptor)
        val out = FileOutputStream(pfd.fileDescriptor)
        outStream = out

        val packetBuffer = ByteArray(32767)
        Log.i(TAG, "Blank DNS packet filter loop started")

        try {
            while (isRunning.get() && !Thread.currentThread().isInterrupted) {
                val length = inStream.read(packetBuffer)
                if (length > 0) {
                    handleIncomingPacket(packetBuffer, length)
                }
            }
        } catch (e: IOException) {
            if (isRunning.get()) {
                Log.w(TAG, "VPN read IO ended: ${e.message}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in VPN worker loop", e)
        } finally {
            Log.i(TAG, "Blank packet loop stopped cleanly")
        }
    }

    private fun handleIncomingPacket(packet: ByteArray, length: Int) {
        if (length < 28) return

        val ihl = (packet[0].toInt() and 0x0F) * 4
        if (length < ihl + 8) return

        val protocol = packet[9].toInt() and 0xFF
        if (protocol == 17) { // UDP
            val dstPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or (packet[ihl + 3].toInt() and 0xFF)

            // Silently drop outgoing QUIC (UDP 443) to force standard TCP HTTPS + DNS resolution
            if (dstPort == 443) {
                return
            }

            // Port 53: DNS Query
            if (dstPort == 53) {
                val query = DnsPacketParser.parseQuery(packet, length)
                if (query != null) {
                    val packetCopy = packet.copyOf(length)
                    dnsExecutor?.submit {
                        processDnsQuery(packetCopy, query)
                    }
                    return
                }
            }
        }

        // Pass-through for other local packets
        writePacketToTun(packet.copyOf(length))
    }

    private fun processDnsQuery(rawPacket: ByteArray, query: com.blank.app.dns.ParsedDnsQuery) {
        val domain = query.domain
        val isBlocked = BlocklistMatcher.isBlocked(domain)

        if (isBlocked) {
            // Synthesize instant 0.0.0.0 DNS response
            StatsManager.incrementBlocked()
            val responsePacket = DnsPacketSynthesizer.createBlockedResponse(rawPacket, query)
            writePacketToTun(responsePacket)
        } else {
            // Forward to upstream DNS resolver
            StatsManager.incrementAllowed()
            val upstreamPayload = upstreamResolver?.forward(
                rawPacket,
                query.dnsPayloadOffset,
                query.dnsPayloadLength
            )

            if (upstreamPayload != null) {
                val responsePacket = DnsPacketSynthesizer.wrapUpstreamResponse(
                    query,
                    upstreamPayload,
                    upstreamPayload.size
                )
                writePacketToTun(responsePacket)
            }
        }
    }

    private fun writePacketToTun(packet: ByteArray) {
        val stream = outStream ?: return
        synchronized(writeLock) {
            try {
                stream.write(packet)
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.w(TAG, "Error writing to TUN: ${e.message}")
                }
            }
        }
    }

    private fun stopVpn() {
        Log.i(TAG, "Stopping Blank VPN...")
        isRunning.set(false)
        stateChangeListener?.invoke(false)

        dnsExecutor?.shutdownNow()
        dnsExecutor = null

        try {
            outStream?.close()
        } catch (e: Exception) {
            // ignore
        }
        outStream = null

        try {
            vpnInterface?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error closing VPN interface", e)
        }
        vpnInterface = null

        workerThread?.interrupt()
        workerThread = null

        stopSelf()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    override fun onRevoke() {
        Log.i(TAG, "VPN authorization revoked by system")
        stopVpn()
        super.onRevoke()
    }
}
