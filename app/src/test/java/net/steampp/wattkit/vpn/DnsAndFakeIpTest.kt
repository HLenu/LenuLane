package net.steampp.wattkit.vpn

import net.steampp.wattkit.authenticator.SteamTotp
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
            "46.82.174.68", "108.160.165.141", "108.160.169.181", "128.121.243.228",
            "199.96.58.177", "103.97.176.73"
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

    @Test fun dohCandidateMergeAndEwmaScoringReorderHealthyLowestLatencyFirst() {
        DoHResolver.clearCache()
        val host = "unit.test.example.org"
        DoHResolver.recordIpSuccess(host, "1.2.3.4", 180L)
        DoHResolver.recordIpSuccess(host, "5.6.7.8", 42L)
        DoHResolver.markIpFailed(host, "9.9.9.9")

        val ranked = DoHResolver.mergeAndRankCandidates(
            hostname = host,
            discoveredIps = listOf("9.9.9.9", "1.2.3.4"),
            fallbackEdgePool = listOf("5.6.7.8", "1.2.3.4")
        )
        check(ranked == listOf("5.6.7.8", "1.2.3.4", "9.9.9.9")) { "Unexpected candidate order: $ranked" }
        DoHResolver.clearCache()
    }

    @Test fun smartDnsWireCacheServesRepeatUnmatchedDomainWithPatchedTransactionId() {
        val pool = FakeIpPool()
        var upstreamCalls = 0
        val realIp = DnsPacketCodec.ipStringToInt("220.181.38.148")!!

        val engine = SmartDnsEngine(
            fakeIpPool = pool,
            protectUdp = { true },
            upstreamForwarder = { q, len, _ ->
                upstreamCalls++
                val question = DnsPacketCodec.parseQuestion(q, len)!!
                DnsPacketCodec.buildAResponse(q, len, question, realIp, ttlSeconds = 120)
            }
        )

        val q1 = DnsPacketCodec.buildQuery("baidu.com", DnsPacketCodec.TYPE_A, 0x1111)
        val r1 = engine.handleDnsQuery(q1, q1.size)!!
        check(upstreamCalls == 1)
        check(engine.cacheMisses.get() == 1L && engine.cacheHits.get() == 0L)
        check(((r1[0].toInt() and 0xFF) shl 8 or (r1[1].toInt() and 0xFF)) == 0x1111)

        val q2 = DnsPacketCodec.buildQuery("baidu.com", DnsPacketCodec.TYPE_A, 0x2222)
        val r2 = engine.handleDnsQuery(q2, q2.size)!!
        check(upstreamCalls == 1) { "Expected cached hit without extra upstream query" }
        check(engine.cacheHits.get() == 1L)
        check(((r2[0].toInt() and 0xFF) shl 8 or (r2[1].toInt() and 0xFF)) == 0x2222)
        val extracted = DnsPacketCodec.extractARecords(r2, r2.size)
        check(extracted.size == 1 && extracted[0].first == realIp)
    }

    @Test fun dnsResponseMatchingRejectsWrongIdQuestionAndQuery() {
        val query = DnsPacketCodec.buildQuery("example.org", transactionId = 123)
        val question = DnsPacketCodec.parseQuestion(query)!!
        val response = DnsPacketCodec.buildAResponse(query, query.size, question, 0x01020304)
        check(DnsPacketCodec.matchesResponse(question, response))
        check(!DnsPacketCodec.matchesResponse(question, response.copyOf().apply { this[1] = 7 }))
        check(!DnsPacketCodec.matchesResponse(question, response.copyOf().apply { this[13] = 'x'.code.toByte() }))
        check(!DnsPacketCodec.matchesResponse(question, query))
        check(!DnsPacketCodec.matchesResponse(question, response, 12))
    }

    @Test fun wireCacheAgesTtlAndDoesNotExtendShortOrZeroTtl() {
        var now = 1000L
        var calls = 0
        var ttl = 5
        val engine = SmartDnsEngine(FakeIpPool(), { true }, { q, len, _ ->
            calls++
            DnsPacketCodec.buildAResponse(q, len, DnsPacketCodec.parseQuestion(q, len)!!, 0x01020304, ttl)
        }, { now })
        val query = DnsPacketCodec.buildQuery("example.org")
        check(engine.handleDnsQuery(query, query.size) != null)
        now += 3000
        check(DnsPacketCodec.extractARecords(engine.handleDnsQuery(query, query.size)!!).single().second == 2)
        check(calls == 1)
        now += 2000
        ttl = 0
        check(engine.handleDnsQuery(query, query.size) != null)
        check(engine.handleDnsQuery(query, query.size) != null)
        check(calls == 3)
    }

    @Test fun tcpSendWindowHonorsRemainingBytesAndRetransmissionCapacity() {
        check(TcpSendWindow.available(1360, 0, 0, 0, 384) == 0)
        check(TcpSendWindow.available(1360, 1000, 900, 1, 384) == 100)
        check(TcpSendWindow.available(1360, 1000, 1000, 1, 384) == 0)
        check(TcpSendWindow.available(1360, 5000, 0, 384, 384) == 0)
        check(TcpSendWindow.available(1360, 5000, 100, 1, 384) == 1360)
    }

    @Test fun steamTotpOtpAuthUriParsingAndConfirmationHashWorkOnJvm() {
        val uri = "otpauth://totp/Steam:TestPlayer?secret=JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP&issuer=Steam"
        val acc = SteamTotp.parseOtpAuthUri(uri)
        check(acc != null && acc.accountName == "TestPlayer")
        check(SteamTotp.isValidBase64Secret(acc.sharedSecret))

        val code = SteamTotp.generateSteamGuardCode(acc.sharedSecret)
        check(code.length == 5 && code != "ERROR")

        val confHash = SteamTotp.generateConfirmationHash(acc.sharedSecret, 1_700_000_000L, "conf")
        check(!confHash.isNullOrBlank() && confHash.length == 28 && confHash.endsWith("="))
    }
}
