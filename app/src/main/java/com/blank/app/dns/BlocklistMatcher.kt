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
                        val line = rawLine.trim()
                        if (line.isNotEmpty() && !line.startsWith("#")) {
                            // Strip possible leading '0.0.0.0 ' or '127.0.0.1 ' if hosts format
                            val domain = line.split("\\s+".toRegex()).last()
                            if (domain.isNotEmpty() && !domain.startsWith("#")) {
                                onDomain(domain)
                            }
                        }
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
        if (!isLoaded || rawDomain.isEmpty()) return false

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
}
