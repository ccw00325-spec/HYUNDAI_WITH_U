package com.withu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 급발진·페달 오조작 소명: 브레이크를 밟은 채 빨라졌는지, 크루즈·경고등 상태는 어땠는지 */
class PedalFactTest {
    private val T = 1_794_500_102_766L
    private fun rows(from: Long = -40_000, to: Long = 10_000, f: (Long) -> Sample) = (from / 100..to / 100).map { f(it * 100).copy(t = T + it * 100) }
    private fun pedal(r: List<Sample>) = FaultFactSheet.build(r, T).first { it.key == "pedal" }

    @Test fun brakePressedWhileSpeedRises_isConfirmedForDriver() {
        // −6초부터 브레이크를 밟았는데 −5초~−2.6초 동안 31 → 49km/h
        val r = rows { d -> Sample(0, spd = when { d < -5_000 -> 31f; d < -2_600 -> 31f + (d + 5_000) / 2_400f * 18f; else -> 49f }, brk = d >= -6_000, cc = '0', tt = "") }
        val p = pedal(r)
        assertEquals(Level.CONFIRMED, p.level); assertEquals(Lean.FOR_DRIVER, p.lean)
        assertEquals("브레이크를 밟은 채 2.4초 동안 속도 +18km/h(31→49) — 운전자 의도와 다른 가속 정황 · 크루즈 꺼짐 · 경고등 없음", p.text)
        assertEquals(T - 5_000, p.markT)
        // 웹 리포트(JS) 계산 대조용
        val nl = "\n"
        java.io.File("build/pedal_case.txt").writeText(T.toString() + nl + p.text + nl + Sample.HEADER + nl + r.joinToString(nl) { it.csv() })
    }

    @Test fun contextShowsCruiseAndTelltales() {
        val r = rows { d -> Sample(0, spd = if (d < -3_000) 40f else 40f + (d + 3_000) / 1_000f * 4f, brk = d >= -3_000, cc = if (d >= -3_000) 'A' else 'E', tt = if (d >= -2_000) "FE" else "") }
        val p = pedal(r)
        assertTrue(p.text, p.text.endsWith("· 크루즈 작동 · 경고등 FCA(전방 충돌방지)·ESC"))
    }

    @Test fun smallRiseOrShortSpan_isNotEnough() {
        assertEquals(Level.NA, pedal(rows { d -> Sample(0, spd = if (d < -3_000) 40f else 42.9f, brk = d >= -4_000) }).level)          // +2.9km/h
        assertEquals(Level.CONFIRMED, pedal(rows { d -> Sample(0, spd = if (d < -3_000) 40f else 43f, brk = d >= -4_000) }).level)     // +3km/h
        // 0.9초 안의 상승은 세지 않는다
        assertNull(FaultFactSheet.brakeRise(listOf(Sample(T - 900, spd = 10f, brk = true), Sample(T, spd = 30f, brk = true)), T - 30_000, T))
        val ok = FaultFactSheet.brakeRise(listOf(Sample(T - 1_000, spd = 10f, brk = true), Sample(T, spd = 30f, brk = true)), T - 30_000, T)!!
        assertEquals(20f, ok.third)
        // 브레이크가 끊기면 다른 구간
        assertNull(FaultFactSheet.brakeRise(listOf(Sample(T - 3_000, spd = 10f, brk = true), Sample(T - 2_000, spd = 20f, brk = false), Sample(T - 1_000, spd = 30f, brk = true)), T - 30_000, T))
        // 가장 느린 순간부터 잰다
        val lo = FaultFactSheet.brakeRise(listOf(Sample(T - 4_000, spd = 30f, brk = true), Sample(T - 3_000, spd = 20f, brk = true), Sample(T - 1_000, spd = 35f, brk = true)), T - 30_000, T)!!
        assertEquals(15f, lo.third); assertEquals(T - 3_000, lo.first.t)
        // 창 밖(−30초 이전)은 보지 않는다
        assertNull(FaultFactSheet.brakeRise(listOf(Sample(T - 32_000, spd = 10f, brk = true), Sample(T - 31_000, spd = 30f, brk = true)), T - 30_000, T))
    }

