package com.withu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidenceTest {
    private val fakeSign: (String) -> String = { "sig:" + it.take(8) }
    private val fakeVerify: (Chunk) -> Boolean = { it.sig == "sig:" + it.hash.take(8) }

    private fun sealMinutes(minutes: Int): List<Chunk> {
        val out = mutableListOf<Chunk>()
        val s = ChunkSealer(7, 0, fakeSign) { out += it }
        var t = 0L
        while (t < minutes * 60_000L) { s.add(Sample(t, 50f, 'D', false, '0', 0f, true, false, false, 37.5, 127.0)); t += 100 }
        s.flush()
        return out
    }

    @Test fun pendingShowsUnsealedSamples_withoutSealing() {
        val out = mutableListOf<Chunk>()
        val s = ChunkSealer(7, 0, fakeSign) { out += it }
        assertTrue(s.pending().isEmpty())
        for (t in 0L until 1_000L step 100) s.add(Sample(t, 30f, 'D', true, 'R', 1f, null, null, false, null, null))
        val p = s.pending()
        assertEquals(10, p.size); assertEquals(0L, p.first().t); assertEquals(900L, p.last().t)
        assertEquals(30f, p[3].spd); assertEquals('R', p[3].turn)
        assertTrue("미리보기만으로는 봉인하지 않는다", out.isEmpty())
        s.add(Sample(60_000, 0f))                 // 다음 분 → 이전 분 봉인
        assertEquals(1, out.size); assertEquals(10, out[0].n)
        assertEquals(listOf(60_000L), s.pending().map { it.t })
        s.flush(); assertTrue(s.pending().isEmpty())
    }

    @Test fun sealerCutsOneChunkPerMinute_andChainsThem() {
        val c = sealMinutes(3)
        assertEquals(3, c.size)
        assertEquals(listOf(600, 600, 600), c.map { it.n })
        assertEquals(HashChain.genesis(7, 0), c[0].prev)
        assertEquals(c[0].hash, c[1].prev); assertEquals(c[1].hash, c[2].prev)
        assertEquals(0L, c[0].m0); assertEquals(60_000L, c[0].m1)
        assertTrue(HashChain.verify(c, fakeVerify).all { it.ok })
    }

    @Test fun tamperedData_breaksHashOfThatChunkOnly() {
        val c = sealMinutes(3).toMutableList()
        c[1] = c[1].copy(data = c[1].data.replaceFirst(",50.0,", ",49.0,"))
        val r = HashChain.verify(c, fakeVerify)
        assertTrue(r[0].ok); assertFalse(r[1].hashOk); assertTrue(r[1].linkOk); assertTrue(r[2].hashOk)
    }

    @Test fun missingChunk_breaksLinkOfNextChunk() {
        val c = sealMinutes(3)
        val r = HashChain.verify(listOf(c[0], c[2]), fakeVerify)
        assertTrue(r[0].ok); assertTrue(r[1].hashOk); assertFalse(r[1].linkOk); assertFalse(r[1].ok)
    }

    @Test fun differentTrip_doesNotRequireLink() {
        val a = sealMinutes(1); val b = sealMinutes(1).map { it.copy(trip = 8, prev = "x", hash = HashChain.seal("x", it.data)).let { k -> k.copy(sig = fakeSign(k.hash)) } }
        assertTrue(HashChain.verify(a + b, fakeVerify).all { it.ok })
    }

    @Test fun badSignature_isReported() {
        val c = sealMinutes(1).map { it.copy(sig = "forged") }
        val r = HashChain.verify(c, fakeVerify).single()
        assertTrue(r.hashOk); assertFalse(r.sigOk); assertFalse(r.ok)
    }

    @Test fun flushOnEmpty_returnsNull_andPartialFlushKeepsChain() {
        val out = mutableListOf<Chunk>()
        val s = ChunkSealer(1, 5, fakeSign) { out += it }
        assertNull(s.flush())
        s.add(Sample(1_000, 10f)); s.add(Sample(1_100, 11f))
        val first = s.flush()!!
        assertEquals(2, first.n); assertEquals(1_000L, first.m0); assertEquals(1_200L, first.m1)
        s.add(Sample(1_200, 12f))
        val second = s.flush()!!
        assertEquals(first.hash, second.prev); assertEquals(second.hash, s.prev)
        assertTrue(HashChain.verify(out, fakeVerify).all { it.ok })
    }

    @Test fun sealIsOrderSensitive() {
        assertFalse(HashChain.seal("a", "b") == HashChain.seal("b", "a"))
        assertEquals(64, Hash.sha256("x").length)
        assertEquals("2d711642b726b04401627ca9fbac32f5c8530fb1903cc4db02258717921a4881", Hash.sha256("x"))
    }

    // ---------- 보관 정책 ----------
    @Test fun retention_sevenDaysThenExpire_boundaryKept() {
        val end = 1_000L
        val keep = Retention.keepOnEnd(end)
        assertEquals(end + 7 * Retention.DAY, keep)
        assertFalse(Retention.expired(keep, end, keep))           // 기한과 같은 순간은 보관
        assertTrue(Retention.expired(keep, end, keep + 1))
        assertFalse(Retention.expired(keep, null, keep + 999))     // 진행 중인 운행은 지우지 않음
    }

    @Test fun retention_pinExtendsButNeverShortens() {
        val now = 10 * Retention.DAY
        assertEquals(now + 365 * Retention.DAY, Retention.pin(now + Retention.DAY, now))
        val far = now + 2_000 * Retention.DAY
        assertEquals(far, Retention.pin(far, now))
        assertEquals(now + Retention.MAX_DAYS * Retention.DAY, Retention.pin(0, now, 9_999))
        assertEquals(now + Retention.DAY, Retention.pin(0, now, 0))
    }

    @Test fun retention_daysLeftRoundsUp() {
        assertEquals(7, Retention.daysLeft(7 * Retention.DAY, 0))
        assertEquals(7, Retention.daysLeft(7 * Retention.DAY - 1, 0))
        assertEquals(1, Retention.daysLeft(1, 0))
        assertEquals(0, Retention.daysLeft(0, 0))
    }

    // ---------- 모델 ----------
    @Test fun csvRoundTrip_keepsNullsAndValues() {
        val s = Sample(123, 64.25f, 'D', true, 'R', -12.5f, true, null, false, 37.4127, 127.0947)
        val line = s.csv()
        assertEquals("123,64.3,D,1,R,-12.5,1,,0,37.412700,127.094700", line)   // 반올림 확인 (64.25 → 64.3, HALF_UP 아님 대비 값 선택)
        val back = Sample.parse(line)!!
        assertEquals(s.copy(spd = 64.3f), back)
        assertNull(Sample.parse("1,2,3"))
        assertNull(Sample.parse("x,1,D,1,R,0,1,0,0,,"))
        assertEquals(1, Sample.parseAll(Sample.HEADER + "\n" + line + "\n\n").size)
        assertEquals(Sample(5), Sample.parse("5,,,,,,,,,,"))
    }

    @Test fun sdkNameMapping() {
        assertEquals('P', Gear.fromSdk("GEAR_PARK")); assertEquals('D', Gear.fromSdk("GEAR_DRIVE"))
        assertEquals('R', Gear.fromSdk("GEAR_REVERSE")); assertEquals('N', Gear.fromSdk("GEAR_NEUTRAL"))
        assertNull(Gear.fromSdk(null)); assertEquals('?', Gear.fromSdk("GEAR_X"))
        assertEquals('L', Turn.fromSdk("LEFT")); assertEquals('R', Turn.fromSdk("RIGHT")); assertEquals('0', Turn.fromSdk("OFF"))
        assertNull(Turn.fromSdk(null)); assertEquals('0', Turn.fromSdk("HAZARD"))
    }

    // ---------- 변이 테스트 보강 ----------
    @Test fun eachChunkHoldsOnlyItsOwnRows() {
        val c = sealMinutes(2)
        val rows = Sample.parseAll(c[1].data)
        assertEquals(600, rows.size)
        assertEquals(60_000L, rows.first().t); assertEquals(119_900L, rows.last().t)
    }

    @Test fun genesisIsHashOfTripAndStart() {
        assertEquals(Hash.sha256("trip:7:0"), HashChain.genesis(7, 0))
        assertEquals(64, HashChain.genesis(7, 0).length)
        assertFalse(HashChain.genesis(7, 0) == HashChain.genesis(7, 1))
    }

    @Test fun validChunkReportsSigOkTrue() {
        val r = HashChain.verify(sealMinutes(1), fakeVerify).single()
        assertTrue(r.sigOk); assertTrue(r.linkOk); assertTrue(r.hashOk)
    }

    @Test fun daysLeftWithNonZeroNow() {
        val now = 100 * Retention.DAY
        assertEquals(7, Retention.daysLeft(now + 7 * Retention.DAY, now))
        assertEquals(1, Retention.daysLeft(now + 1, now))
        assertEquals(-2, Retention.daysLeft(now - 2 * Retention.DAY, now))
    }

    @Test fun parsedFieldsAreReadable() {
        val back = Sample.parse("123,64.3,D,1,R,-12.5,1,,0,37.412700,127.094700")!!
        assertEquals(37.4127, back.lat!!, 1e-9); assertEquals(127.0947, back.lon!!, 1e-9)
        assertEquals(true, back.low); assertNull(back.wiper); assertEquals(false, back.door)
        val other = Sample.parse("1,0.0,P,0,0,0.0,0,1,1,,")!!
        assertEquals(false, other.low); assertEquals(true, other.wiper)
    }
}
