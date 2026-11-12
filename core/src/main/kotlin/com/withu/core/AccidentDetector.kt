package com.withu.core

/** 사고 추정 판단 결과 */
sealed class Decision {
    /** ① 급제동: 표시만 남기고 묻지 않는다 */
    data class HardBrake(val t: Long, val fromKph: Float, val toKph: Float) : Decision()
    /** ② 급제동 뒤 정차 */
    data class Stopped(val t: Long, val afterMs: Long) : Decision()
    /** ③ 정차가 이어진 채 P단 → "지금 사고가 난 상황인가요?" */
    data class Suspect(val eventT: Long, val fromKph: Float, val parkAfterStopMs: Long) : Decision()
    /** 질문하지 않고 끝난 경우 (기록은 7일 보관에 그대로 남는다) */
    data class NoPrompt(val eventT: Long, val reason: Reason) : Decision()
    /** 에어백 전개: 질문 없이 사고로 본다 (실차·지원 차종만) */
    data class Airbag(val t: Long) : Decision()

    enum class Reason { NO_STOP, RESUMED_BEFORE_PARK, NO_PARK_IN_TIME }
}

data class DetectorConfig(
    /** 1초 동안 이만큼 이상 감속하면 급제동 (km/h) */
    val dropKph: Float = 15f,
    /** 급제동 뒤 이 시간 안에 서야 정차로 본다 */
    val stopWithinMs: Long = 10_000,
    /** 정차 뒤 이 시간 안에 P단이 되어야 묻는다 (제안값) */
    val parkWithinMs: Long = 180_000,
    /** 이 속도 미만이면 정차 */
    val stopKph: Float = 0.5f,
    /** 정차 뒤 이 속도를 넘으면 다시 출발한 것 */
    val resumeKph: Float = 5f,
    /** 한 판단이 끝난 뒤 다음 급제동을 보지 않는 시간 */
    val cooldownMs: Long = 5_000,
)

/**
 * 사고 추정 규칙 (2026-11-12 서비스 방향 확정)
 *  급제동(1초 −15km/h & 브레이크) → 10초 안 정차 → 정차가 이어진 채 P단 일 때만 질문한다.
 *  노란불 급정거처럼 D단으로 서 있다가 다시 출발하면 질문하지 않는다.
 */
class AccidentDetector(private val cfg: DetectorConfig = DetectorConfig()) {
    private val recent = ArrayDeque<Sample>()
    private var evtT = -1L
    private var evtFrom = 0f
    private var stoppedAt = -1L
    private var cooldownUntil = Long.MIN_VALUE
    private var airbagFired = false

    val armed: Boolean get() = evtT >= 0

    fun tick(s: Sample, airbagDeployed: Boolean? = null): List<Decision> {
        val out = mutableListOf<Decision>()
        if (airbagDeployed == true && !airbagFired) { airbagFired = true; out += Decision.Airbag(s.t) }
        recent.addLast(s)
        while (recent.size > 1 && s.t - recent.first().t > 1_500) recent.removeFirst()
        val spd = s.spd ?: return out

        if (evtT < 0) {
            val prev = speedAgo(s.t, 1_000)
            if (s.t >= cooldownUntil && prev != null && prev - spd >= cfg.dropKph && s.brk == true) {
                evtT = s.t; evtFrom = prev; stoppedAt = -1
                out += Decision.HardBrake(s.t, prev, spd)
            } else return out
        }

        val since = s.t - evtT
        if (stoppedAt < 0) {
            if (spd < cfg.stopKph && since <= cfg.stopWithinMs) { stoppedAt = s.t; out += Decision.Stopped(s.t, since) }
            else if (since > cfg.stopWithinMs) { out += Decision.NoPrompt(evtT, Decision.Reason.NO_STOP); reset(s.t) }
            return out
        }
        when {
            spd > cfg.resumeKph -> { out += Decision.NoPrompt(evtT, Decision.Reason.RESUMED_BEFORE_PARK); reset(s.t) }
            s.gear == 'P' -> { out += Decision.Suspect(evtT, evtFrom, s.t - stoppedAt); reset(s.t) }
            s.t - stoppedAt > cfg.parkWithinMs -> { out += Decision.NoPrompt(evtT, Decision.Reason.NO_PARK_IN_TIME); reset(s.t) }
        }
        return out
    }

    /** now 기준 약 ago 밀리초 전 속도 (그보다 오래된 샘플 중 가장 최근) */
    private fun speedAgo(now: Long, ago: Long): Float? = recent.lastOrNull { now - it.t >= ago }?.spd

    private fun reset(now: Long) { evtT = -1; stoppedAt = -1; cooldownUntil = now + cfg.cooldownMs }
}
