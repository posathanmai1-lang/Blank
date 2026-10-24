package com.blank.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.blank.app.R
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
        private const val NOTIF_CHANNEL_ID = "blank_vpn_channel"
        private const val NOTIF_ID = 1001

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
        createNotificationChannel()
        if (!BlocklistMatcher.isLoaded) {
            BlocklistMatcher.load(applicationContext)
        }
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

            // Re-create resolver if it was released on a previous stop
            if (upstreamResolver == null) {
                upstreamResolver = UpstreamDnsResolver(this)
            }

            val builder = Builder()
                .setSession("Blank")
                .addAddress(VPN_ADDRESS, VPN_PREFIX)
                .addDnsServer(VPN_ADDRESS)       // Intercept all DNS queries locally via IPv4
                .addRoute(VPN_ADDRESS, VPN_PREFIX)
                .setMtu(1500)
                .setBlocking(true)

            // Disallow Blank itself so its upstream UDP sockets never loop into the TUN
            try {
                builder.addDisallowedApplication(packageName)
                Log.i(TAG, "Exempted Blank from VPN: $packageName")
            } catch (e: Exception) {
                Log.w(TAG, "Could not exempt own package: ${e.message}")
            }

            // Exclude apps with strict anti-VPN checks so they can still connect
            // (JioHotstar, JioCinema, Netflix, banking/UPI apps)
            val antiVpnBypassApps = listOf(
                "in.startv.hotstar",
                "in.startv.hotstar.dplus",
                "com.jio.media.ondemand",
                "com.netflix.ninja",
                "com.netflix.mediaclient",
                "com.amazon.avod.thirdpartyclient",
                "com.google.android.apps.nbu.paisa.user",
                "net.one97.paytm",
                "com.phonepe.app",
                "in.gov.uidai.mAadhaarPlus",
                "com.sbi.lotusintouch",
                "com.csam.icici.bank.imobile"
            )
            for (pkg in antiVpnBypassApps) {
                try {
                    builder.addDisallowedApplication(pkg)
                } catch (e: Exception) {
                    // App not installed on this device — safe to ignore
                }
            }

            val configureIntent = packageManager.getLaunchIntentForPackage(packageName)?.let {
                PendingIntent.getActivity(
                    this, 0, it,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            }
            if (configureIntent != null) {
                builder.setConfigureIntent(configureIntent)
            }

            vpnInterface = builder.establish()
            if (vpnInterface == null) {
                Log.e(TAG, "Failed to establish VPN interface — user revoked permission?")
                return
            }

            // Android 8+ requires a foreground notification or the service is killed within seconds
            startForeground(NOTIF_ID, buildNotification())

            dnsExecutor = Executors.newFixedThreadPool(4)
            isRunning.set(true)
            stateChangeListener?.invoke(true)

            workerThread = Thread(this, "BlankVpnThread").apply { start() }
            Log.i(TAG, "Blank VPN active — ${BlocklistMatcher.blocklistCount} blocked domains")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Blank VPN", e)
            stopVpn()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID,
                "Blank VPN",
                NotificationManager.IMPORTANCE_LOW  // Silent — no sound/vibration
            ).apply {
                description = "Blank ad-blocking VPN is running"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openAppIntent = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIF_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Blank is active")
            .setContentText("Blocking ads & trackers across all apps")
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .build()
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

        // 1. Verify IPv4
        val version = (packet[0].toInt() shr 4) and 0x0F
        if (version != 4) return

        val ihl = (packet[0].toInt() and 0x0F) * 4
        if (ihl < 20 || length < ihl + 8) return

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

        // Drop all non-DNS packets — the TUN interface only handles DNS (port 53).
    }

    private fun processDnsQuery(rawPacket: ByteArray, query: com.blank.app.dns.ParsedDnsQuery) {
        val domain = query.domain
        val isBlocked = BlocklistMatcher.isBlocked(domain)

        if (isBlocked) {
            // Synthesize instant 0.0.0.0 DNS response — ad is blocked
            StatsManager.incrementBlocked()
            val responsePacket = DnsPacketSynthesizer.createBlockedResponse(rawPacket, query)
            writePacketToTun(responsePacket)
        } else {
            // Forward to upstream physical network DNS resolver
            StatsManager.incrementAllowed()
            val upstreamPayload = upstreamResolver?.forward(
                rawPacket,
                query.dnsPayloadOffset,
                query.dnsPayloadLength
            )

            if (upstreamPayload != null) {
                // Got a real answer — wrap it and inject back into TUN
                val responsePacket = DnsPacketSynthesizer.wrapUpstreamResponse(
                    query,
                    upstreamPayload,
                    upstreamPayload.size
                )
                writePacketToTun(responsePacket)
            } else {
                // All DNS servers timed out — send SERVFAIL immediately so the OS
                // retries or reports failure rather than leaving the socket hanging forever.
                // This was causing other apps to lose internet when Blank was active.
                val servFail = DnsPacketSynthesizer.createServFailResponse(rawPacket, query)
                writePacketToTun(servFail)
            }
        }
    }

    private fun writePacketToTun(packet: ByteArray) {
        val stream = outStream ?: return
        synchronized(writeLock) {
            try {
                stream.write(packet)
                stream.flush()
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

        // Stop foreground before releasing resources
        stopForeground(true)

        dnsExecutor?.shutdownNow()
        dnsExecutor = null

        // Release NetworkCallback to prevent memory leaks
        upstreamResolver?.release()
        upstreamResolver = null

        try {
            outStream?.close()
        } catch (e: Exception) { /* ignore */ }
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
