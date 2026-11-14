package com.withu.core

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** 사실표 한 줄의 확인 수준 */
enum class Level(val ko: String) { CONFIRMED("확인됨"), PARTIAL("부분 확인"), UNKNOWN("확인 불가"), NA("해당 없음") }

/** 그 사실이 운전자 쪽 주장과 어떤 관계인지. 과실 비율은 계산하지 않는다 */
enum class Lean(val ko: String) { FOR_DRIVER("운전자 주장 뒷받침"), AGAINST_DRIVER("불리할 수 있음"), NEUTRAL("판단 재료") }

/**
 * 과실비율 인정기준의 수정요소 하나에 대응하는 차량 기록 사실.
 * @param markT 그래프에 표시할 근거 시각(없으면 null)
 */
data class FactorRow(val key: String, val factor: String, val signal: String, val level: Level, val lean: Lean, val text: String, val markT: Long? = null)

/** 조향 한 번(진로변경 후보): |조향각|이 기준 이상인 구간 */
data class Maneuver(val start: Long, val end: Long, val peakDeg: Float) { val durationS get() = (end - start) / 1000.0 }

/**
 * 수정요소 사실표.
 * 리포트를 보험 담당자가 쓰는 '기본과실 + 수정요소' 순서대로 정리하고, 각 항목에 차량 신호 근거를 붙인다.
 * 비율은 정하지 않는다(판정은 보험사 협의·분쟁심의). 불리한 사실도 그대로 적고, 신호가 없으면 '확인 불가'로 둔다.
 */
object FaultFactSheet {
    const val LOOKBACK_MS = 30_000L
    const val STEER_DEG = 5f                 // 조향 시작으로 보는 각도 (제안값 · 실차 보정 필요)
    const val SIGNAL_MIN_M_ROAD = 30         // 도로교통법 시행령 별표 2: 진로를 바꾸려는 지점 30m 전(고속도로 100m) 신호
    const val SIGNAL_MIN_M_HIGHWAY = 100
    const val CALM_STEER_S = 2.0             // 조향을 이 시간 이상에 걸쳐 했으면 완만한 진로변경 (제안값)
    const val ABRUPT_STEER_S = 1.0           // 이보다 짧으면 급진로변경 가능성 (제안값)
    const val SPEED_AVG_MS = 5_000L
    const val OVER_LIMIT_KPH = 10f           // 제한속도보다 이만큼 이상 빠르면 '불리할 수 있음'
    const val STOP_KPH = 0.5f
    const val BRAKE_BEFORE_MS = 10_000L
    const val BRAKE_AFTER_MS = 2_000L

    const val PEDAL_RISE_KPH = 3f           // 브레이크를 밟은 채 이만큼 이상 빨라지면 페달 오조작·급발진 정황 (제안값)
    const val PEDAL_MIN_MS = 1_000L
    const val ACCEL_NO_BRAKE_KPH = 10f      // 브레이크 없이 10초 안에 이만큼 이상 빨라진 구간

    val KEYS = listOf("signal", "abrupt_lane", "speed", "hard_brake", "stopped_hit", "door_open", "night_light", "pedal")

    fun build(rows: List<Sample>, t: Long, address: String? = null): List<FactorRow> {
        val s = rows.sortedBy { it.t }
        val at = s.lastOrNull { it.t <= t }
        val maxSpd30 = s.filter { it.t in (t - 30_000)..t }.mapNotNull { it.spd }.maxOrNull()
        val highway = RoadContext.isHighway(maxSpd30, address)
        val man = maneuver(s, t)
        return listOf(signal(s, t, man, highway), abrupt(s, t, man), speed(s, t), brake(s, t), stopped(s, t, at), door(s, t, at), night(s, t, at), pedal(s, t))
    }

    /** 기준 시각 전 30초(~ +1초) 안의 마지막 조향 구간 */
    fun maneuver(s: List<Sample>, t: Long): Maneuver? {
        var cur: Maneuver? = null; var last: Maneuver? = null; var prevT = Long.MIN_VALUE
        for (r in s) {
            if (r.t < t - LOOKBACK_MS || r.t > t + 1_000) continue
            val on = (r.steer?.let { abs(it) } ?: 0f) >= STEER_DEG
            if (on) {
                cur = if (cur == null || r.t - prevT > 300) Maneuver(r.t, r.t, abs(r.steer!!)) else cur.copy(end = r.t, peakDeg = maxOf(cur.peakDeg, abs(r.steer!!)))
                prevT = r.t
            } else if (cur != null) { last = cur.copy(end = r.t); cur = null }
        }
        val m = cur ?: last
        return m?.takeIf { it.start <= t }
    }

