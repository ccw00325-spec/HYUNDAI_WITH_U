package com.withu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 사실표 기준값의 경계(이상·초과, 창의 양 끝)를 고정한다 */
class FaultFactsBoundaryTest {
    private val T = 1_794_500_102_766L
    private fun at(d: Long, f: Sample.() -> Sample = { this }) = Sample(T + d).f()
    private fun rows(from: Long = -40_000, to: Long = 20_000, f: (Long) -> Sample): List<Sample> = (from / 100..to / 100).map { f(it * 100).copy(t = T + it * 100) }
    private fun List<FactorRow>.k(key: String) = first { it.key == key }

    @Test fun sunElevation_pinnedReferenceValues() {
        assertEquals(34.5452, Sun.elevationDeg(1_794_538_800_000, 37.3955, 127.1056), 0.01)    // 서울 11월 정오
        assertEquals(75.8446, Sun.elevationDeg(1_782_012_600_000, 37.5665, 126.978), 0.01)     // 서울 하지 정오
        assertEquals(-66.4887, Sun.elevationDeg(T, 37.3955, 127.1056), 0.01)                   // 새벽 1시
        assertEquals(61.9112, Sun.elevationDeg(1_767_225_600_000, -33.8688, 151.2093), 0.01)   // 남반구 시드니
        assertEquals(-7.7897, Sun.elevationDeg(1_794_560_400_000, 37.3955, 127.1056), 0.01)    // 해 진 직후
    }

    @Test fun highway_speedWindowAndThreshold() {
        fun sig(spdAt: (Long) -> Float) = FaultFactSheet.build(rows { d -> Sample(0, spd = spdAt(d), turn = if (d >= -8_000) 'R' else '0', steer = if (d in -5_000 until -2_000) 30f else 0f) }, T).k("signal").text
        assertTrue(sig { 80f }.contains("일반도로"))                                           // 80km/h는 아직 일반도로
        assertTrue(sig { d -> if (d == -30_000L) 81f else 60f }.contains("고속도로"))           // 창의 시작(−30초) 포함
        assertTrue(sig { d -> if (d == -30_100L) 120f else 60f }.contains("일반도로"))          // 창 밖은 제외
        assertTrue(sig { d -> if (d == 100L) 120f else 60f }.contains("일반도로"))              // 기준 시각 뒤는 제외
    }

    @Test fun maneuver_windowThresholdAndGap() {
        fun m(steer: (Long) -> Float) = FaultFactSheet.maneuver(rows { d -> Sample(0, spd = 30f, steer = steer(d)) }, T)
        assertEquals(T - 30_000, m { d -> if (d == -30_000L) 10f else 0f }!!.start)          // 창 시작 포함
        assertNull(m { d -> if (d == -30_100L) 10f else 0f })
        assertNull(m { d -> if (d == 1_100L) 10f else 0f })                                   // +1초 넘으면 제외
        assertEquals(T, m { d -> if (d in 0L..1_000L) 10f else 0f }!!.start)                   // 기준 시각에 시작하면 포함
        assertNull(m { d -> if (d in 100L..1_000L) 10f else 0f })                              // 기준 시각 뒤에 시작하면 제외
        assertEquals(T - 2_000, m { d -> if (d in -2_000L..-1_000L) 5f else 0f }!!.start)      // 5°부터 조향
        assertNull(m { d -> if (d in -2_000L..-1_000L) 4.9f else 0f })
        // 300ms 간격은 이어 보고, 그보다 벌어지면 새 구간
        val gap300 = FaultFactSheet.maneuver(listOf(at(-3_000) { copy(steer = 9f) }, at(-2_700) { copy(steer = 9f) }, at(-2_600) { copy(steer = 0f) }), T)!!
        assertEquals(T - 3_000, gap300.start); assertEquals(T - 2_600, gap300.end); assertEquals(9f, gap300.peakDeg)
        val gap400 = FaultFactSheet.maneuver(listOf(at(-3_000) { copy(steer = 9f) }, at(-2_600) { copy(steer = -12f) }, at(-2_500) { copy(steer = 0f) }), T)!!
        assertEquals(T - 2_600, gap400.start); assertEquals(12f, gap400.peakDeg)
    }

