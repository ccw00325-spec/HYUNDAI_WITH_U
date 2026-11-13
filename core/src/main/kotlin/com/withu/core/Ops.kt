package com.withu.core

/**
 * 사이렌 버튼 기준 시각.
 * 급제동 뒤 3분 안에 눌렀고 그 뒤로 다시 달리지 않았다면(같은 정차), 급제동 시각과 직전 속도를 사고 기준으로 쓴다.
 * 그래야 늦게 눌러도 고속도로 판단(직전 80km/h 초과)이 맞게 된다.
 */
object SirenAnchor {
    const val SAME_STOP_MS = 180_000L
    /** 급제동 직후 이 시간 안의 ‘움직임’은 감속 중 속도로 보고 재출발로 치지 않는다 */
    const val MOVING_GRACE_MS = 15_000L

    data class Result(val t: Long, val fromKph: Float, val sameStop: Boolean)

    /**
     * @param lastBrakeAt 마지막 급제동 시각(없으면 0 이하)
     * @param lastMovingAt 마지막으로 5km/h를 넘은 시각
     */
    fun resolve(now: Long, speedNowKph: Float?, lastBrakeAt: Long, lastBrakeFromKph: Float, lastMovingAt: Long): Result {
        val sameStop = lastBrakeAt > 0 && now - lastBrakeAt < SAME_STOP_MS && lastMovingAt - lastBrakeAt < MOVING_GRACE_MS
        val speed = speedNowKph ?: 0f
        return if (sameStop) Result(lastBrakeAt, maxOf(speed, lastBrakeFromKph), true) else Result(now, speed, false)
    }
}

/**
 * 로그 개인정보 가림 (Pleos 앱 보안 가이드: 로그에 개인정보 금지).
 * release 빌드는 기본으로 위치·주소·진술 문장을 가리고, 시험할 때만 adb 전용 명령으로 잠시 풀 수 있다.
 */
object LogPrivacy {
    @Volatile var redact: Boolean = true
    const val MASK = "[가림]"

    fun loc(lat: Double?, lon: Double?): String = when {
        lat == null || lon == null -> "-"
        redact -> MASK
        else -> String.format(java.util.Locale.ROOT, "%.5f,%.5f", lat, lon)
    }

    fun text(s: String?): String = when {
        s == null -> "-"
        redact -> "$MASK(${s.length}자)"
        else -> s
    }
}

/** 실행 중 보관 정리 주기. 서비스가 며칠씩 이어져도 7일 지난 운행이 남지 않게 한다. */
object SweepSchedule {
    const val INTERVAL_MS = 3_600_000L
    fun due(lastSweep: Long, now: Long): Boolean = now - lastSweep >= INTERVAL_MS
}