    @Test fun accelerationWithoutBrake_isPartialAndAsksForPedalSignal() {
        val p = pedal(rows { d -> Sample(0, spd = if (d < -8_000) 20f else minOf(60f, 20f + (d + 8_000) / 1_000f * 8f), brk = false) })
        assertEquals(Level.PARTIAL, p.level); assertEquals(Lean.NEUTRAL, p.lean)
        assertTrue(p.text, p.text.startsWith("기준 시각 전 10초 가속(+40km/h) 중 브레이크 기록 없음 · 가속 페달 개도는 SDK 미제공(기술 지원 검토 요청)"))
        // 기준 시각 1초 전부터의 제동(충돌 직전 반응)은 '가속 중 브레이크'로 보지 않는다
        val late = pedal(rows { d -> Sample(0, spd = if (d < -8_000) 20f else minOf(60f, 20f + (d + 8_000) / 1_000f * 8f), brk = d >= -1_000) })
        assertEquals(Level.PARTIAL, late.level)
        val early = pedal(rows { d -> Sample(0, spd = if (d < -8_000) 20f else minOf(60f, 20f + (d + 8_000) / 1_000f * 8f), brk = d in -3_000 until -2_000) })
        assertEquals(Level.NA, early.level)
        assertEquals(Level.NA, pedal(rows { Sample(0, spd = 50f, brk = false) }).level)
    }

    @Test fun noBrakeSignal_isUnknown() {
        assertEquals(Level.UNKNOWN, pedal(rows { Sample(0, spd = 50f) }).level)
        assertEquals("브레이크를 밟은 채 빨라진 구간 없음", pedal(rows { Sample(0, spd = 50f, brk = false) }).text)
    }

    @Test fun brakeRise_windowEdgesZeroRiseAndTies() {
        val from = T - 30_000
        // 창의 시작·끝 샘플도 포함
        assertEquals(10f, FaultFactSheet.brakeRise(listOf(Sample(from, spd = 10f, brk = true), Sample(from + 1_000, spd = 20f, brk = true)), from, T)!!.third)
        assertEquals(10f, FaultFactSheet.brakeRise(listOf(Sample(T - 1_000, spd = 10f, brk = true), Sample(T, spd = 20f, brk = true)), from, T)!!.third)
        assertNull(FaultFactSheet.brakeRise(listOf(Sample(T - 1_000, spd = 10f, brk = true), Sample(T + 100, spd = 20f, brk = true)), from, T))
        // 속도가 그대로면 구간 없음
        assertNull(FaultFactSheet.brakeRise(listOf(Sample(T - 2_000, spd = 10f, brk = true), Sample(T, spd = 10f, brk = true)), from, T))
        // 같은 상승이면 먼저 나온 구간을 유지
        val tie = FaultFactSheet.brakeRise(listOf(Sample(T - 9_000, spd = 10f, brk = true), Sample(T - 8_000, spd = 15f, brk = true), Sample(T - 7_000, spd = 15f, brk = false),
            Sample(T - 3_000, spd = 30f, brk = true), Sample(T - 2_000, spd = 35f, brk = true)), from, T)!!
        assertEquals(T - 9_000, tie.first.t)
    }

