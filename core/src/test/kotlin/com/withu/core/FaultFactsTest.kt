package com.withu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FaultFactsTest {
    private val T = 1_794_500_102_766L               // 2026-11-13 01:15 KST (야간)
    private val NOON = 1_794_538_800_000L            // 2026-11-13 12:00 KST (주간)
    private val LAT = 37.3955; private val LON = 127.1056

    /** t-40초 ~ t+20초를 10Hz로 만든다. f(상대 ms) → 그 시점 샘플 */
    private fun run(t: Long = T, f: (Long) -> Sample): List<Sample> = (-400..200).map { k -> f(k * 100L).copy(t = t + k * 100L) }

    private fun drive(spd: Float = 64f, turnFrom: Long? = null, side: Char = 'R', steerFrom: Long? = null, steerTo: Long? = null, brakeFrom: Long? = null,
                      low: Boolean = true, lim: Int? = null, address: String? = null, t: Long = T): List<FactorRow> {
        val rows = run(t) { d -> Sample(0, spd = spd, gear = 'D', brk = brakeFrom != null && d >= brakeFrom,
            turn = if (turnFrom != null && d >= turnFrom) side else '0',
            steer = if (steerFrom != null && d >= steerFrom && d < (steerTo ?: Long.MAX_VALUE)) 35f else 0f,
            low = low, wiper = false, door = false, lat = LAT, lon = LON, lim = lim) }
        return FaultFactSheet.build(rows, t, address)
    }
    private fun List<FactorRow>.k(key: String) = first { it.key == key }

    @Test fun rowsFollowCriteriaOrder() {
        assertEquals(FaultFactSheet.KEYS, drive().map { it.key })
    }

    @Test fun signalBeforeSteering_farEnough_supportsDriver() {
        val r = drive(turnFrom = -8_400, steerFrom = -5_000, steerTo = -2_000, brakeFrom = -1_300)
        val s = r.k("signal")
        assertEquals(Level.CONFIRMED, s.level); assertEquals(Lean.FOR_DRIVER, s.lean)
        assertTrue(s.text, s.text.startsWith("조향 시작 3.4초 전(약 60m 앞) 오른쪽 점등"))
        assertTrue(s.text.contains("일반도로 법정 30m 이상"))
        assertEquals(T - 8_400, s.markT)
        val a = r.k("abrupt_lane")
        assertEquals(Level.PARTIAL, a.level); assertEquals(Lean.FOR_DRIVER, a.lean)
        assertTrue(a.text, a.text.startsWith("조향 3.0초 유지(최대 35°, 완만)"))
        val b = r.k("hard_brake")
        assertEquals(Level.CONFIRMED, b.level); assertTrue(b.text, b.text.startsWith("기준 시각 1.3초 전 제동 시작"))
        assertEquals(Level.NA, r.k("stopped_hit").level)
        assertEquals(Level.NA, r.k("door_open").level)
    }

    @Test fun noSignal_lateSignal_signalAfterSteering_areAgainstDriver() {
        val none = drive(steerFrom = -5_000, steerTo = -2_000).k("signal")
        assertEquals(Lean.AGAINST_DRIVER, none.lean); assertTrue(none.text.contains("신호 없이"))
        val late = drive(turnFrom = -6_000, steerFrom = -5_000, steerTo = -2_000).k("signal")      // 1초 · 약 18m
        assertEquals(Lean.AGAINST_DRIVER, late.lean); assertTrue(late.text, late.text.contains("약 18m 앞") && late.text.contains("30m 미만"))
        val after = drive(turnFrom = -4_200, steerFrom = -5_000, steerTo = -2_000).k("signal")
        assertEquals(Lean.AGAINST_DRIVER, after.lean); assertTrue(after.text, after.text.startsWith("조향 시작 0.8초 뒤 오른쪽 점등"))
    }

    @Test fun highwayNeeds100m() {
        val ok = drive(spd = 100f, turnFrom = -10_000, steerFrom = -5_000, steerTo = -2_000, address = "경부고속도로 서울방향").k("signal")
        assertEquals(Lean.FOR_DRIVER, ok.lean); assertTrue(ok.text, ok.text.contains("고속도로 법정 100m 이상"))
        val short = drive(spd = 100f, turnFrom = -8_000, steerFrom = -5_000, steerTo = -2_000, address = "경부고속도로 서울방향").k("signal")
        assertEquals(Lean.AGAINST_DRIVER, short.lean); assertTrue(short.text, short.text.contains("약 83m 앞") && short.text.contains("100m 미만"))
        // 주소가 없어도 직전 30초 80km/h 초과면 고속도로로 본다
        assertTrue(drive(spd = 100f, turnFrom = -10_000, steerFrom = -5_000, steerTo = -2_000).k("signal").text.contains("고속도로"))
    }

    @Test fun noSteering_isNotLaneChange() {
        val r = drive(turnFrom = -3_000)
        assertEquals(Level.NA, r.k("signal").level); assertTrue(r.k("signal").text.contains("오른쪽 점등 3.0초 전"))
        assertEquals(Level.NA, r.k("abrupt_lane").level)
    }

    @Test fun steeringDuration_classifiesAbrupt() {
        assertEquals(Lean.AGAINST_DRIVER, drive(steerFrom = -2_000, steerTo = -1_400).k("abrupt_lane").lean)
        assertEquals(Lean.NEUTRAL, drive(steerFrom = -2_500, steerTo = -1_000).k("abrupt_lane").lean)
        // 마지막 조향 구간을 쓴다
        val rows = run { d -> Sample(0, spd = 60f, steer = if (d in -20_000 until -18_000 || d in -3_000 until -2_500) 20f else 0f) }
        val m = FaultFactSheet.maneuver(rows, T)!!
        assertEquals(T - 3_000, m.start); assertEquals(T - 2_500, m.end)
    }

    @Test fun speed_withAndWithoutLimit() {
        val noLim = drive(spd = 52f).k("speed")
        assertEquals(Level.PARTIAL, noLim.level); assertTrue(noLim.text.contains("평균 52km/h") && noLim.text.contains("제한속도 미수신"))
        val within = drive(spd = 52f, lim = 60).k("speed")
        assertEquals(Level.CONFIRMED, within.level); assertEquals(Lean.FOR_DRIVER, within.lean); assertTrue(within.text.contains("제한 60km/h 이내"))
        val over = drive(spd = 75f, lim = 60).k("speed")
        assertEquals(Lean.AGAINST_DRIVER, over.lean); assertTrue(over.text.contains("15km/h 빠름"))
        assertEquals(Lean.NEUTRAL, drive(spd = 65f, lim = 60).k("speed").lean)
    }

    @Test fun hardBrake_reportsOnsetAndDeceleration() {
        val rows = run { d -> Sample(0, spd = if (d < -1_000) 64f else if (d < 0) 64f - (d + 1_000) / 1_000f * 20f else 44f, brk = d >= -1_300) }
        val b = FaultFactSheet.build(rows, T).first { it.key == "hard_brake" }
        assertTrue(b.text, b.text.startsWith("기준 시각 1.3초 전 제동 시작 · 최대 감속 5.6m/s²(0.57g)"))
        val none = drive().k("hard_brake")
        assertEquals(Level.CONFIRMED, none.level); assertTrue(none.text.contains("제동 기록 없음"))
        assertNull(FaultFactSheet.maxDecel(emptyList(), 0, 1))
    }

    @Test fun stoppedWhenHit_supportsDriver() {
        val rows = run { d -> Sample(0, spd = if (d < -8_000) 20f else 0f, gear = 'D', brk = true, door = false, low = true, lat = LAT, lon = LON) }
        val r = FaultFactSheet.build(rows, T)
        val s = r.k("stopped_hit")
        assertEquals(Level.CONFIRMED, s.level); assertEquals(Lean.FOR_DRIVER, s.lean)
        assertEquals("기준 시각 8.0초 전부터 정지 · D단", s.text)
        val d = r.k("door_open")
        assertEquals(Lean.FOR_DRIVER, d.lean); assertEquals("정차 중 운전석 문 닫혀 있음", d.text)
        val justStopped = FaultFactSheet.build(run { d2 -> Sample(0, spd = if (d2 < -300) 20f else 0f) }, T).k("stopped_hit")
        assertEquals(Lean.NEUTRAL, justStopped.lean)
    }

    @Test fun doorOpenWhileStopped_isAgainstDriver_andExitAfterIsNoted() {
        val open = FaultFactSheet.build(run { d -> Sample(0, spd = 0f, gear = 'P', door = d >= -3_000) }, T).k("door_open")
        assertEquals(Lean.AGAINST_DRIVER, open.lean); assertEquals("정차 중 운전석 문 열림(기준 시각 3.0초 전부터)", open.text)
        val exit = FaultFactSheet.build(run { d -> Sample(0, spd = if (d < 3_000) 40f else 0f, door = d >= 10_000) }, T).k("door_open")
        assertEquals("주행 중 · 운전석 문 닫힘 · 사고 10.0초 뒤 열림(하차)", exit.text)
    }

    @Test fun headlight_nightAndDay() {
        val on = drive().k("night_light")
        assertEquals(Level.CONFIRMED, on.level); assertEquals(Lean.FOR_DRIVER, on.lean); assertTrue(on.text, on.text.startsWith("일몰 후"))
        val off = drive(low = false).k("night_light")
        assertEquals(Lean.AGAINST_DRIVER, off.lean); assertTrue(off.text.endsWith("전조등 꺼짐"))
        val day = drive(t = NOON).k("night_light")
        assertEquals(Level.NA, day.level); assertTrue(day.text, day.text.startsWith("주간"))
        val noLoc = FaultFactSheet.build(run { Sample(0, spd = 30f, low = true) }, T).k("night_light")
        assertEquals(Level.PARTIAL, noLoc.level)
    }

    @Test fun missingSignals_areUnknownNotGuessed() {
        val r = FaultFactSheet.build(run { Sample(0) }, T)
        assertTrue(r.all { it.level == Level.UNKNOWN && it.lean == Lean.NEUTRAL })
        val noSteer = FaultFactSheet.build(run { d -> Sample(0, spd = 50f, turn = if (d >= -2_000) 'L' else '0') }, T).k("signal")
        assertEquals(Level.PARTIAL, noSteer.level); assertTrue(noSteer.text, noSteer.text.contains("왼쪽 점등"))
    }

    @Test fun sunElevation_seoul() {
        val noon = Sun.elevationDeg(NOON, LAT, LON)
        assertTrue("$noon", noon in 30.0..40.0)                 // 11월 서울 정오 약 35°
        assertTrue(Sun.elevationDeg(T, LAT, LON) < -40)          // 새벽 1시
        assertNotNull(drive().k("night_light").markT ?: 0L)
    }
}
