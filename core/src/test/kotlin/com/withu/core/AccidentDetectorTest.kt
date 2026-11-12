package com.withu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccidentDetectorTest {
    private fun run(d: AccidentDetector, from: Long, to: Long, f: (Long) -> Sample): List<Decision> {
        val out = mutableListOf<Decision>()
        var t = from
        while (t <= to) { out += d.tick(f(t)); t += 100 }
        return out
    }

    /** 0~5초 64km/h, 5~6초 급제동 64→0, 그 뒤 정차 */
    private fun brakeToStop(t: Long, gearAfter: (Long) -> Char = { 'D' }, resumeAt: Long = Long.MAX_VALUE): Sample = when {
        t < 5_000 -> Sample(t, 64f, 'D', false)
        t < 6_000 -> Sample(t, 64f - (t - 5_000) / 1000f * 64f, 'D', true)
        t >= resumeAt -> Sample(t, 30f, 'D', false)
        else -> Sample(t, 0f, gearAfter(t), true)
    }

    @Test fun hardBrakeThenStopThenPark_asks() {
        val d = AccidentDetector()
        val out = run(d, 0, 20_000) { brakeToStop(it, { t -> if (t >= 12_000) 'P' else 'D' }) }
        val hb = out.filterIsInstance<Decision.HardBrake>().single()
        val sus = out.filterIsInstance<Decision.Suspect>().single()
        assertEquals(hb.t, sus.eventT)
        assertEquals(64f, sus.fromKph, 0.01f)
        assertTrue(sus.parkAfterStopMs in 5_000..7_000)
        assertTrue(out.indexOf(hb) < out.indexOf(out.filterIsInstance<Decision.Stopped>().single()))
    }

    @Test fun yellowLight_stopInDriveThenResume_doesNotAsk() {
        val d = AccidentDetector()
        val out = run(d, 0, 60_000) { brakeToStop(it, resumeAt = 40_000) }
        assertTrue(out.none { it is Decision.Suspect })
        val np = out.filterIsInstance<Decision.NoPrompt>().single()
        assertEquals(Decision.Reason.RESUMED_BEFORE_PARK, np.reason)
    }

    @Test fun hardBrakeWithoutStop_doesNotAsk() {
        val d = AccidentDetector()
        val out = run(d, 0, 25_000) { t -> when { t < 5_000 -> Sample(t, 64f, 'D', false); t < 6_000 -> Sample(t, 64f - (t - 5_000) / 1000f * 30f, 'D', true); else -> Sample(t, 34f, 'D', false) } }
        assertEquals(Decision.Reason.NO_STOP, out.filterIsInstance<Decision.NoPrompt>().single().reason)
        assertTrue(out.none { it is Decision.Suspect })
    }

    @Test fun parkTooLate_doesNotAsk() {
        val d = AccidentDetector(DetectorConfig(parkWithinMs = 20_000))
        val out = run(d, 0, 40_000) { brakeToStop(it, { t -> if (t >= 35_000) 'P' else 'D' }) }
        assertEquals(Decision.Reason.NO_PARK_IN_TIME, out.filterIsInstance<Decision.NoPrompt>().single().reason)
        assertTrue(out.none { it is Decision.Suspect })
    }

    @Test fun parkExactlyAtLimit_stillAsks() {
        val d = AccidentDetector(DetectorConfig(parkWithinMs = 20_000))
        val out = mutableListOf<Decision>()
        var stopT = -1L
        var t = 0L
        while (t <= 40_000) {
            val s = brakeToStop(t, { tt -> if (stopT >= 0 && tt >= stopT + 20_000) 'P' else 'D' })
            val r = d.tick(s); out += r
            r.filterIsInstance<Decision.Stopped>().firstOrNull()?.let { stopT = it.t }
            t += 100
        }
        assertEquals(20_000L, out.filterIsInstance<Decision.Suspect>().single().parkAfterStopMs)
    }

    @Test fun brakeOff_isNotHardBrake() {
        val d = AccidentDetector()
        val out = run(d, 0, 10_000) { t -> if (t < 5_000) Sample(t, 64f, 'D', false) else Sample(t, maxOf(0f, 64f - (t - 5_000) / 1000f * 64f), 'D', false) }
        assertTrue(out.isEmpty())
    }

    @Test fun dropBelowThreshold_isNotHardBrake() {
        val d = AccidentDetector()
        // 1초 동안 14km/h 감속은 급제동 아님
        val out = run(d, 0, 10_000) { t -> if (t < 5_000) Sample(t, 64f, 'D', true) else Sample(t, maxOf(0f, 64f - (t - 5_000) / 1000f * 14f), 'D', true) }
        assertTrue(out.none { it is Decision.HardBrake })
    }

    @Test fun dropExactlyThreshold_isHardBrake() {
        val d = AccidentDetector()
        val out = mutableListOf<Decision>()
        out += d.tick(Sample(0, 50f, 'D', false))
        out += d.tick(Sample(1_000, 35f, 'D', true))
        assertEquals(1, out.filterIsInstance<Decision.HardBrake>().size)
        assertTrue(d.armed)
    }

    @Test fun stopExactlyAtLimit_counts_andJustAfter_doesNot() {
        val a = AccidentDetector()
        a.tick(Sample(0, 60f, 'D', false)); a.tick(Sample(1_000, 40f, 'D', true))
        val atLimit = a.tick(Sample(11_000, 0f, 'D', true))
        assertTrue(atLimit.any { it is Decision.Stopped })
        val b = AccidentDetector()
        b.tick(Sample(0, 60f, 'D', false)); b.tick(Sample(1_000, 40f, 'D', true))
        val late = b.tick(Sample(11_001, 0f, 'D', true))
        assertEquals(Decision.Reason.NO_STOP, late.filterIsInstance<Decision.NoPrompt>().single().reason)
    }

    @Test fun cooldownBlocksImmediateSecondEvent() {
        val d = AccidentDetector()
        d.tick(Sample(0, 60f, 'D', false)); d.tick(Sample(1_000, 40f, 'D', true))
        d.tick(Sample(2_000, 0f, 'D', true))
        d.tick(Sample(2_100, 10f, 'D', false))          // 출발 → NoPrompt, 쿨다운 7_100까지
        d.tick(Sample(5_000, 60f, 'D', false))
        val blocked = d.tick(Sample(6_100, 40f, 'D', true))
        assertTrue(blocked.none { it is Decision.HardBrake })
        d.tick(Sample(7_000, 60f, 'D', false))
        val allowed = d.tick(Sample(8_000, 40f, 'D', true))
        assertTrue(allowed.any { it is Decision.HardBrake })
    }

    @Test fun resumeExactlyAtThreshold_isStillStopped() {
        val d = AccidentDetector()
        d.tick(Sample(0, 60f, 'D', false)); d.tick(Sample(1_000, 40f, 'D', true)); d.tick(Sample(2_000, 0f, 'D', true))
        assertTrue(d.tick(Sample(3_000, 5f, 'D', false)).isEmpty())
        assertTrue(d.armed)
        assertEquals(Decision.Reason.RESUMED_BEFORE_PARK, d.tick(Sample(3_100, 5.1f, 'D', false)).filterIsInstance<Decision.NoPrompt>().single().reason)
    }

    @Test fun nullSpeed_isIgnoredButAirbagStillFires() {
        val d = AccidentDetector()
        val out = d.tick(Sample(0, null, 'D', true), airbagDeployed = true)
        assertEquals(listOf(Decision.Airbag(0)), out)
        assertTrue(d.tick(Sample(100, null), airbagDeployed = true).isEmpty())   // 한 번만
        assertFalse(d.armed)
    }

    @Test fun stopSpeedBoundary() {
        val d = AccidentDetector()
        d.tick(Sample(0, 60f, 'D', false)); d.tick(Sample(1_000, 40f, 'D', true))
        assertTrue(d.tick(Sample(2_000, 0.5f, 'D', true)).none { it is Decision.Stopped })
        assertTrue(d.tick(Sample(2_100, 0.4f, 'D', true)).any { it is Decision.Stopped })
    }

    // ---------- 변이 테스트 보강 (PIT 생존 변이 대응) ----------
    /** evtT >= 0 경계: 시각 0에 급제동이 나도 판단이 이어져야 한다 */
    @Test fun eventAtTimeZero_staysArmed_andProceeds() {
        val d = AccidentDetector()
        d.tick(Sample(-1_000, 50f, 'D', false))
        assertTrue(d.tick(Sample(0, 35f, 'D', true)).single() is Decision.HardBrake)
        assertTrue(d.armed)
        assertEquals(Decision.Stopped(100, 100), d.tick(Sample(100, 0f, 'D', true)).single())
    }

    /** 1.5초 창 경계: 정확히 1.5초 전 샘플도 비교 대상으로 남는다 */
    @Test fun sparseSamples_exactly1500msApart_stillCompared() {
        val d = AccidentDetector()
        d.tick(Sample(0, 60f, 'D', false))
        assertEquals(Decision.HardBrake(1_500, 60f, 40f), d.tick(Sample(1_500, 40f, 'D', true)).single())
        val e = AccidentDetector()
        e.tick(Sample(0, 60f, 'D', false))
        assertTrue(e.tick(Sample(1_501, 40f, 'D', true)).isEmpty())
    }

    /** 쿨다운이 끝나는 바로 그 순간의 급제동은 잡는다 */
    @Test fun hardBrakeExactlyAtCooldownEnd_counts() {
        val d = AccidentDetector()
        d.tick(Sample(0, 60f, 'D', false)); d.tick(Sample(1_000, 40f, 'D', true)); d.tick(Sample(2_000, 0f, 'D', true))
        d.tick(Sample(2_100, 10f, 'D', false))          // 쿨다운 7_100까지
        d.tick(Sample(6_100, 60f, 'D', false))
        assertEquals(Decision.HardBrake(7_100, 60f, 40f), d.tick(Sample(7_100, 40f, 'D', true)).single())
    }

    /** 급제동이 없을 때도 에어백 판단은 돌려줘야 한다 */
    @Test fun airbagWithoutHardBrake_isReturned() {
        val d = AccidentDetector()
        assertEquals(listOf(Decision.Airbag(500)), d.tick(Sample(500, 70f, 'D', false), airbagDeployed = true))
    }

    /** 정차 대기 10초 경계: 정확히 10초에는 아직 판단하지 않는다 */
    @Test fun noStopDecision_onlyAfterLimit() {
        val d = AccidentDetector()
        d.tick(Sample(0, 60f, 'D', false)); d.tick(Sample(1_000, 40f, 'D', true))
        assertTrue(d.tick(Sample(11_000, 20f, 'D', false)).isEmpty())
        assertEquals(Decision.NoPrompt(1_000, Decision.Reason.NO_STOP), d.tick(Sample(11_100, 20f, 'D', false)).single())
    }

    /** P단 대기 경계: 정확히 제한 시간에 D단이면 아직 기다린다 */
    @Test fun noParkDecision_onlyAfterLimit() {
        val d = AccidentDetector(DetectorConfig(parkWithinMs = 20_000))
        d.tick(Sample(0, 60f, 'D', false)); d.tick(Sample(1_000, 40f, 'D', true)); d.tick(Sample(2_000, 0f, 'D', true))
        assertTrue(d.tick(Sample(22_000, 0f, 'D', true)).isEmpty())
        assertEquals(Decision.NoPrompt(1_000, Decision.Reason.NO_PARK_IN_TIME), d.tick(Sample(22_100, 0f, 'D', true)).single())
    }

    @Test fun decisionFieldsAreExposed() {
        val d = AccidentDetector()
        d.tick(Sample(0, 60f, 'D', false))
        val hb = d.tick(Sample(1_000, 40f, 'D', true)).single() as Decision.HardBrake
        assertEquals(60f, hb.fromKph, 0f); assertEquals(40f, hb.toKph, 0f); assertEquals(1_000L, hb.t)
        val st = d.tick(Sample(1_300, 0f, 'D', true)).single() as Decision.Stopped
        assertEquals(300L, st.afterMs)
        val np = d.tick(Sample(1_400, 9f, 'D', false)).single() as Decision.NoPrompt
        assertEquals(1_000L, np.eventT)
        assertEquals(42L, (AccidentDetector().tick(Sample(42, null), true).single() as Decision.Airbag).t)
    }
}