    @Test fun signal_exactLegalDistanceAndEdges() {
        // 36km/h = 10m/s, 조향 3초 전 점등 → 정확히 30m
        fun sig(turnFrom: Long, spd: Float = 36f, steerFrom: Long = -5_000) = FaultFactSheet.build(rows { d -> Sample(0, spd = spd, turn = if (d >= turnFrom) 'L' else '0', steer = if (d in steerFrom until -2_000) 20f else 0f) }, T).k("signal")
        val exact = sig(-8_000)
        assertEquals("조향 시작 3.0초 전(약 30m 앞) 왼쪽 점등 — 일반도로 법정 30m 이상", exact.text); assertEquals(Lean.FOR_DRIVER, exact.lean)
        assertEquals(Lean.AGAINST_DRIVER, sig(-7_900).lean)                                    // 29m
        val same = sig(-5_000)                                                                 // 조향과 동시에 점등
        assertEquals("조향 시작 0.0초 전(약 0m 앞) 왼쪽 점등 — 일반도로 법정 30m 미만", same.text)
        val edge = sig(-35_000, steerFrom = -5_000)                                            // 조향 30초 전(창의 끝) 점등
        assertTrue(edge.text, edge.text.startsWith("조향 시작 30.0초 전"))
        val outside = sig(-35_100, steerFrom = -5_000)
        assertTrue(outside.text, outside.text.contains("신호 없이"))
        // 조향 정보가 없을 때 문장
        val noSteer = FaultFactSheet.build(rows { d -> Sample(0, spd = 50f, turn = if (d >= -2_500) 'R' else '0') }, T).k("signal")
        assertEquals("조향 신호 미수신 · 기준 시각 2.5초 전 오른쪽 점등", noSteer.text)
        assertEquals("조향 신호 미수신 · 방향지시등 꺼짐", FaultFactSheet.build(rows { Sample(0, spd = 50f, turn = '0') }, T).k("signal").text)
    }

    @Test fun abrupt_exactDurations() {
        fun lean(from: Long, to: Long) = FaultFactSheet.build(rows { d -> Sample(0, spd = 50f, steer = if (d in from until to) 15f else 0f) }, T).k("abrupt_lane")
        val calm = lean(-3_000, -1_000); assertEquals(Lean.FOR_DRIVER, calm.lean); assertTrue(calm.text.startsWith("조향 2.0초 유지"))
        assertEquals(Lean.NEUTRAL, lean(-3_000, -1_100).lean)
        assertEquals(Lean.NEUTRAL, lean(-2_000, -1_000).lean)                                  // 정확히 1초는 급조향 아님
        assertEquals(Lean.AGAINST_DRIVER, lean(-2_000, -1_100).lean)
    }

    @Test fun speed_windowsAndLimitEdges() {
        fun sp(f: (Long) -> Sample) = FaultFactSheet.build(rows(f = f), T).k("speed")
        assertTrue(sp { d -> Sample(0, spd = if (d == -5_000L) 100f else 50f) }.text.contains("최고 100km/h"))    // −5초 포함
        assertTrue(sp { d -> Sample(0, spd = if (d == -5_100L || d > 0) 100f else 50f) }.text.contains("최고 50km/h"))
        assertEquals(Lean.FOR_DRIVER, sp { d -> Sample(0, spd = 60f, lim = 60) }.lean)                         // 제한속도와 같으면 이내
        assertEquals(Lean.AGAINST_DRIVER, sp { d -> Sample(0, spd = 70f, lim = 60) }.lean)                     // +10이면 불리
        assertEquals(Lean.NEUTRAL, sp { d -> Sample(0, spd = 69f, lim = 60) }.lean)
        assertEquals(Level.CONFIRMED, sp { d -> Sample(0, spd = 50f, lim = if (d == -60_000L) 60 else null) }.let { _ -> FaultFactSheet.build(rows(from = -70_000) { d -> Sample(0, spd = 50f, lim = if (d == -60_000L) 60 else null) }, T).k("speed") }.level)
        assertEquals(Level.PARTIAL, FaultFactSheet.build(rows(from = -70_000) { d -> Sample(0, spd = 50f, lim = if (d == -60_100L) 60 else null) }, T).k("speed").level)
        assertEquals(Level.PARTIAL, FaultFactSheet.build(rows { d -> Sample(0, spd = 50f, lim = if (d == 100L) 60 else null) }, T).k("speed").level)
    }

    @Test fun deceleration_pairSpacingAndWindow() {
        assertEquals(10.0, FaultFactSheet.maxDecel(listOf(at(-1_000) { copy(spd = 36f) }, at(0) { copy(spd = 0f) }), T - 5_000, T + 3_000)!!, 1e-9)
        assertEquals(10.0, FaultFactSheet.maxDecel(listOf(at(-500) { copy(spd = 18f) }, at(0) { copy(spd = 0f) }), T - 5_000, T + 3_000)!!, 1e-9)
        assertNull(FaultFactSheet.maxDecel(listOf(at(-400) { copy(spd = 18f) }, at(0) { copy(spd = 0f) }), T - 5_000, T + 3_000))
        assertEquals(5.0, FaultFactSheet.maxDecel(listOf(at(2_000) { copy(spd = 18f) }, at(3_000) { copy(spd = 0f) }), T - 5_000, T + 3_000)!!, 1e-9)   // 창 끝 포함
        assertNull(FaultFactSheet.maxDecel(listOf(at(2_100) { copy(spd = 18f) }, at(3_100) { copy(spd = 0f) }), T - 5_000, T + 3_000))
        assertEquals(5.0, FaultFactSheet.maxDecel(listOf(at(-6_000) { copy(spd = 30f) }, at(-5_000) { copy(spd = 12f) }), T - 5_000, T + 3_000)!!, 1e-9)  // 창 시작 포함
        // 더 큰 감속을 고른다
        assertEquals(10.0, FaultFactSheet.maxDecel(listOf(at(-3_000) { copy(spd = 36f) }, at(-2_000) { copy(spd = 0f) }, at(-1_000) { copy(spd = 0f) }, at(0) { copy(spd = 0f) }), T - 5_000, T + 3_000)!!, 1e-9)
    }

