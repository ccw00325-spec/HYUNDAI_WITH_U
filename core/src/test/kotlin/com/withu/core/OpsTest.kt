package com.withu.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpsTest {
    @After fun restore() { LogPrivacy.redact = true }

    // ---------- 사이렌 기준 시각 ----------
    private val brakeAt = 1_000_000L

    @Test fun siren_withoutAnyHardBrake_usesNowAndCurrentSpeed() {
        val r = SirenAnchor.resolve(now = 5_000_000, speedNowKph = 12f, lastBrakeAt = 0, lastBrakeFromKph = 0f, lastMovingAt = 4_900_000)
        assertEquals(SirenAnchor.Result(5_000_000, 12f, false), r)
    }

    @Test fun siren_sameStopWithinThreeMinutes_usesBrakeTimeAndPreBrakeSpeed() {
        val r = SirenAnchor.resolve(now = brakeAt + 120_000, speedNowKph = 0f, lastBrakeAt = brakeAt, lastBrakeFromKph = 95f, lastMovingAt = brakeAt + 1_000)
        assertEquals(SirenAnchor.Result(brakeAt, 95f, true), r)
        assertTrue(RoadContext.isHighway(r.fromKph, null))
    }

    @Test fun siren_exactlyThreeMinutesAfterBrake_isNoLongerSameStop() {
        val r = SirenAnchor.resolve(now = brakeAt + 180_000, speedNowKph = 0f, lastBrakeAt = brakeAt, lastBrakeFromKph = 95f, lastMovingAt = brakeAt)
        assertFalse(r.sameStop); assertEquals(brakeAt + 180_000, r.t); assertEquals(0f, r.fromKph)
    }

    @Test fun siren_justBeforeThreeMinutes_isStillSameStop() {
        assertTrue(SirenAnchor.resolve(brakeAt + 179_999, 0f, brakeAt, 95f, brakeAt).sameStop)
    }

    @Test fun siren_noBrakeRecordedYet_isNeverSameStop_evenRightAfterBoot() {
        // 부팅 직후(now가 작을 때)에도 급제동 기록이 없으면(0) 같은 정차로 보지 않는다
        val r = SirenAnchor.resolve(now = 100_000, speedNowKph = 0f, lastBrakeAt = 0, lastBrakeFromKph = 0f, lastMovingAt = 0)
        assertFalse(r.sameStop); assertEquals(100_000L, r.t)
    }

    @Test fun siren_afterDrivingAgain_isNotSameStop() {
        // 급제동 뒤 15초가 지나서 다시 5km/h를 넘었으면 다른 정차다
        val r = SirenAnchor.resolve(brakeAt + 60_000, 0f, brakeAt, 95f, lastMovingAt = brakeAt + 15_000)
        assertFalse(r.sameStop); assertEquals(brakeAt + 60_000, r.t)
        assertTrue(SirenAnchor.resolve(brakeAt + 60_000, 0f, brakeAt, 95f, lastMovingAt = brakeAt + 14_999).sameStop)
    }

    @Test fun siren_sameStopKeepsFasterCurrentSpeed_andNullSpeedCountsAsZero() {
        assertEquals(30f, SirenAnchor.resolve(brakeAt + 1_000, 30f, brakeAt, 20f, brakeAt).fromKph)
        assertEquals(0f, SirenAnchor.resolve(5_000_000, null, 0, 0f, 0).fromKph)
    }

    // ---------- 로그 개인정보 가림 ----------
    @Test fun logPrivacy_isOnByDefault_andToggleIsReadable() {
        assertTrue(LogPrivacy.redact)
        LogPrivacy.redact = false; assertFalse(LogPrivacy.redact)
        LogPrivacy.redact = true; assertTrue(LogPrivacy.redact)
    }

    @Test fun logPrivacy_redactsLocationAndText_byDefault() {
        LogPrivacy.redact = true
        assertEquals(LogPrivacy.MASK, LogPrivacy.loc(37.49791, 127.02763))
        assertEquals("${LogPrivacy.MASK}(6자)", LogPrivacy.text("수정구 시흥"))
        assertFalse(LogPrivacy.text("깜빡이를 켰는데").contains("깜빡이"))
    }

    @Test fun logPrivacy_showsValues_whenVerboseForTesting() {
        LogPrivacy.redact = false
        assertEquals("37.49791,127.02763", LogPrivacy.loc(37.497912, 127.027634))
        assertEquals("수정구 시흥동", LogPrivacy.text("수정구 시흥동"))
    }

    @Test fun logPrivacy_missingValuesAreDash_inBothModes() {
        for (r in listOf(true, false)) {
            LogPrivacy.redact = r
            assertEquals("-", LogPrivacy.loc(null, 127.0)); assertEquals("-", LogPrivacy.loc(37.0, null)); assertEquals("-", LogPrivacy.text(null))
        }
    }

    // ---------- 보관 정리 주기 ----------
    @Test fun sweep_dueExactlyAfterOneHour_notBefore() {
        assertFalse(SweepSchedule.due(0, SweepSchedule.INTERVAL_MS - 1))
        assertTrue(SweepSchedule.due(0, SweepSchedule.INTERVAL_MS))
        assertEquals(3_600_000L, SweepSchedule.INTERVAL_MS)
        val ten = 36_000_000L
        assertFalse(SweepSchedule.due(ten, ten + 3_599_999)); assertTrue(SweepSchedule.due(ten, ten + 3_600_000))
    }
}
