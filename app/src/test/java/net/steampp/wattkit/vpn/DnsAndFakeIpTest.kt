package net.steampp.wattkit.vpn

import net.steampp.wattkit.proxy.DoHResolver
import org.junit.Test

class DnsAndFakeIpTest {
    @Test fun fakeIpPoolAllocatesInRfc2544SubnetAndLooksUpDomain() {
        val pool = FakeIpPool()
        val ip1 = pool.allocate("steamcommunity.com.")
        val ip2 = pool.allocate("SteamCommunity.COM")
        val ip3 = pool.allocate("store.steampowered.com")

        check(ip1 == ip2) { "Same domain should reuse same Fake-IP" }
        check(ip1 != ip3) { "Distinct domains must get distinct Fake-IPs" }
        check(FakeIpPool.isFakeIp(ip1))
        check(DnsPacketCodec.ipIntToString(ip1).startsWith("198.18."))
        check(pool.lookupDomain(ip1) == "steamcommunity.com")
        check(pool.lookupDomain(ip3) == "store.steampowered.com")
    }

    @Test fun dnsPacketCodecRoundtripsQueryAndAResponse() {
        val query = DnsPacketCodec.buildQuery("i.pximg.net", DnsPacketCodec.TYPE_A, 0x55AA)
        val q = DnsPacketCodec.parseQuestion(query, query.size)
        check(q != null)
        check(q.transactionId == 0x55AA)
        check(q.qName == "i.pximg.net")
        check(q.qType == DnsPacketCodec.TYPE_A)

        val fakeIp = DnsPacketCodec.ipStringToInt("198.18.0.42")!!
        val resp = DnsPacketCodec.buildAResponse(query, query.size, q, fakeIp, ttlSeconds = 60)
        val records = DnsPacketCodec.extractARecords(resp, resp.size)
        check(records.size == 1)
        check(records[0].first == fakeIp)
        check(records[0].second == 60)
    }

    @Test fun dnsEmptyNoErrorResponseHasZeroAnswersForAaaaAndHttps() {
        for (qType in listOf(DnsPacketCodec.TYPE_AAAA, DnsPacketCodec.TYPE_HTTPS)) {
            val query = DnsPacketCodec.buildQuery("store.steampowered.com", qType, 0x1122)
            val q = DnsPacketCodec.parseQuestion(query, query.size)!!
            val resp = DnsPacketCodec.buildEmptyNoErrorResponse(query, query.size, q)
            check(resp.size >= 12)
            val flags = ((resp[2].toInt() and 0xFF) shl 8) or (resp[3].toInt() and 0xFF)
            val anCount = ((resp[6].toInt() and 0xFF) shl 8) or (resp[7].toInt() and 0xFF)
            check((flags and 0x800F) == 0x8000) { "Expected QR=1 RCODE=0, got flags=0x${flags.toString(16)}" }
            check(anCount == 0)
            check(DnsPacketCodec.extractARecords(resp, resp.size).isEmpty())
        }
    }

    @Test fun dohFilterRejectsPoisonAndBogonButAllowsOfficialCdnIps() {
        val rejected = listOf(
            "0.0.0.0", "127.0.0.1", "10.0.2.15", "192.168.1.1", "172.16.0.1",
            "198.18.0.10", "198.19.1.5",
            "104.244.43.35", "157.240.17.41", "69.63.178.13", "103.252.115.53",
            "46.82.174.68", "108.160.165.141", "103.97.176.73"
        )
        for (ip in rejected) {
            check(DoHResolver.isPoisonedOrBogon(ip)) { "Should reject $ip" }
        }

        val allowed = listOf(
            "23.15.140.216", "23.35.102.114", "172.64.145.17",
            "210.140.139.130", "162.159.137.232", "57.155.223.240", "20.205.243.166"
        )
        for (ip in allowed) {
            check(!DoHResolver.isPoisonedOrBogon(ip)) { "Should allow clean CDN IP $ip" }
        }
    }

    @Test fun dohLiteralIpAndFallbackPoolOrderWorkCleanly() {
        val lit = DoHResolver.resolveCandidates("1.1.1.1")
        check(lit.size == 1 && lit[0].ip == "1.1.1.1")
        check(DoHResolver.resolveCandidates("198.18.0.10").isEmpty())
        val fallbackList = DoHResolver.fallbackIps["steamcommunity.com"]
        check(fallbackList != null && fallbackList.size >= 2)
        val discordList = DoHResolver.fallbackIps["discord.com"]
        check(discordList != null && discordList.size >= 3)
    }
}
