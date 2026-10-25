package com.blank.app.dns

object DnsPacketSynthesizer {

    /**
     * Synthesizes an IPv4 UDP packet containing a DNS response pointing to 0.0.0.0 (Type A)
     * or NODATA (Type AAAA), ready to inject directly back into the TUN interface.
     */
    fun createBlockedResponse(
        rawPacket: ByteArray,
        query: ParsedDnsQuery
    ): ByteArray {
        val questionLen = query.questionEndOffset - query.dnsPayloadOffset - 12
        val isTypeA = query.qType == 1

        val answerLen = if (isTypeA) 16 else 0
        val dnsLen = 12 + questionLen + answerLen
        val udpLen = 8 + dnsLen
        val totalIpLen = 20 + udpLen

        val response = ByteArray(totalIpLen)

        // 1. Construct IPv4 Header (20 bytes)
        response[0] = 0x45.toByte() // IPv4, IHL = 5 (20 bytes)
        response[1] = 0x00.toByte()
        response[2] = ((totalIpLen shr 8) and 0xFF).toByte()
        response[3] = (totalIpLen and 0xFF).toByte()
        response[4] = 0x00.toByte()
        response[5] = 0x00.toByte()
        response[6] = 0x40.toByte() // Don't Fragment
        response[7] = 0x00.toByte()
        response[8] = 64.toByte()   // TTL = 64
        response[9] = 17.toByte()   // UDP = 17
        response[10] = 0x00.toByte() // Checksum placeholder
        response[11] = 0x00.toByte()

        // Swap IP addresses (Response Source is 10.0.0.2, Dest is Client)
        System.arraycopy(query.destIp, 0, response, 12, 4)
        System.arraycopy(query.sourceIp, 0, response, 16, 4)

        // Compute and insert IPv4 header checksum
        val ipChecksum = computeIpChecksum(response, 0, 20)
        response[10] = ((ipChecksum shr 8) and 0xFF).toByte()
        response[11] = (ipChecksum and 0xFF).toByte()

        // 2. Construct UDP Header (8 bytes)
        val udpOffset = 20
        response[udpOffset] = ((query.destPort shr 8) and 0xFF).toByte()     // Src Port = 53
        response[udpOffset + 1] = (query.destPort and 0xFF).toByte()
        response[udpOffset + 2] = ((query.sourcePort shr 8) and 0xFF).toByte() // Dst Port = Client Port
        response[udpOffset + 3] = (query.sourcePort and 0xFF).toByte()
        response[udpOffset + 4] = ((udpLen shr 8) and 0xFF).toByte()
        response[udpOffset + 5] = (udpLen and 0xFF).toByte()
        response[udpOffset + 6] = 0x00.toByte() // Optional checksum for IPv4 UDP
        response[udpOffset + 7] = 0x00.toByte()

        // 3. Construct DNS Header (12 bytes)
        val dnsOffset = 28
        response[dnsOffset] = ((query.transactionId shr 8) and 0xFF).toByte()
        response[dnsOffset + 1] = (query.transactionId and 0xFF).toByte()
        response[dnsOffset + 2] = 0x81.toByte() // QR=1, AA=1, RD=1
        response[dnsOffset + 3] = 0x80.toByte() // RA=1, RCODE=0 (No error)
        response[dnsOffset + 4] = 0x00.toByte()
        response[dnsOffset + 5] = 0x01.toByte() // QDCOUNT = 1
        response[dnsOffset + 6] = 0x00.toByte()
        response[dnsOffset + 7] = (if (isTypeA) 0x01 else 0x00).toByte() // ANCOUNT = 1 if A, 0 if AAAA
        response[dnsOffset + 8] = 0x00.toByte()
        response[dnsOffset + 9] = 0x00.toByte() // NSCOUNT = 0
        response[dnsOffset + 10] = 0x00.toByte()
        response[dnsOffset + 11] = 0x00.toByte() // ARCOUNT = 0

        // 4. Copy DNS Question verbatim from incoming packet
        System.arraycopy(rawPacket, query.dnsPayloadOffset + 12, response, dnsOffset + 12, questionLen)

        // 5. If Type A, append 0.0.0.0 Answer Section
        if (isTypeA) {
            val ansOffset = dnsOffset + 12 + questionLen
            response[ansOffset] = 0xC0.toByte()     // Name pointer to QNAME (offset 12)
            response[ansOffset + 1] = 0x0C.toByte()
            response[ansOffset + 2] = 0x00.toByte() // Type A (1)
            response[ansOffset + 3] = 0x01.toByte()
            response[ansOffset + 4] = 0x00.toByte() // Class IN (1)
            response[ansOffset + 5] = 0x01.toByte()
            response[ansOffset + 6] = 0x00.toByte() // TTL 60 seconds
            response[ansOffset + 7] = 0x00.toByte()
            response[ansOffset + 8] = 0x00.toByte()
            response[ansOffset + 9] = 0x3C.toByte()
            response[ansOffset + 10] = 0x00.toByte() // RDLENGTH = 4
            response[ansOffset + 11] = 0x04.toByte()
            response[ansOffset + 12] = 0x00.toByte() // RDATA = 0.0.0.0
            response[ansOffset + 13] = 0x00.toByte()
            response[ansOffset + 14] = 0x00.toByte()
            response[ansOffset + 15] = 0x00.toByte()
        }

        return response
    }

