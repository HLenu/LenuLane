package net.steampp.wattkit.vpn

import org.junit.Test

class PacketChecksumAndTunTest {
    @Test fun ipv4AndUdpChecksumsVerifyToZero() {
        val srcIp = DnsPacketCodec.ipStringToInt("10.0.0.1")!!
        val dstIp = DnsPacketCodec.ipStringToInt("10.0.0.2")!!
        val payload = byteArrayOf(1, 2, 3, 4, 5, 6, 7) // odd length tests trailing-byte checksum padding
        val pkt = PacketChecksum.buildIpv4UdpPacket(srcIp, 53, dstIp, 45678, payload)

        check(PacketChecksum.internetChecksum(pkt, 0, 20) == 0) { "IPv4 header checksum invalid" }
        val udpLen = pkt.size - 20
        val pseudo = PacketChecksum.pseudoHeaderSum(srcIp, dstIp, PacketChecksum.PROTO_UDP, udpLen)
        check(PacketChecksum.internetChecksum(pkt, 20, udpLen, pseudo) == 0) { "UDP checksum invalid" }
    }

    @Test fun ipv4AndTcpChecksumsVerifyToZero() {
        val srcIp = DnsPacketCodec.ipStringToInt("198.18.0.10")!!
        val dstIp = DnsPacketCodec.ipStringToInt("10.0.0.2")!!
        val payload = "HTTP/1.1 200 OK\r\n".toByteArray(Charsets.US_ASCII)
        val pkt = PacketChecksum.buildIpv4TcpPacket(
            srcIp = srcIp,
            srcPort = 443,
            dstIp = dstIp,
            dstPort = 51000,
            seqNum = 0xFFFFFFFEL, // wraparound sequence boundary
            ackNum = 0x00000100L,
            tcpFlags = PacketChecksum.TCP_PSH or PacketChecksum.TCP_ACK,
            payload = payload
        )

        check(PacketChecksum.internetChecksum(pkt, 0, 20) == 0) { "IPv4 header checksum invalid" }
        val tcpLen = pkt.size - 20
        val pseudo = PacketChecksum.pseudoHeaderSum(srcIp, dstIp, PacketChecksum.PROTO_TCP, tcpLen)
        check(PacketChecksum.internetChecksum(pkt, 20, tcpLen, pseudo) == 0) { "TCP checksum invalid" }
    }

    @Test fun icmpEchoReplySwapsIpsAndComputesValidChecksum() {
        val req = ByteArray(28)
        req[0] = 0x45; req[2] = 0; req[3] = 28; req[8] = 64; req[9] = 1
        PacketChecksum.writeInt32(req, 12, DnsPacketCodec.ipStringToInt("10.0.0.2")!!)
        PacketChecksum.writeInt32(req, 16, DnsPacketCodec.ipStringToInt("10.0.0.1")!!)
        req[20] = 8 // Echo Request
        PacketChecksum.writeUInt16(req, 24, 0x1234)
        PacketChecksum.writeUInt16(req, 26, 1)
        PacketChecksum.writeUInt16(req, 22, PacketChecksum.internetChecksum(req, 20, 8))
        PacketChecksum.writeUInt16(req, 10, PacketChecksum.internetChecksum(req, 0, 20))

        val reply = PacketChecksum.buildIcmpEchoReply(req, req.size)
        check(reply != null)
        check((reply[20].toInt() and 0xFF) == 0) // Type 0 Echo Reply
        check(PacketChecksum.readInt32(reply, 12) == DnsPacketCodec.ipStringToInt("10.0.0.1")!!)
        check(PacketChecksum.readInt32(reply, 16) == DnsPacketCodec.ipStringToInt("10.0.0.2")!!)
        check(PacketChecksum.internetChecksum(reply, 0, 20) == 0)
        check(PacketChecksum.internetChecksum(reply, 20, reply.size - 20) == 0)
    }

    @Test fun icmpPortUnreachableGeneratedForFakeIpQuicUdp443() {
        val clientIp = DnsPacketCodec.ipStringToInt("10.0.0.2")!!
        val fakeIp = DnsPacketCodec.ipStringToInt("198.18.0.11")!!
        val quicPkt = PacketChecksum.buildIpv4UdpPacket(clientIp, 54321, fakeIp, 443, ByteArray(1200))

        val icmp = PacketChecksum.buildIcmpPortUnreachable(quicPkt, quicPkt.size)
        check(icmp != null)
        check((icmp[9].toInt() and 0xFF) == PacketChecksum.PROTO_ICMP)
        check((icmp[20].toInt() and 0xFF) == 3) // Dest Unreachable
        check((icmp[21].toInt() and 0xFF) == 3) // Port Unreachable
        check(PacketChecksum.readInt32(icmp, 12) == fakeIp)
        check(PacketChecksum.readInt32(icmp, 16) == clientIp)
        check(PacketChecksum.internetChecksum(icmp, 0, 20) == 0)
        check(PacketChecksum.internetChecksum(icmp, 20, icmp.size - 20) == 0)
    }
}