    private fun dist(s: List<Sample>, from: Long, to: Long): Int {
        var d = 0.0
        for (i in 0 until s.size - 1) {
            val a = s[i]; val b = s[i + 1]
            if (a.t < from || b.t > to) continue
            d += (a.spd ?: 0f) / 3.6 * ((b.t - a.t) / 1000.0)
        }
        return Math.round(d).toInt()
    }

    private fun side(c: Char) = if (c == 'R') "오른쪽" else "왼쪽"
    private fun f1(v: Double) = String.format(java.util.Locale.ROOT, "%.1f", v)

    fun signal(s: List<Sample>, t: Long, man: Maneuver?, highway: Boolean): FactorRow {
        val base = FactorRow("signal", "진로변경 신호 불이행·지연", "TurnSignal 점등 시각 ↔ 조향 시작 시각", Level.UNKNOWN, Lean.NEUTRAL, "방향지시등 신호 미수신")
        if (s.none { it.turn != null }) return base
        val ref = man?.start ?: t
        // 기준점(조향 시작, 없으면 기준 시각) 직전 30초 ~ 기준 시각 사이에 켜진 마지막 점등
        // 조향 시작 전에 켜진 마지막 점등을 쓰고, 없으면 조향 뒤 처음 켜진 점등(지연)을 쓴다
        val ons = (1 until s.size).filter { i -> val c = s[i].turn; (c == 'L' || c == 'R') && s[i - 1].turn != c && s[i].t >= ref - LOOKBACK_MS && s[i].t <= t }.map { s[it] }
        val pick = ons.lastOrNull { it.t <= ref } ?: ons.firstOrNull()
        val onT: Long? = pick?.t; val onSide = pick?.turn ?: '0'
        val min = if (highway) SIGNAL_MIN_M_HIGHWAY else SIGNAL_MIN_M_ROAD
        val road = if (highway) "고속도로" else "일반도로"
        if (man == null) {
            if (s.none { it.steer != null }) return base.copy(level = Level.PARTIAL, text = "조향 신호 미수신 · " + (onT?.let { "기준 시각 ${f1((t - it) / 1000.0)}초 전 ${side(onSide)} 점등" } ?: "방향지시등 꺼짐"))
            return base.copy(level = Level.NA, text = "기준 시각 전 30초 조향 변화 없음(진로변경 아님)" + (onT?.let { " · ${side(onSide)} 점등 ${f1((t - it) / 1000.0)}초 전" } ?: ""), markT = onT)
        }
        if (onT == null) return base.copy(level = Level.CONFIRMED, lean = Lean.AGAINST_DRIVER, text = "조향 중 방향지시등 꺼짐 — 신호 없이 진로변경", markT = man.start)
        val lead = (man.start - onT) / 1000.0
        if (lead < 0) return base.copy(level = Level.CONFIRMED, lean = Lean.AGAINST_DRIVER, text = "조향 시작 ${f1(-lead)}초 뒤 ${side(onSide)} 점등 — 지연", markT = onT)
        val d = dist(s, onT, man.start)
        return if (d >= min) base.copy(level = Level.CONFIRMED, lean = Lean.FOR_DRIVER, text = "조향 시작 ${f1(lead)}초 전(약 ${d}m 앞) ${side(onSide)} 점등 — $road 법정 ${min}m 이상", markT = onT)
        else base.copy(level = Level.CONFIRMED, lean = Lean.AGAINST_DRIVER, text = "조향 시작 ${f1(lead)}초 전(약 ${d}m 앞) ${side(onSide)} 점등 — $road 법정 ${min}m 미만", markT = onT)
    }

