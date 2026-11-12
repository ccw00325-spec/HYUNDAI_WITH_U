package com.withu.core

import java.util.Calendar
import java.util.TimeZone

/** 리포트 기준 시각 후보가 되는 기록된 사건 */
data class EventMark(val t: Long, val kind: String, val confirmed: Boolean)

object ReportWindow {
    const val HALF_MS = 300_000L          // 앞뒤 5분
    const val SNAP_MS = 120_000L          // 고른 시각 ±2분 안의 사건으로 맞춤 (제안값)

    fun range(anchor: Long) = (anchor - HALF_MS)..(anchor + HALF_MS)

    /** 사용자가 고른 시각 근처의 사건으로 기준 시각을 맞춘다. 확인된 사고 > 가까운 사건 순. 없으면 고른 시각 그대로 */
    fun anchor(userT: Long, events: List<EventMark>, snapMs: Long = SNAP_MS): Pair<Long, EventMark?> {
        val near = events.filter { kotlin.math.abs(it.t - userT) <= snapMs }
        val best = near.sortedWith(compareByDescending<EventMark> { it.confirmed }.thenBy { kotlin.math.abs(it.t - userT) }).firstOrNull()
        return (best?.t ?: userT) to best
    }
}

/** 발급 전 확인 결과 */
sealed class IssueCheck {
    object Ok : IssueCheck()
    data class Future(val anchor: Long) : IssueCheck()
    /** 직후 5분이 아직 지나지 않았다. 남은 밀리초 */
    data class Collecting(val remainMs: Long) : IssueCheck()
    /** 보관 기간(7일)보다 오래되었거나 기록이 없다 */
    object NoData : IssueCheck()
    /** 같은 기준 시각으로 이미 발급한 리포트가 있다 (중복 과금 방지) */
    data class Duplicate(val rid: String) : IssueCheck()
}

object Issue {
    fun check(anchor: Long, now: Long, sampleCount: Int, existingRid: String?): IssueCheck = when {
        existingRid != null -> IssueCheck.Duplicate(existingRid)
        anchor > now -> IssueCheck.Future(anchor)
        anchor + ReportWindow.HALF_MS > now -> IssueCheck.Collecting(anchor + ReportWindow.HALF_MS - now)
        sampleCount == 0 -> IssueCheck.NoData
        else -> IssueCheck.Ok
    }

    /** 기대 샘플 수(10Hz × 10분) 대비 실제 비율 */
    fun coverage(sampleCount: Int, hz: Int = 10): Double = (sampleCount.toDouble() / (hz * 600)).coerceAtMost(1.0)
}

data class TurnFact(val side: Char, val onBeforeS: Double, val distanceM: Int, val speedAtOnKph: Float?)

data class Facts(
    val speedAtEventKph: Float?,
    val maxSpeed30sKph: Float?,
    val turn: TurnFact?,
    val brakeOnBeforeS: Double?,
    val maxAbsSteer10sDeg: Float,
    val lowBeam: Boolean?,
    val wiper: Boolean?,
    val parkAfterS: Double?,
    val doorOpenAfterS: Double?,
)

/** 기준 시각 T 앞뒤 기록에서 보험 담당자가 먼저 보는 사실만 뽑는다. 과실 판단은 하지 않는다. */
object FactExtractor {
    const val TURN_LOOKBACK_MS = 30_000L
    const val BRAKE_BEFORE_MS = 10_000L
    const val BRAKE_AFTER_MS = 2_000L