    @Test fun brake_windowEdgesAndTinyDecel() {
        fun br(onAt: Long, spd: (Long) -> Float = { 40f }) = FaultFactSheet.build(rows { d -> Sample(0, spd = spd(d), brk = d >= onAt) }, T).k("hard_brake").text
        assertEquals("기준 시각 10.0초 전 제동 시작", br(-10_000))
        assertEquals("기준 시각 2.0초 뒤 제동 시작", br(2_000))
        assertEquals("기준 시각 0.0초 전 제동 시작", br(0))
        assertEquals("기준 시각 −10초~+2초 제동 기록 없음", br(2_100))
        // 0.5m/s² 이하의 작은 감속은 표시하지 않는다
        assertEquals("기준 시각 0.0초 전 제동 시작", br(0) { d -> if (d < 0) 10f else if (d < 1_000) 10f - d / 1_000f * 1.7f else 8.3f })
        assertTrue(br(0) { d -> if (d < 0) 10f else if (d < 1_000) 10f - d / 1_000f * 2f else 8f }.contains("최대 감속 0.6m/s²(0.06g)"))
    }

    @Test fun stoppedAndDoor_speedEdges() {
        val r = FaultFactSheet.build(rows { d -> Sample(0, spd = if (d < -1_000) 20f else 0.5f, gear = 'D', door = false) }, T)
        assertEquals("기준 시각 1.0초 전부터 정지 · D단", r.k("stopped_hit").text); assertEquals(Lean.FOR_DRIVER, r.k("stopped_hit").lean)
        assertEquals(Level.CONFIRMED, r.k("door_open").level)
        val moving = FaultFactSheet.build(rows { Sample(0, spd = 0.6f, door = false) }, T)
        assertEquals(Level.NA, moving.k("stopped_hit").level); assertEquals(Level.NA, moving.k("door_open").level)
        val fromStart = FaultFactSheet.build(rows(from = -5_000) { Sample(0, spd = 0f, gear = 'P') }, T).k("stopped_hit")
        assertEquals("기록 시작(5.0초 전)부터 정지 · P단", fromStart.text)
        val noGear = FaultFactSheet.build(rows { d -> Sample(0, spd = if (d < -3_000) 20f else 0f) }, T).k("stopped_hit")
        assertEquals("기준 시각 3.0초 전부터 정지", noGear.text)
        val openAll = FaultFactSheet.build(rows { Sample(0, spd = 0f, door = true) }, T).k("door_open")
        assertEquals("정차 중 운전석 문 열림", openAll.text)
    }

    @Test fun night_usesLocationBeforeAnchorOnly() {
        val later = FaultFactSheet.build(rows { d -> Sample(0, spd = 30f, low = true, lat = if (d > 0) 37.4 else null, lon = if (d > 0) 127.1 else null) }, T).k("night_light")
        assertEquals(Level.PARTIAL, later.level)
        val atT = FaultFactSheet.build(rows { d -> Sample(0, spd = 30f, low = true, lat = if (d == 0L) 37.4 else null, lon = if (d == 0L) 127.1 else null) }, T).k("night_light")
        assertEquals(Level.CONFIRMED, atT.level)
        val dayOff = FaultFactSheet.build(rows { Sample(0, spd = 30f, low = false, lat = 37.4, lon = 127.1) }.map { it.copy(t = it.t - T + 1_794_538_800_000) }, 1_794_538_800_000).k("night_light")
        assertEquals("주간(태양 고도 35°) · 전조등 꺼짐", dayOff.text)
    }

    @Test fun unknownWhenOnlySomeSignalsMissing() {
        val r = FaultFactSheet.build(rows { Sample(0, spd = 40f, gear = 'D') }, T)
        assertEquals(Level.UNKNOWN, r.k("signal").level); assertEquals(Level.UNKNOWN, r.k("abrupt_lane").level)
        assertEquals(Level.UNKNOWN, r.k("hard_brake").level); assertEquals(Level.UNKNOWN, r.k("door_open").level); assertEquals(Level.UNKNOWN, r.k("night_light").level)
        assertEquals(Level.PARTIAL, r.k("speed").level); assertEquals(Level.NA, r.k("stopped_hit").level)
        // 입력 순서가 섞여 있어도 시간순으로 본다
        val shuffled = rows { d -> Sample(0, spd = 36f, turn = if (d >= -8_000) 'L' else '0', steer = if (d in -5_000 until -2_000) 20f else 0f) }.reversed()
        assertEquals(Lean.FOR_DRIVER, FaultFactSheet.build(shuffled, T).k("signal").lean)
    }
}