    fun abrupt(s: List<Sample>, t: Long, man: Maneuver?): FactorRow {
        val base = FactorRow("abrupt_lane", "급진로변경", "조향 시작~끝 시간 (차선 인식 신호는 없음)", Level.UNKNOWN, Lean.NEUTRAL, "조향 신호 미수신")
        if (s.none { it.steer != null }) return base
        if (man == null) return base.copy(level = Level.NA, text = "기준 시각 전 30초 조향 ${STEER_DEG.toInt()}° 이상 없음")
        val lean = when { man.durationS >= CALM_STEER_S -> Lean.FOR_DRIVER; man.durationS < ABRUPT_STEER_S -> Lean.AGAINST_DRIVER; else -> Lean.NEUTRAL }
        val how = when (lean) { Lean.FOR_DRIVER -> "완만"; Lean.AGAINST_DRIVER -> "급조향"; else -> "보통" }
        return base.copy(level = Level.PARTIAL, lean = lean, text = "조향 ${f1(man.durationS)}초 유지(최대 ${Math.round(man.peakDeg)}°, $how) · 차선 위치는 확인 불가", markT = man.start)
    }

    fun speed(s: List<Sample>, t: Long): FactorRow {
        val base = FactorRow("speed", "과속 (현저·중대 과실)", "AAOS 속도 · 내비 제한속도 · 위치", Level.UNKNOWN, Lean.NEUTRAL, "속도 신호 미수신")
        val v = s.filter { it.t in (t - SPEED_AVG_MS)..t }.mapNotNull { it.spd }
        if (v.isEmpty()) return base
        val avg = v.average().toFloat(); val max = v.max()
        val lim = s.lastOrNull { it.t in (t - 60_000)..t && it.lim != null }?.lim
        if (lim == null) return base.copy(level = Level.PARTIAL, text = "기준 시각 전 5초 평균 ${Math.round(avg)}km/h · 최고 ${Math.round(max)}km/h · 제한속도 미수신(현장 표지와 대조)")
        val over = avg - lim
        val lean = when { over <= 0f -> Lean.FOR_DRIVER; over >= OVER_LIMIT_KPH -> Lean.AGAINST_DRIVER; else -> Lean.NEUTRAL }
        val rel = if (over <= 0f) "제한 ${lim}km/h 이내" else "제한 ${lim}km/h보다 ${Math.round(over)}km/h 빠름"
        return base.copy(level = Level.CONFIRMED, lean = lean, text = "기준 시각 전 5초 평균 ${Math.round(avg)}km/h · $rel")
    }

    /** 1초 간격 속도 차로 구한 최대 감속도(m/s²) */
    fun maxDecel(s: List<Sample>, from: Long, to: Long): Double? {
        var best: Double? = null; var j = 0
        for (i in s.indices) {
            val b = s[i]; if (b.t < from || b.t > to || b.spd == null) continue
            while (j < i && s[j].t < b.t - 1_000) j++
            val a = s[j]; if (a.spd == null || b.t - a.t < 500) continue
            val d = (a.spd - b.spd) / 3.6 / ((b.t - a.t) / 1000.0)
            if (best == null || d > best) best = d
        }
        return best
    }

    fun brake(s: List<Sample>, t: Long): FactorRow {
        val base = FactorRow("hard_brake", "급정거 · 앞차 급제동", "Brake · 감속도(속도 변화)", Level.UNKNOWN, Lean.NEUTRAL, "브레이크 신호 미수신")
        if (s.none { it.brk != null }) return base
        val w = s.filter { it.t in (t - BRAKE_BEFORE_MS)..(t + BRAKE_AFTER_MS) }
        val on = w.indices.firstOrNull { i -> w[i].brk == true && (i == 0 || w[i - 1].brk != true) }?.let { w[it] }
        val dec = maxDecel(s, t - 5_000, t + 3_000)
        val decTxt = dec?.takeIf { it > 0.5 }?.let { " · 최대 감속 ${f1(it)}m/s²(${String.format(java.util.Locale.ROOT, "%.2f", it / 9.81)}g)" } ?: ""
        if (on == null) return base.copy(level = Level.CONFIRMED, text = "기준 시각 −10초~+2초 제동 기록 없음$decTxt")
        val dt = (t - on.t) / 1000.0
        val when_ = if (dt >= 0) "기준 시각 ${f1(dt)}초 전 제동 시작" else "기준 시각 ${f1(-dt)}초 뒤 제동 시작"
        return base.copy(level = Level.CONFIRMED, text = when_ + decTxt, markT = on.t)
    }