    fun extract(rows: List<Sample>, t: Long): Facts {
        val sorted = rows.sortedBy { it.t }
        val iT = sorted.indexOfLast { it.t <= t }
        val at = sorted.getOrNull(iT)
        var turn: TurnFact? = null
        if (iT >= 1) {
            for (i in iT downTo 1) {
                if (sorted[iT].t - sorted[i].t > TURN_LOOKBACK_MS) break
                val cur = sorted[i].turn; val pre = sorted[i - 1].turn
                if ((cur == 'L' || cur == 'R') && pre != cur) {
                    var dist = 0.0
                    for (k in i until iT) dist += (sorted[k].spd ?: 0f) / 3.6 * ((sorted[k + 1].t - sorted[k].t) / 1000.0)
                    turn = TurnFact(cur, (t - sorted[i].t) / 1000.0, Math.round(dist).toInt(), sorted[i].spd)
                    break
                }
            }
        }
        val brake = sorted.firstOrNull { it.t in (t - BRAKE_BEFORE_MS)..(t + BRAKE_AFTER_MS) && it.brk == true }
        val steer = sorted.filter { it.t in (t - 10_000)..t }.maxOfOrNull { kotlin.math.abs(it.steer ?: 0f) } ?: 0f
        val park = sorted.firstOrNull { it.t > t && it.gear == 'P' }
        val door = sorted.firstOrNull { it.t > t && it.door == true }
        return Facts(
            speedAtEventKph = at?.spd,
            maxSpeed30sKph = sorted.filter { it.t in (t - 30_000)..t }.mapNotNull { it.spd }.maxOrNull(),
            turn = turn,
            brakeOnBeforeS = brake?.let { (t - it.t) / 1000.0 },
            maxAbsSteer10sDeg = steer,
            lowBeam = at?.low, wiper = at?.wiper,
            parkAfterS = park?.let { (it.t - t) / 1000.0 },
            doorOpenAfterS = door?.let { (it.t - t) / 1000.0 },
        )
    }
}

/** 진술 녹음 첫 문장: 날짜·시각·장소를 미리 채운다 */
object StatementPrompt {
    val QUESTIONS = listOf(
        "언제, 어디서 일어났나요?", "그때 무엇을 하던 중이었나요? (차로 변경·정차·직진 등)",
        "상대 차량은 어떻게 움직였나요?", "다친 사람이 있나요?", "사고 뒤 어떤 조치를 했나요?",
    )
    fun opening(t: Long, place: String?, tz: TimeZone = TimeZone.getTimeZone("Asia/Seoul")): String {
        val c = Calendar.getInstance(tz).apply { timeInMillis = t }
        val h24 = c.get(Calendar.HOUR_OF_DAY)
        val ampm = if (h24 < 12) "오전" else "오후"
        val h12 = if (h24 % 12 == 0) 12 else h24 % 12
        val where = place?.takeIf { it.isNotBlank() } ?: "(장소)"
        return "${c.get(Calendar.MONTH) + 1}월 ${c.get(Calendar.DAY_OF_MONTH)}일 $ampm ${h12}시 ${c.get(Calendar.MINUTE)}분경, $where 부근에서"
    }
}

/** 리포트 비용 부담 주체: 제휴 보험사 가입자면 보험사가, 아니면 개인이 건당 결제 */
sealed class Payer {
    data class Insurer(val name: String) : Payer()
    data class Personal(val priceWon: Int) : Payer()
}

object Billing {
    const val PERSONAL_PRICE_WON = 9_900      // 제안값 (지불의사 조사 전 가정)
    fun payer(insurer: String?, partners: Set<String>): Payer =
        if (insurer != null && insurer.isNotBlank() && insurer in partners) Payer.Insurer(insurer) else Payer.Personal(PERSONAL_PRICE_WON)
}

object ReportId {
    /** R-yyyyMMdd-HHmmss-XXXX (서울 시각) */
    fun make(anchor: Long, nonce: String, tz: TimeZone = TimeZone.getTimeZone("Asia/Seoul")): String {
        val c = Calendar.getInstance(tz).apply { timeInMillis = anchor }
        fun p(v: Int) = v.toString().padStart(2, '0')
        val d = "${c.get(Calendar.YEAR)}${p(c.get(Calendar.MONTH) + 1)}${p(c.get(Calendar.DAY_OF_MONTH))}"
        val tm = "${p(c.get(Calendar.HOUR_OF_DAY))}${p(c.get(Calendar.MINUTE))}${p(c.get(Calendar.SECOND))}"
        return "R-$d-$tm-" + Hash.sha256("$anchor:$nonce").take(4).uppercase()
    }
    fun viewerUrl(base: String, rid: String, sha: String) = "$base?r=$rid&h=${sha.take(16)}"
}
