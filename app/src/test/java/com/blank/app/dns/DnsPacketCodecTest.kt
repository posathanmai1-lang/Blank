package com.blank.app.dns

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsPacketCodecTest {

    @Test
    fun parsesIpv4UdpDnsQuestion() {
        val packet = createQueryPacket("tracker.invalid")

        val query = DnsPacketParser.parseQuery(packet, packet.size)

        checkNotNull(query)
        assertEquals("tracker.invalid", query.domain)
        assertEquals(1, query.qType)
        assertEquals(1, query.qClass)
        assertEquals(0x1234, query.transactionId)
        assertEquals(53000, query.sourcePort)
        assertEquals(53, query.destPort)
        assertEquals(packet.size - 28, query.dnsPayloadLength)
    }

    @Test
    fun rejectsDnsResponsesAndTruncatedPackets() {
        val response = createQueryPacket("tracker.invalid").also {
            it[30] = (it[30].toInt() or 0x80).toByte()
        }
        val truncated = createQueryPacket("tracker.invalid")

        assertNull(DnsPacketParser.parseQuery(response, response.size))
        assertNull(DnsPacketParser.parseQuery(truncated, truncated.size - 1))
    }

    @Test
    fun blockedAQueryGetsZeroAddressResponseWithValidIpChecksum() {
        val packet = createQueryPacket("tracker.invalid")
        val query = checkNotNull(DnsPacketParser.parseQuery(packet, packet.size))

        val response = DnsPacketSynthesizer.createBlockedResponse(packet, query)
        val dnsOffset = 28
        val questionLength = query.questionEndOffset - query.dnsPayloadOffset - 12
        val answerOffset = dnsOffset + 12 + questionLength
        val addressOffset = answerOffset + 12

        assertEquals(packet[16], response[12])
        assertEquals(packet[12], response[16])
        assertEquals(53, readUnsignedShort(response, 20))
        assertEquals(53000, readUnsignedShort(response, 22))
        assertEquals(0x1234, readUnsignedShort(response, dnsOffset))
        assertEquals(0x8180, readUnsignedShort(response, dnsOffset + 2))
        assertEquals(1, readUnsignedShort(response, dnsOffset + 6))
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), response.copyOfRange(addressOffset, addressOffset + 4))
        assertEquals(0xFFFF, foldedOnesComplementSum(response, 20))
    }

    @Test
    fun blockedAaaaQueryGetsNoDataResponse() {
        val packet = createQueryPacket("tracker.invalid", qType = 28)
        val query = checkNotNull(DnsPacketParser.parseQuery(packet, packet.size))

        val response = DnsPacketSynthesizer.createBlockedResponse(packet, query)

        assertEquals(0, readUnsignedShort(response, 34))
        assertEquals(0, response.size - (query.questionEndOffset - query.dnsPayloadOffset + 28))
    }

    @Test
    fun wrapsUpstreamPayloadAndRestoresOriginalUdpTuple() {
        val packet = createQueryPacket("allowed.invalid")
        val query = checkNotNull(DnsPacketParser.parseQuery(packet, packet.size))
        val upstream = packet.copyOfRange(
            query.dnsPayloadOffset,
            query.dnsPayloadOffset + query.dnsPayloadLength
        ).also {
            it[2] = 0x81.toByte()
            it[3] = 0x80.toByte()
        }

        val response = DnsPacketSynthesizer.wrapUpstreamResponse(query, upstream, upstream.size)

        assertEquals(53, readUnsignedShort(response, 20))
        assertEquals(53000, readUnsignedShort(response, 22))
        assertArrayEquals(upstream, response.copyOfRange(28, response.size))
        assertEquals(0xFFFF, foldedOnesComplementSum(response, 20))
    }

    @Test
    fun loadsDomainAndHostsFileLinesAndMatchesSuffixesWithWhitelistPriority() {
        assertEquals("tracker.invalid", BlocklistMatcher.parseDomainLine("Tracker.Invalid."))
        assertEquals(
            "tracker.invalid",
            BlocklistMatcher.parseDomainLine("0.0.0.0 Tracker.Invalid # generated entry")
        )
        assertNull(BlocklistMatcher.parseDomainLine("# comment"))
        assertNull(BlocklistMatcher.parseDomainLine("||tracker.invalid^"))
        assertNull(BlocklistMatcher.parseDomainLine("127.0.0.1 localhost"))
        assertNull(BlocklistMatcher.parseDomainLine("::1 ip6-localhost"))

        assertTrue(
            BlocklistMatcher.isBlocked(
                "ads.tracker.invalid.",
                blockedDomains = setOf("tracker.invalid"),
                whitelistedDomains = emptySet()
            )
        )
        assertFalse(
            BlocklistMatcher.isBlocked(
                "ads.safe.invalid",
                blockedDomains = setOf("invalid"),
                whitelistedDomains = setOf("safe.invalid")
            )
        )
    }

    private fun createQueryPacket(domain: String, qType: Int = 1): ByteArray {
        val labels = domain.split('.')
        val qnameSize = labels.sumOf { it.length + 1 } + 1
        val dns = ByteArray(12 + qnameSize + 4)
        dns[0] = 0x12
        dns[1] = 0x34
        dns[2] = 0x01
        dns[3] = 0x00
        dns[5] = 0x01

        var cursor = 12
        labels.forEach { label ->
            dns[cursor++] = label.length.toByte()
            label.forEach { char -> dns[cursor++] = char.code.toByte() }
        }
        dns[cursor++] = 0
        dns[cursor++] = (qType shr 8).toByte()
        dns[cursor++] = qType.toByte()
        dns[cursor++] = 0
        dns[cursor] = 1

        val udpLength = 8 + dns.size
        val packet = ByteArray(20 + udpLength)
        packet[0] = 0x45
        packet[2] = (packet.size shr 8).toByte()
        packet[3] = packet.size.toByte()
        packet[8] = 64
        packet[9] = 17
        packet[12] = 192.toByte()
        packet[13] = 0
        packet[14] = 2
        packet[15] = 10
        packet[16] = 10
        packet[17] = 0
        packet[18] = 0
        packet[19] = 2
        packet[20] = (53000 shr 8).toByte()
        packet[21] = 53000.toByte()
        packet[22] = 0
        packet[23] = 53
        packet[24] = (udpLength shr 8).toByte()
        packet[25] = udpLength.toByte()
        System.arraycopy(dns, 0, packet, 28, dns.size)
        return packet
    }

    private fun readUnsignedShort(packet: ByteArray, offset: Int): Int =
        ((packet[offset].toInt() and 0xFF) shl 8) or (packet[offset + 1].toInt() and 0xFF)

    private fun foldedOnesComplementSum(packet: ByteArray, length: Int): Int {
        var sum = 0
        var offset = 0
        while (offset < length) {
            sum += ((packet[offset].toInt() and 0xFF) shl 8) or
                (packet[offset + 1].toInt() and 0xFF)
            offset += 2
        }
        while (sum ushr 16 != 0) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return sum
    }
}