    fun stopped(s: List<Sample>, t: Long, at: Sample?): FactorRow {
        val base = FactorRow("stopped_hit", "정지 중 피추돌", "Gear · 속도 0 유지 시간", Level.UNKNOWN, Lean.NEUTRAL, "속도 신호 미수신")
        val v = at?.spd ?: return base
        if (v > STOP_KPH) return base.copy(level = Level.NA, text = "기준 시각에 주행 중(${Math.round(v)}km/h)")
        val moving = s.lastOrNull { it.t <= t && (it.spd ?: 0f) > STOP_KPH }
        // 마지막으로 움직인 뒤 처음 멈춘 샘플부터 센다
        val since = (if (moving == null) s.firstOrNull() else s.firstOrNull { it.t > moving.t })?.t ?: t
        val dur = (t - since) / 1000.0
        val gear = at.gear?.let { " · ${it}단" } ?: ""
        val head = if (moving == null) "기록 시작(${f1(dur)}초 전)부터 정지" else "기준 시각 ${f1(dur)}초 전부터 정지"
        return if (dur >= 1.0) base.copy(level = Level.CONFIRMED, lean = Lean.FOR_DRIVER, text = head + gear, markT = since)
        else base.copy(level = Level.CONFIRMED, text = "기준 시각 직전(${f1(dur)}초)에 멈춤$gear", markT = since)
    }

    fun door(s: List<Sample>, t: Long, at: Sample?): FactorRow {
        val base = FactorRow("door_open", "정차 중 문 개방", "Door(운전석) 상태 · 속도", Level.UNKNOWN, Lean.NEUTRAL, "운전석 문 신호 미수신")
        val d = at?.door ?: return base
        val after = s.firstOrNull { it.t > t && it.door == true }?.let { " · 사고 ${f1((it.t - t) / 1000.0)}초 뒤 열림(하차)" } ?: ""
        val moving = (at.spd ?: 0f) > STOP_KPH
        if (moving) return base.copy(level = Level.NA, text = "주행 중 · 운전석 문 닫힘" + if (d) "" else after)
        if (d) {
            val closed = s.lastOrNull { it.t <= t && it.door != true }
            val opened = closed?.let { c -> s.firstOrNull { it.t > c.t }?.let { (t - it.t) / 1000.0 } }
            return base.copy(level = Level.CONFIRMED, lean = Lean.AGAINST_DRIVER, text = "정차 중 운전석 문 열림" + (opened?.let { "(기준 시각 ${f1(it)}초 전부터)" } ?: ""))
        }
        return base.copy(level = Level.CONFIRMED, lean = Lean.FOR_DRIVER, text = "정차 중 운전석 문 닫혀 있음$after")
    }

    fun night(s: List<Sample>, t: Long, at: Sample?): FactorRow {
        val base = FactorRow("night_light", "야간 전조등 미점등", "Vehicle SDK LowBeam · 시각·위치로 일몰 계산", Level.UNKNOWN, Lean.NEUTRAL, "전조등 신호 미수신")
        val low = at?.low ?: return base
        val lat = s.lastOrNull { it.t <= t && it.lat != null }?.lat; val lon = s.lastOrNull { it.t <= t && it.lon != null }?.lon
        val st = if (low) "전조등 켬" else "전조등 꺼짐"
        if (lat == null || lon == null) return base.copy(level = Level.PARTIAL, text = "$st · 위치가 없어 야간 여부 미확인")
        val el = Sun.elevationDeg(t, lat, lon)
        if (el > Sun.HORIZON_DEG) return base.copy(level = Level.NA, text = "주간(태양 고도 ${Math.round(el)}°) · $st")
        return if (low) base.copy(level = Level.CONFIRMED, lean = Lean.FOR_DRIVER, text = "일몰 후(태양 고도 ${Math.round(el)}°) · 전조등 켬")
        else base.copy(level = Level.CONFIRMED, lean = Lean.AGAINST_DRIVER, text = "일몰 후(태양 고도 ${Math.round(el)}°) · 전조등 꺼짐")
    }
    /** 브레이크를 밟은 채 빨라진 가장 큰 구간: (시작 샘플, 끝 샘플, 상승 km/h) */
    fun brakeRise(s: List<Sample>, from: Long, to: Long): Triple<Sample, Sample, Float>? {
        var best: Triple<Sample, Sample, Float>? = null
        var i = 0
        val w = s.filter { it.t in from..to }
        while (i < w.size) {
            if (w[i].brk != true) { i++; continue }
            var j = i
            while (j + 1 < w.size && w[j + 1].brk == true) j++
            var lo = w[i]                                    // 구간 안에서 가장 느렸던 순간부터 잰다
            for (k in i..j) {
                val c = w[k]; val cs = c.spd ?: continue
                if (lo.spd == null || cs <= lo.spd!!) lo = c
                val rise = cs - (lo.spd ?: cs)
                if (c.t - lo.t >= PEDAL_MIN_MS && rise > 0f && (best == null || rise > best!!.third)) best = Triple(lo, c, rise)
            }
            i = j + 1
        }
        return best
    }