    @Test fun partialAndNa_includeContextFromTheirWindows() {
        fun accel(d: Long) = if (d < -8_000) 20f else minOf(60f, 20f + (d + 8_000) / 1_000f * 8f)
        // 가속 구간(−10초~0) 문맥: −12초의 크루즈 작동은 창 밖
        val p = pedal(rows { d -> Sample(0, spd = accel(d), brk = false, cc = if (d == -12_000L) 'A' else '0', tt = if (d == 0L) "A" else "") })
        assertTrue(p.text, p.text.endsWith("· 크루즈 꺼짐 · 경고등 ABS"))
        // 해당 없음(−30초~0) 문맥: −25초 크루즈 작동은 창 안
        val na = pedal(rows { d -> Sample(0, spd = 50f, brk = false, cc = if (d == -25_000L) 'A' else '0', tt = "") })
        assertEquals("브레이크를 밟은 채 빨라진 구간 없음 · 크루즈 작동 · 경고등 없음", na.text)
        val naOut = pedal(rows { d -> Sample(0, spd = 50f, brk = false, cc = if (d == -31_000L) 'A' else null) })
        assertEquals("브레이크를 밟은 채 빨라진 구간 없음", naOut.text)
        // 크루즈 대기(E)는 작동이 아니다
        assertEquals("브레이크를 밟은 채 빨라진 구간 없음 · 크루즈 대기(작동 안 함)", pedal(rows { Sample(0, spd = 50f, brk = false, cc = 'E') }).text)
        assertEquals("브레이크를 밟은 채 빨라진 구간 없음 · 크루즈 대기(작동 안 함)", pedal(rows { Sample(0, spd = 50f, brk = false, cc = 'S') }).text)
        assertEquals("브레이크를 밟은 채 빨라진 구간 없음 · 크루즈 대기(작동 안 함)", pedal(rows { Sample(0, spd = 50f, brk = false, cc = 'O') }).text)                        // 창 밖 크루즈 신호만 있으면 문맥 없음
    }

    @Test fun partial_gainAndLateBrakeBoundaries() {
        // 정확히 +10km/h면 '가속 중 브레이크 없음'
        val ten = pedal(rows { d -> Sample(0, spd = if (d < -5_000) 40f else 50f, brk = false) })
        assertEquals(Level.PARTIAL, ten.level)
        assertEquals(Level.NA, pedal(rows { d -> Sample(0, spd = if (d < -5_000) 40f else 49.9f, brk = false) }).level)
        // 기준 시각 1초 전 정확히 시작한 제동은 직전 반응으로 보고 제외, 그보다 이르면 포함
        fun accel(d: Long) = if (d < -8_000) 20f else minOf(60f, 20f + (d + 8_000) / 1_000f * 8f)
        assertEquals(Level.PARTIAL, pedal(rows { d -> Sample(0, spd = accel(d), brk = d >= -1_000) }).level)
        assertEquals(Level.NA, pedal(rows { d -> Sample(0, spd = accel(d), brk = d in -1_100 until -1_000) }).level)
        // −10초 창의 시작(최저 속도가 정확히 −10초)도 포함
        val edge = pedal(rows { d -> Sample(0, spd = if (d <= -10_000) 20f else 35f, brk = false) })
        assertEquals(Level.PARTIAL, edge.level); assertEquals(T - 10_000, edge.markT)
        val outside = pedal(rows { d -> Sample(0, spd = if (d <= -10_100) 20f else 35f, brk = false) })
        assertEquals(Level.NA, outside.level)
    }

    @Test fun contextIncludesSegmentEdges_andPartialHandlesMissingSpeeds() {
        // 크루즈는 구간 시작 순간에만, 경고등은 구간 끝 순간에만 켜져도 문맥에 들어간다
        val r = rows { d -> Sample(0, spd = when { d < -4_000 -> 30f; d <= -2_000 -> 30f + (d + 4_000) / 2_000f * 10f; else -> 40f }, brk = d in -4_000..-2_000,
            cc = if (d == -4_000L) 'A' else '0', tt = if (d == -2_000L) "F" else "") }
        val p = pedal(r)
        assertEquals(Level.CONFIRMED, p.level)
        assertTrue(p.text, p.text.endsWith("· 크루즈 작동 · 경고등 FCA(전방 충돌방지)"))
        // 최고 속도가 정확히 기준 시각에 나와도 가속으로 센다, 속도가 빠진 샘플은 건너뛴다
        val atT = pedal(rows { d -> Sample(0, spd = if (d % 1_000 == 500L) null else if (d < 0) 40f else 55f, brk = false) })
        assertEquals(Level.PARTIAL, atT.level); assertTrue(atT.text, atT.text.startsWith("기준 시각 전 10초 가속(+15km/h)"))
    }
}
