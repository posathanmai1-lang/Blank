package com.blank.app.dns

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap

object BlocklistMatcher {

    private const val TAG = "BlocklistMatcher"

    // High performance in-memory sets for O(1) lookups
    private val blockedDomains = ConcurrentHashMap.newKeySet<String>()
    private val whitelistedDomains = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    var isLoaded: Boolean = false
        private set

    val blocklistCount: Int
        get() = blockedDomains.size

    val whitelistCount: Int
        get() = whitelistedDomains.size

    /**
     * Loads blocklist and whitelist from app assets into memory.
     */
    fun load(context: Context) {
        try {
            val startMs = System.currentTimeMillis()
            blockedDomains.clear()
            whitelistedDomains.clear()

            // Load Whitelist
            loadLinesFromAsset(context, "whitelist.txt") { line ->
                whitelistedDomains.add(line.lowercase().trim())
            }

            // Load Blocklist
            loadLinesFromAsset(context, "blocklist.txt") { line ->
                blockedDomains.add(line.lowercase().trim())
            }

            isLoaded = true
            val elapsedMs = System.currentTimeMillis() - startMs
            Log.i(TAG, "Loaded ${blockedDomains.size} blocked domains & ${whitelistedDomains.size} whitelisted domains in ${elapsedMs}ms")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading blocklist assets", e)
        }
    }

    private inline fun loadLinesFromAsset(
        context: Context,
        assetFileName: String,
        crossinline onDomain: (String) -> Unit
    ) {
        try {
            context.assets.open(assetFileName).use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    reader.forEachLine { rawLine ->
                        parseDomainLine(rawLine)?.let(onDomain)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not load asset $assetFileName: ${e.message}")
        }
    }

    /**
     * Tests if a domain is blocked via right-to-left suffix matching.
     * Example: for "ads.unity3d.com", checks "ads.unity3d.com" then "unity3d.com".
     */
    fun isBlocked(rawDomain: String): Boolean {
        if (!isLoaded) return false
        return isBlocked(rawDomain, blockedDomains, whitelistedDomains)
    }

    internal fun isBlocked(
        rawDomain: String,
        blockedDomains: Set<String>,
        whitelistedDomains: Set<String>
    ): Boolean {
        if (rawDomain.isEmpty()) return false

        // Normalize domain (strip trailing dot and convert to lowercase)
        val domain = if (rawDomain.endsWith('.')) {
            rawDomain.substring(0, rawDomain.length - 1).lowercase()
        } else {
            rawDomain.lowercase()
        }

        // 1. Whitelist check (Exact or suffix match)
        var current: String = domain
        while (current.isNotEmpty()) {
            if (whitelistedDomains.contains(current)) {
                return false // Explicitly whitelisted
            }
            val nextDot = current.indexOf('.')
            if (nextDot == -1) break
            current = current.substring(nextDot + 1)
        }

        // 2. Blocklist check (Exact or suffix match)
        current = domain
        while (current.isNotEmpty()) {
            if (blockedDomains.contains(current)) {
                return true // Domain or parent domain is in blocklist
            }
            val nextDot = current.indexOf('.')
            if (nextDot == -1) break
            current = current.substring(nextDot + 1)
        }

        return false
    }

    /** Accepts one domain per line or a hosts-file mapping; ignores comments and filter syntax. */
    internal fun parseDomainLine(rawLine: String): String? {
        val line = rawLine.substringBefore('#').trim()
        if (line.isEmpty()) return null

        val fields = line.split("\\s+".toRegex())
        val candidate = when {
            fields.size == 1 -> fields[0]
            isHostsAddress(fields[0]) -> fields.getOrNull(1) ?: return null
            else -> return null
        }

        val domain = candidate.removeSuffix(".").lowercase()
        if (domain.isEmpty() || domain.length > 253) return null
        if (isHostsAddress(domain) ||
            domain == "localhost" || domain.endsWith(".localhost") ||
            domain.endsWith(".localdomain") || domain == "ip6-localhost" ||
            domain == "ip6-loopback" || domain == "broadcasthost"
        ) {
            return null
        }

        val labels = domain.split('.')
        if (labels.any { label ->
                label.isEmpty() || label.length > 63 ||
                    label.first() == '-' || label.last() == '-' ||
                    label.any { char -> !char.isLetterOrDigit() && char != '-' }
            }) {
            return null
        }

        return domain
    }

    private fun isHostsAddress(value: String): Boolean {
        if (value == "::" || value == "::1") return true
        val octets = value.split('.')
        return octets.size == 4 && octets.all { octet ->
            val number = octet.toIntOrNull()
            number != null && number in 0..255
        }
    }
}
