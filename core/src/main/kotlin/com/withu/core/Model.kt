package com.withu.core

/**
 * 10Hz 차량 신호 한 줄.
 * gear: 'P','D','R','N' 또는 null(미수신) · turn: 'L','R','0' 또는 null · 불리언은 null = 미지원/미수신
 */
data class Sample(
    val t: Long,
    val spd: Float? = null,
    val gear: Char? = null,
    val brk: Boolean? = null,
    val turn: Char? = null,
    val steer: Float? = null,
    val low: Boolean? = null,
    val wiper: Boolean? = null,
    val door: Boolean? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    /** 내비가 알려 준 현재 도로 제한속도(km/h). null = 미수신 */
    val lim: Int? = null,
    /** 크루즈 컨트롤: 'A' 작동 · 'E' 대기 · 'O' 운전자 개입 · 'S' 일시정지 · '0' 꺼짐/기타. null = 미수신 */
    val cc: Char? = null,
    /** 켜진 경고등: F=FCA(전방 충돌방지) · E=ESC · A=ABS · H=오토홀드. "" = 없음, null = 미수신 */
    val tt: String? = null,
) {
    fun csv(): String {
        fun b(v: Boolean?) = when (v) { null -> ""; true -> "1"; false -> "0" }
        fun f1(v: Float?) = v?.let { String.format(java.util.Locale.ROOT, "%.1f", it) } ?: ""
        fun f6(v: Double?) = v?.let { String.format(java.util.Locale.ROOT, "%.6f", it) } ?: ""
        return listOf(t.toString(), f1(spd), gear?.toString() ?: "", b(brk), turn?.toString() ?: "", f1(steer),
            b(low), b(wiper), b(door), f6(lat), f6(lon), lim?.toString() ?: "",
            cc?.toString() ?: "", tt?.let { if (it.isEmpty()) "-" else it } ?: "").joinToString(",")
    }

    companion object {
        const val HEADER = "t,spd,gear,brk,turn,steer,low,wiper,door,lat,lon,lim,cc,tt"

        /** CSV 한 줄을 읽는다. 시각이 없거나 칸이 모자라면 null. 제한속도 칸(12번째)이 없는 옛 기록도 읽는다 */
        fun parse(line: String): Sample? {
            val p = line.split(",")
            if (p.size < 11) return null
            val t = p[0].toLongOrNull() ?: return null
            fun b(s: String) = when (s) { "1" -> true; "0" -> false; else -> null }
            return Sample(t, p[1].toFloatOrNull(), p[2].firstOrNull(), b(p[3]), p[4].firstOrNull(), p[5].toFloatOrNull(),
                b(p[6]), b(p[7]), b(p[8]), p[9].toDoubleOrNull(), p[10].toDoubleOrNull(), p.getOrNull(11)?.toIntOrNull(),
                p.getOrNull(12)?.firstOrNull(), p.getOrNull(13)?.let { if (it == "-") "" else it.ifEmpty { null } })
        }

        fun parseAll(csv: String): List<Sample> = csv.lineSequence().filter { it.isNotBlank() && !it.startsWith("t,") }.mapNotNull { parse(it) }.toList()
    }
}

object Gear {
    /** Pleos Vehicle SDK의 VehicleGear 이름을 한 글자로 */
    fun fromSdk(name: String?): Char? = when (name) {
        "GEAR_PARK" -> 'P'; "GEAR_DRIVE" -> 'D'; "GEAR_REVERSE" -> 'R'; "GEAR_NEUTRAL" -> 'N'; null -> null; else -> '?'
    }
}

object Cruise {
    /** Pleos Vehicle SDK의 CruiseControl 이름을 한 글자로 */
    fun fromSdk(name: String?): Char? = when (name) { "ACTIVATED" -> 'A'; "ENABLED" -> 'E'; "USER_OVERRIDE" -> 'O'; "SUSPENDED" -> 'S'; null -> null; else -> '0' }
}

object Telltale {
    private val MAP = mapOf("FCA" to 'F', "ESC" to 'E', "ABS" to 'A', "AUTO_HOLD" to 'H')
    /** 켜졌거나 깜빡이는 경고등 이름 목록 → 기록용 글자(F·E·A·H 순) */
    fun flags(onNames: Collection<String>): String = "FEAH".filter { c -> onNames.any { MAP[it] == c } }
    fun ko(c: Char) = when (c) { 'F' -> "FCA(전방 충돌방지)"; 'E' -> "ESC"; 'A' -> "ABS"; 'H' -> "오토홀드"; else -> c.toString() }
}

object Turn {
    fun fromSdk(name: String?): Char? = when (name) { "LEFT" -> 'L'; "RIGHT" -> 'R'; "OFF" -> '0'; null -> null; else -> '0' }
}