    /**
     * Synthesizes a SERVFAIL (RCODE 2) response when upstream resolution cannot reach any DNS server.
     * This informs the client OS immediately instead of leaving sockets hanging.
     */
    fun createServFailResponse(
        rawPacket: ByteArray,
        query: ParsedDnsQuery
    ): ByteArray {
        val questionLen = query.questionEndOffset - query.dnsPayloadOffset - 12
        val dnsLen = 12 + questionLen
        val udpLen = 8 + dnsLen
        val totalIpLen = 20 + udpLen

        val response = ByteArray(totalIpLen)

        // 1. IPv4 Header
        response[0] = 0x45.toByte()
        response[1] = 0x00.toByte()
        response[2] = ((totalIpLen shr 8) and 0xFF).toByte()
        response[3] = (totalIpLen and 0xFF).toByte()
        response[6] = 0x40.toByte()
        response[8] = 64.toByte()
        response[9] = 17.toByte()

        System.arraycopy(query.destIp, 0, response, 12, 4)
        System.arraycopy(query.sourceIp, 0, response, 16, 4)

        val ipChecksum = computeIpChecksum(response, 0, 20)
        response[10] = ((ipChecksum shr 8) and 0xFF).toByte()
        response[11] = (ipChecksum and 0xFF).toByte()

        // 2. UDP Header
        val udpOffset = 20
        response[udpOffset] = ((query.destPort shr 8) and 0xFF).toByte()
        response[udpOffset + 1] = (query.destPort and 0xFF).toByte()
        response[udpOffset + 2] = ((query.sourcePort shr 8) and 0xFF).toByte()
        response[udpOffset + 3] = (query.sourcePort and 0xFF).toByte()
        response[udpOffset + 4] = ((udpLen shr 8) and 0xFF).toByte()
        response[udpOffset + 5] = (udpLen and 0xFF).toByte()

        // 3. DNS Header
        val dnsOffset = 28
        response[dnsOffset] = ((query.transactionId shr 8) and 0xFF).toByte()
        response[dnsOffset + 1] = (query.transactionId and 0xFF).toByte()
        response[dnsOffset + 2] = 0x81.toByte() // QR=1, RD=1
        response[dnsOffset + 3] = 0x82.toByte() // RA=1, RCODE=2 (Server failure)
        response[dnsOffset + 4] = 0x00.toByte()
        response[dnsOffset + 5] = 0x01.toByte() // QDCOUNT = 1
        response[dnsOffset + 6] = 0x00.toByte()
        response[dnsOffset + 7] = 0x00.toByte() // ANCOUNT = 0
        response[dnsOffset + 8] = 0x00.toByte()
        response[dnsOffset + 9] = 0x00.toByte()
        response[dnsOffset + 10] = 0x00.toByte()
        response[dnsOffset + 11] = 0x00.toByte()

        // 4. Copy Question
        System.arraycopy(rawPacket, query.dnsPayloadOffset + 12, response, dnsOffset + 12, questionLen)

        return response
    }

    /**
     * Wraps an upstream DNS response payload in IPv4 + UDP headers targeting the original client.
     */
    fun wrapUpstreamResponse(
        query: ParsedDnsQuery,
        upstreamDnsPayload: ByteArray,
        payloadLength: Int
    ): ByteArray {
        val udpLen = 8 + payloadLength
        val totalIpLen = 20 + udpLen
        val response = ByteArray(totalIpLen)

        // 1. IPv4 Header
        response[0] = 0x45.toByte()
        response[1] = 0x00.toByte()
        response[2] = ((totalIpLen shr 8) and 0xFF).toByte()
        response[3] = (totalIpLen and 0xFF).toByte()
        response[4] = 0x00.toByte()
        response[5] = 0x00.toByte()
        response[6] = 0x40.toByte()
        response[7] = 0x00.toByte()
        response[8] = 64.toByte()
        response[9] = 17.toByte()

        System.arraycopy(query.destIp, 0, response, 12, 4)
        System.arraycopy(query.sourceIp, 0, response, 16, 4)

        val ipChecksum = computeIpChecksum(response, 0, 20)
        response[10] = ((ipChecksum shr 8) and 0xFF).toByte()
        response[11] = (ipChecksum and 0xFF).toByte()

        // 2. UDP Header
        val udpOffset = 20
        response[udpOffset] = ((query.destPort shr 8) and 0xFF).toByte()
        response[udpOffset + 1] = (query.destPort and 0xFF).toByte()
        response[udpOffset + 2] = ((query.sourcePort shr 8) and 0xFF).toByte()
        response[udpOffset + 3] = (query.sourcePort and 0xFF).toByte()
        response[udpOffset + 4] = ((udpLen shr 8) and 0xFF).toByte()
        response[udpOffset + 5] = (udpLen and 0xFF).toByte()
        response[udpOffset + 6] = 0x00.toByte()
        response[udpOffset + 7] = 0x00.toByte()

        // 3. DNS Payload - ensure transaction ID matches original query
        System.arraycopy(upstreamDnsPayload, 0, response, 28, payloadLength)
        response[28] = ((query.transactionId shr 8) and 0xFF).toByte()
        response[29] = (query.transactionId and 0xFF).toByte()

        return response
    }

    private fun computeIpChecksum(buffer: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset
        while (i < offset + length) {
            val word = ((buffer[i].toInt() and 0xFF) shl 8) or (buffer[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        while ((sum shr 16) > 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return (sum.inv()) and 0xFFFF
    }
}