    private fun context(s: List<Sample>, from: Long, to: Long): String {
        val w = s.filter { it.t in from..to }
        val out = ArrayList<String>()
        if (w.any { it.cc != null }) out += if (w.any { it.cc == 'A' }) "크루즈 작동" else "크루즈 꺼짐"
        if (w.any { it.tt != null }) {
            val on = w.mapNotNull { it.tt }.joinToString("").toSet()
            out += if (on.isEmpty()) "경고등 없음" else "경고등 " + "FEAH".filter { it in on }.map { Telltale.ko(it) }.joinToString("·")
        }
        return if (out.isEmpty()) "" else " · " + out.joinToString(" · ")
    }

    fun pedal(s: List<Sample>, t: Long): FactorRow {
        val base = FactorRow("pedal", "급발진·페달 오조작 소명 (특수 상황)", "Brake 밟음 중 속도 변화 · 크루즈 · FCA/ESC/ABS 경고등 (가속 페달 개도는 SDK 미제공)", Level.UNKNOWN, Lean.NEUTRAL, "브레이크 신호 미수신")
        if (s.none { it.brk != null }) return base
        val r = brakeRise(s, t - LOOKBACK_MS, t)
        if (r != null && r.third >= PEDAL_RISE_KPH) {
            val (a, b, rise) = r
            return base.copy(level = Level.CONFIRMED, lean = Lean.FOR_DRIVER,
                text = "브레이크를 밟은 채 ${f1((b.t - a.t) / 1000.0)}초 동안 속도 +${Math.round(rise)}km/h(${Math.round(a.spd!!)}→${Math.round(b.spd!!)}) — 운전자 의도와 다른 가속 정황" + context(s, a.t, b.t), markT = a.t)
        }
        val v = s.filter { it.t in (t - 10_000)..t && it.spd != null }
        val minV = v.minByOrNull { it.spd!! }
        val after = if (minV == null) emptyList() else v.filter { it.t >= minV.t }
        val gain = if (minV == null || after.isEmpty()) 0f else after.maxOf { it.spd!! } - minV.spd!!
        if (minV != null && gain >= ACCEL_NO_BRAKE_KPH && after.none { it.brk == true && it.t < t - 1_000 }) {
            return base.copy(level = Level.PARTIAL, text = "기준 시각 전 10초 가속(+${Math.round(gain)}km/h) 중 브레이크 기록 없음 · 가속 페달 개도는 SDK 미제공(기술 지원 검토 요청)" + context(s, t - 10_000, t), markT = minV.t)
        }
        return base.copy(level = Level.NA, text = "브레이크를 밟은 채 빨라진 구간 없음" + context(s, t - LOOKBACK_MS, t))
    }
}

/** 태양 고도(도). 일출·일몰 판단용 근사식(오차 약 ±1°) */
object Sun {
    const val HORIZON_DEG = -0.833
    fun elevationDeg(t: Long, latDeg: Double, lonDeg: Double): Double {
        val rad = Math.PI / 180
        val n = t / 86_400_000.0 + 2440587.5 - 2451545.0
        val l = ((280.460 + 0.9856474 * n) % 360 + 360) % 360
        val g = (((357.528 + 0.9856003 * n) % 360 + 360) % 360) * rad
        val lambda = (l + 1.915 * sin(g) + 0.020 * sin(2 * g)) * rad
        val eps = (23.439 - 0.0000004 * n) * rad
        val ra = atan2(cos(eps) * sin(lambda), cos(lambda))
        val dec = asin(sin(eps) * sin(lambda))
        val gmst = ((18.697374558 + 24.06570982441908 * n) % 24 + 24) % 24
        val ha = (gmst * 15 + lonDeg) * rad - ra
        val lat = latDeg * rad
        return asin(sin(lat) * sin(dec) + cos(lat) * cos(dec) * cos(ha)) / rad
    }
}
