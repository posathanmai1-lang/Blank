package com.blank.app.dns

data class ParsedDnsQuery(
    val sourceIp: ByteArray,
    val destIp: ByteArray,
    val sourcePort: Int,
    val destPort: Int,
    val transactionId: Int,
    val domain: String,
    val qType: Int,
    val qClass: Int,
    val dnsPayloadOffset: Int,
    val dnsPayloadLength: Int,
    val questionEndOffset: Int
)

object DnsPacketParser {

    private const val IPV4_UDP_PROTOCOL = 17
    private const val DNS_PORT = 53

    /**
     * Parses an IPv4 UDP packet destined for port 53.
     * Returns ParsedDnsQuery if valid DNS query, or null otherwise.
     */
    fun parseQuery(packet: ByteArray, length: Int): ParsedDnsQuery? {
        if (length < 28) return null // 20 byte IP + 8 byte UDP minimum

        // 1. Validate IPv4
        val version = (packet[0].toInt() shr 4) and 0x0F
        if (version != 4) return null

        val ihl = (packet[0].toInt() and 0x0F) * 4
        if (ihl < 20 || length < ihl + 8) return null

        // 2. Validate UDP Protocol
        val protocol = packet[9].toInt() and 0xFF
        if (protocol != IPV4_UDP_PROTOCOL) return null

        // 3. Validate UDP Header
        val srcPort = ((packet[ihl].toInt() and 0xFF) shl 8) or (packet[ihl + 1].toInt() and 0xFF)
        val dstPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or (packet[ihl + 3].toInt() and 0xFF)
        val udpLength = ((packet[ihl + 4].toInt() and 0xFF) shl 8) or (packet[ihl + 5].toInt() and 0xFF)

        if (dstPort != DNS_PORT) return null
        if (udpLength < 20 || length < ihl + udpLength) return null

        // 4. Validate DNS Header (12 bytes)
        val dnsOffset = ihl + 8
        val dnsPayloadLength = udpLength - 8
        if (dnsPayloadLength < 12) return null

        val transactionId = ((packet[dnsOffset].toInt() and 0xFF) shl 8) or (packet[dnsOffset + 1].toInt() and 0xFF)
        val flags = ((packet[dnsOffset + 2].toInt() and 0xFF) shl 8) or (packet[dnsOffset + 3].toInt() and 0xFF)
        val isQuery = (flags and 0x8000) == 0
        if (!isQuery) return null

        val qdCount = ((packet[dnsOffset + 4].toInt() and 0xFF) shl 8) or (packet[dnsOffset + 5].toInt() and 0xFF)
        if (qdCount < 1) return null

        // 5. Parse Question QNAME (RFC 1035 labels)
        var cursor = dnsOffset + 12
        val maxCursor = dnsOffset + dnsPayloadLength
        val domainBuilder = StringBuilder()

        while (cursor < maxCursor) {
            val labelLen = packet[cursor].toInt() and 0xFF
            cursor++

            if (labelLen == 0) {
                // End of QNAME
                break
            }

            // Guard against compression pointers in Question or malformed lengths
            if ((labelLen and 0xC0) != 0 || cursor + labelLen > maxCursor) {
                return null
            }

            if (domainBuilder.isNotEmpty()) {
                domainBuilder.append('.')
            }

            for (i in 0 until labelLen) {
                domainBuilder.append(packet[cursor + i].toInt().toChar())
            }
            cursor += labelLen
        }

        if (cursor + 4 > maxCursor) return null

        val qType = ((packet[cursor].toInt() and 0xFF) shl 8) or (packet[cursor + 1].toInt() and 0xFF)
        val qClass = ((packet[cursor + 2].toInt() and 0xFF) shl 8) or (packet[cursor + 3].toInt() and 0xFF)
        cursor += 4

        val srcIp = packet.copyOfRange(12, 16)
        val dstIp = packet.copyOfRange(16, 20)

        return ParsedDnsQuery(
            sourceIp = srcIp,
            destIp = dstIp,
            sourcePort = srcPort,
            destPort = dstPort,
            transactionId = transactionId,
            domain = domainBuilder.toString(),
            qType = qType,
            qClass = qClass,
            dnsPayloadOffset = dnsOffset,
            dnsPayloadLength = dnsPayloadLength,
            questionEndOffset = cursor
        )
    }
}
