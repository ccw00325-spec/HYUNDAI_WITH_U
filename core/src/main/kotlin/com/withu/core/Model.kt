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
) {
    fun csv(): String {
        fun b(v: Boolean?) = when (v) { null -> ""; true -> "1"; false -> "0" }
        fun f1(v: Float?) = v?.let { String.format(java.util.Locale.ROOT, "%.1f", it) } ?: ""
        fun f6(v: Double?) = v?.let { String.format(java.util.Locale.ROOT, "%.6f", it) } ?: ""
        return listOf(t.toString(), f1(spd), gear?.toString() ?: "", b(brk), turn?.toString() ?: "", f1(steer),
            b(low), b(wiper), b(door), f6(lat), f6(lon)).joinToString(",")
    }

    companion object {
        const val HEADER = "t,spd,gear,brk,turn,steer,low,wiper,door,lat,lon"

        /** CSV 한 줄을 읽는다. 시각이 없거나 칸이 모자라면 null */
        fun parse(line: String): Sample? {
            val p = line.split(",")
            if (p.size < 11) return null
            val t = p[0].toLongOrNull() ?: return null
            fun b(s: String) = when (s) { "1" -> true; "0" -> false; else -> null }
            return Sample(t, p[1].toFloatOrNull(), p[2].firstOrNull(), b(p[3]), p[4].firstOrNull(), p[5].toFloatOrNull(),
                b(p[6]), b(p[7]), b(p[8]), p[9].toDoubleOrNull(), p[10].toDoubleOrNull())
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

object Turn {
    fun fromSdk(name: String?): Char? = when (name) { "LEFT" -> 'L'; "RIGHT" -> 'R'; "OFF" -> '0'; null -> null; else -> '0' }
}
