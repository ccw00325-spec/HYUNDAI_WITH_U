package com.withu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class GuideAndReportTest {
    // ---------- 대응 안내 ----------
    @Test fun highwayPutsEvacuationFirst_generalRoadChecksFirst() {
        assertEquals(listOf(GuideStep.EVACUATE, GuideStep.HAZARD, GuideStep.TRUNK, GuideStep.REPORT), RoadContext.steps(true))
        assertEquals(listOf(GuideStep.HAZARD, GuideStep.CHECK, GuideStep.EVACUATE, GuideStep.REPORT), RoadContext.steps(false))
    }

    @Test fun highwayDetection_speedBoundaryAndAddress() {
        assertFalse(RoadContext.isHighway(80f, null))
        assertTrue(RoadContext.isHighway(80.1f, null))
        assertTrue(RoadContext.isHighway(30f, "경부고속도로 서울방향"))
        assertTrue(RoadContext.isHighway(null, "올림픽대로(자동차전용도로)"))
        assertTrue(RoadContext.isHighway(null, "내부순환 고속화도로"))
        assertFalse(RoadContext.isHighway(null, "성남시 수정구 시흥동"))
        assertFalse(RoadContext.isHighway(null, null))
    }

    @Test fun guideAdvances_andStopsAtEnd() {
        val g = GuideFlow()
        assertFalse(g.next())
        assertEquals(GuideStep.HAZARD, g.start(false))
        assertTrue(g.active); assertFalse(g.done)
        assertTrue(g.next()); assertEquals(GuideStep.CHECK, g.current)
        assertTrue(g.next()); assertTrue(g.next()); assertEquals(GuideStep.REPORT, g.current)
        assertTrue(g.next()); assertTrue(g.done); assertFalse(g.active); assertNull(g.current)
        assertFalse(g.next())
    }

    @Test fun doorOpenPauses_closeOnEvacuateMovesOn() {
        val g = GuideFlow(); g.start(true)               // EVACUATE 먼저
        assertFalse(g.onDoor(true)); assertTrue(g.paused)
        assertFalse(g.next())                            // 멈춤 중엔 진행 안 됨
        assertTrue(g.onDoor(false)); assertFalse(g.paused)
        assertEquals(GuideStep.HAZARD, g.current)        // 대피 단계는 문이 닫히면 다음으로
    }

    @Test fun doorCycleOnOtherStep_resumesSameStep() {
        val g = GuideFlow(); g.start(false)              // HAZARD
        g.onDoor(true)
        assertTrue(g.onDoor(false))
        assertEquals(GuideStep.HAZARD, g.current)
        assertFalse(g.onDoor(false))                     // 이미 닫힌 상태에서 또 닫힘: 변화 없음
    }

    @Test fun doorBeforeStart_isRemembered() {
        val g = GuideFlow()
        assertFalse(g.onDoor(true))
        g.start(false)
        assertFalse(g.paused)                            // start에서 문 상태 초기화
    }

    @Test fun doorClosingLastEvacuateFinishesGuideWithoutSpeech() {
        val g = GuideFlow(); g.start(false)
        g.next(); g.next(); assertEquals(GuideStep.EVACUATE, g.current)
        g.next(); assertEquals(GuideStep.REPORT, g.current)
        g.onDoor(true); assertTrue(g.onDoor(false)); assertEquals(GuideStep.REPORT, g.current)
    }

    // ---------- 리포트 기준 시각 ----------
    private val ev = listOf(EventMark(100_000, "HARD_BRAKE", false), EventMark(160_000, "ACCIDENT_CONFIRMED", true), EventMark(400_000, "SIREN", false))

    @Test fun anchorPrefersConfirmedOverNearest() {
        val (t, m) = ReportWindow.anchor(110_000, ev)
        assertEquals(160_000L, t); assertEquals("ACCIDENT_CONFIRMED", m!!.kind)
    }

    @Test fun anchorSnapBoundary() {
        assertEquals(400_000L, ReportWindow.anchor(520_000, ev).first)        // 정확히 2분
        val (t, m) = ReportWindow.anchor(520_001, ev)
        assertEquals(520_001L, t); assertNull(m)
    }

    @Test fun anchorNearestAmongUnconfirmed() {
        val e = listOf(EventMark(1_000, "HARD_BRAKE", false), EventMark(9_000, "SIREN", false))
        assertEquals(9_000L, ReportWindow.anchor(6_000, e).first)
        assertEquals(1_000L, ReportWindow.anchor(4_000, e).first)
    }

    @Test fun windowIsFiveMinutesEachSide() {
        val r = ReportWindow.range(1_000_000)
        assertEquals(700_000L, r.first); assertEquals(1_300_000L, r.last)
    }

    @Test fun issueChecks() {
        val a = 1_000_000L
        assertEquals(IssueCheck.Duplicate("R-1"), Issue.check(a, a + 999_999, 6000, "R-1"))
        assertEquals(IssueCheck.Future(a), Issue.check(a, a - 1, 0, null))
        assertEquals(IssueCheck.Collecting(300_000), Issue.check(a, a, 0, null))
        assertEquals(IssueCheck.Collecting(1), Issue.check(a, a + 299_999, 10, null))
        assertEquals(IssueCheck.NoData, Issue.check(a, a + 300_000, 0, null))
        assertEquals(IssueCheck.Ok, Issue.check(a, a + 300_000, 1, null))
    }

    @Test fun coverageRatio() {
        assertEquals(1.0, Issue.coverage(6000), 1e-9)
        assertEquals(0.5, Issue.coverage(3000), 1e-9)
        assertEquals(1.0, Issue.coverage(9000), 1e-9)
    }

    // ---------- 사실 추출 ----------
    /** 64km/h 주행, T-4.5초 오른쪽 깜빡이, T-0.8초 브레이크, T에 충돌, T+6초 P, T+20초 문 열림 */
    private fun scenario(T: Long): List<Sample> {
        val out = mutableListOf<Sample>()
        var t = T - 60_000
        while (t <= T + 60_000) {
            val turn = if (t in (T - 4_500) until (T + 1_000)) 'R' else '0'
            val brk = t >= T - 800
            val spd = when { t < T -> 64f; t < T + 1_500 -> 64f - (t - T) / 1500f * 64f; else -> 0f }
            val steer = if (t in (T - 3_000) until (T - 900)) 35f else 2f
            out += Sample(t, spd, if (t >= T + 6_000) 'P' else 'D', brk, turn, steer, true, false, t >= T + 20_000, 37.4, 127.1)
            t += 100
        }
        return out
    }

    @Test fun factsFromScenario() {
        val T = 10_000_000L
        val f = FactExtractor.extract(scenario(T).shuffled(java.util.Random(1)), T)
        assertEquals(64f, f.speedAtEventKph!!, 0.01f)
        assertEquals(64f, f.maxSpeed30sKph!!, 0.01f)
        val turn = f.turn!!
        assertEquals('R', turn.side); assertEquals(4.5, turn.onBeforeS, 1e-9)
        assertEquals(80, turn.distanceM)            // 64km/h × 4.5초 ≈ 80m
        assertEquals(64f, turn.speedAtOnKph!!, 0.01f)
        assertEquals(0.8, f.brakeOnBeforeS!!, 1e-9)
        assertEquals(35f, f.maxAbsSteer10sDeg, 0.01f)
        assertEquals(true, f.lowBeam); assertEquals(false, f.wiper)
        assertEquals(6.0, f.parkAfterS!!, 1e-9)
        assertEquals(20.0, f.doorOpenAfterS!!, 1e-9)
    }

    @Test fun factsWithoutSignal_andTurnTooOld() {
        val T = 1_000_000L
        val noTurn = scenario(T).map { it.copy(turn = '0', brk = false) }
        val f = FactExtractor.extract(noTurn, T)
        assertNull(f.turn); assertNull(f.brakeOnBeforeS)
        val old = scenario(T).map { it.copy(turn = if (it.t in (T - 40_000) until (T - 31_000)) 'L' else '0') }
        assertNull(FactExtractor.extract(old, T).turn)
        val edge = scenario(T).map { it.copy(turn = if (it.t >= T - 30_000 && it.t <= T) 'L' else '0') }
        assertEquals(30.0, FactExtractor.extract(edge, T).turn!!.onBeforeS, 1e-9)
    }

    @Test fun brakeWindowEdges() {
        val T = 1_000_000L
        val base = scenario(T).map { it.copy(brk = false) }
        fun at(ms: Long) = FactExtractor.extract(base.map { if (it.t == ms) it.copy(brk = true) else it }, T).brakeOnBeforeS
        assertEquals(10.0, at(T - 10_000)!!, 1e-9)
        assertNull(at(T - 10_100))
        assertEquals(-2.0, at(T + 2_000)!!, 1e-9)
        assertNull(at(T + 2_100))
    }

    @Test fun factsOnEmpty() {
        val f = FactExtractor.extract(emptyList(), 0)
        assertNull(f.speedAtEventKph); assertNull(f.turn); assertEquals(0f, f.maxAbsSteer10sDeg, 0f); assertNull(f.parkAfterS)
    }

    // ---------- 진술·과금·ID ----------
    private val seoul = TimeZone.getTimeZone("Asia/Seoul")
    @Test fun statementOpeningFillsDateTimePlace() {
        val t = 1_762_818_411_000L   // 2025-11-11 08:46:51 KST
        assertEquals("11월 11일 오전 8시 46분경, 경부고속도로 서울방향 부근에서", StatementPrompt.opening(t, "경부고속도로 서울방향", seoul))
        assertEquals("11월 11일 오후 12시 46분경, (장소) 부근에서", StatementPrompt.opening(t + 4 * 3_600_000, " ", seoul))
        assertEquals("11월 11일 오전 12시 46분경, (장소) 부근에서", StatementPrompt.opening(t - 8 * 3_600_000, null, seoul))
        assertEquals(5, StatementPrompt.QUESTIONS.size)
    }

    @Test fun payerIsInsurerOnlyForPartners() {
        val partners = setOf("예시손해보험")
        assertEquals(Payer.Insurer("예시손해보험"), Billing.payer("예시손해보험", partners))
        assertEquals(Payer.Personal(9_900), Billing.payer("다른보험", partners))
        assertEquals(Payer.Personal(9_900), Billing.payer(null, partners))
        assertEquals(Payer.Personal(9_900), Billing.payer("", setOf("")))
    }

    @Test fun reportIdFormat() {
        val id = ReportId.make(1_762_818_411_000L, "n", seoul)
        assertTrue(id, id.matches(Regex("R-20251111-084651-[0-9A-F]{4}")))
        assertEquals(id, ReportId.make(1_762_818_411_000L, "n", seoul))
        assertFalse(id == ReportId.make(1_762_818_411_000L, "m", seoul))
        assertEquals("https://x/v/?r=R-1&h=0123456789abcdef", ReportId.viewerUrl("https://x/v/", "R-1", "0123456789abcdefXYZ"))
    }

    // ---------- 변이 테스트 보강 ----------
    @Test fun guideExposesState() {
        val g = GuideFlow()
        assertEquals(-1, g.index); assertTrue(g.steps.isEmpty())
        g.start(true)
        assertTrue(g.highway); assertEquals(0, g.index); assertEquals(4, g.steps.size)
        g.next(); assertEquals(1, g.index)
        GuideFlow().apply { start(false); assertFalse(highway) }
    }

    @Test fun guideStepTexts() {
        assertEquals("비상등 켜기", GuideStep.HAZARD.title)
        assertEquals("비상등을 켜 주세요.", GuideStep.HAZARD.speech)
        assertTrue(GuideStep.EVACUATE.hint.contains("문이 열리면"))
        assertTrue(GuideStep.values().all { it.title.isNotBlank() && it.speech.isNotBlank() && it.hint.isNotBlank() })
    }

    /** 실제 시각(epoch ms) 규모에서도 ±2분 보정이 동작해야 한다 */
    @Test fun anchorWithEpochTimes() {
        val base = 1_762_818_411_000L
        val e = listOf(EventMark(base, "HARD_BRAKE", false), EventMark(base + 90_000, "SIREN", false))
        assertEquals(base + 90_000, ReportWindow.anchor(base + 60_000, e).first)
        assertEquals(base, ReportWindow.anchor(base + 30_000, e).first)
        assertEquals(base - 130_000, ReportWindow.anchor(base - 130_000, e).first)
    }

    @Test fun turnAtSecondSample_isFound() {
        val rows = listOf(Sample(0, 30f, 'D', false, '0'), Sample(100, 30f, 'D', false, 'L'))
        val f = FactExtractor.extract(rows, 100)
        assertEquals('L', f.turn!!.side); assertEquals(0.0, f.turn!!.onBeforeS, 1e-9); assertEquals(0, f.turn!!.distanceM)
    }

    @Test fun steerAndSpeedWindowEdges() {
        val T = 1_000_000L
        fun rows(at: Long, steer: Float, spd: Float) = (0..1200).map { i -> val t = T - 60_000 + i * 100L
            Sample(t, if (t == at) spd else 10f, 'D', false, '0', if (t == at) steer else 1f) }
        fun f(at: Long) = FactExtractor.extract(rows(at, 40f, 90f), T)
        assertEquals(40f, f(T - 10_000).maxAbsSteer10sDeg, 0f)
        assertEquals(1f, f(T - 10_100).maxAbsSteer10sDeg, 0f)
        assertEquals(40f, f(T).maxAbsSteer10sDeg, 0f)
        assertEquals(1f, f(T + 100).maxAbsSteer10sDeg, 0f)
        assertEquals(90f, f(T - 30_000).maxSpeed30sKph!!, 0f)
        assertEquals(10f, f(T - 30_100).maxSpeed30sKph!!, 0f)
        assertEquals(90f, f(T).maxSpeed30sKph!!, 0f)
        assertEquals(10f, f(T + 100).maxSpeed30sKph!!, 0f)
        assertEquals(40f, FactExtractor.extract(rows(T - 5_000, -40f, 10f), T).maxAbsSteer10sDeg, 0f)
    }

    @Test fun maxSpeedPicksLargest() {
        val rows = listOf(Sample(0, 20f), Sample(100, 70f), Sample(200, 40f))
        assertEquals(70f, FactExtractor.extract(rows, 200).maxSpeed30sKph!!, 0f)
    }

    @Test fun parkAndDoorMustBeStrictlyAfterEvent() {
        val rows = listOf(Sample(0, 0f, 'P', door = true), Sample(100, 0f, 'D', door = false))
        val f = FactExtractor.extract(rows, 0)
        assertNull(f.parkAfterS); assertNull(f.doorOpenAfterS)
    }

    @Test fun lightsAtEventAreReported() {
        val f = FactExtractor.extract(listOf(Sample(0, 10f, low = false, wiper = true)), 0)
        assertEquals(false, f.lowBeam); assertEquals(true, f.wiper)
    }

    @Test fun checkResultFields() {
        assertEquals(10L, (Issue.check(10, 0, 0, null) as IssueCheck.Future).anchor)
        assertEquals(300_000L, (Issue.check(0, 0, 0, null) as IssueCheck.Collecting).remainMs)
        assertEquals("R-9", (Issue.check(0, 0, 0, "R-9") as IssueCheck.Duplicate).rid)
        assertEquals(9_900, (Billing.payer(null, emptySet()) as Payer.Personal).priceWon)
        assertEquals("A", (Billing.payer("A", setOf("A")) as Payer.Insurer).name)
    }
}
