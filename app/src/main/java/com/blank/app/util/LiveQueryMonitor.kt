package com.blank.app.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

object LiveQueryMonitor {

    data class LogEntry(
        val time: String,
        val domain: String,
        val isBlocked: Boolean,
        val latencyMs: Long
    )

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    val recentQueries = ConcurrentLinkedDeque<LogEntry>()

    var onQueryLogged: ((LogEntry) -> Unit)? = null

    fun record(domain: String, isBlocked: Boolean, latencyMs: Long) {
        val entry = LogEntry(
            time = timeFormat.format(Date()),
            domain = domain,
            isBlocked = isBlocked,
            latencyMs = latencyMs
        )
        recentQueries.addFirst(entry)
        while (recentQueries.size > 50) {
            recentQueries.removeLast()
        }
        onQueryLogged?.invoke(entry)
    }

    fun getHtmlDashboard(): String {
        val rows = StringBuilder()
        for (q in recentQueries) {
            val badge = if (q.isBlocked) {
                "<span style='color:#ff4d4d;font-weight:bold;'>BLOCKED (0.0.0.0)</span>"
            } else {
                "<span style='color:#00ff88;font-weight:bold;'>ALLOWED (${q.latencyMs}ms)</span>"
            }
            rows.append("<tr><td>${q.time}</td><td><code>${q.domain}</code></td><td>$badge</td></tr>")
        }

        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>Blank Live Monitor</title>
                <meta http-equiv="refresh" content="2">
                <style>
                    body { font-family: monospace; background: #0a0a0c; color: #fff; padding: 20px; }
                    h2 { color: #3b82f6; }
                    table { width: 100%; border-collapse: collapse; margin-top: 15px; }
                    th, td { border: 1px solid #222; padding: 8px 12px; text-align: left; }
                    th { background: #16161a; }
                    tr:nth-child(even) { background: #111114; }
                </style>
            </head>
            <body>
                <h2>🛡️ Blank Live Network Monitor</h2>
                <p>Status: <strong>ACTIVE</strong> | Total Captured: <strong>${StatsManager.totalQueries}</strong> | Blocked: <strong>${StatsManager.blockedAds}</strong></p>
                <table>
                    <thead>
                        <tr><th>Time</th><th>Domain</th><th>Status</th></tr>
                    </thead>
                    <tbody>
                        $rows
                    </tbody>
                </table>
            </body>
            </html>
        """.trimIndent()
    }
}
