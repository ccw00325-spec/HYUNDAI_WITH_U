package com.withu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 에뮬레이터에서 실제로 발급한 리포트 원본 신호로 사실표를 다시 만든다 (웹 리포트 계산과 같은 결과인지 대조용 파일도 남김) */
class RealReportFactsTest {
    private fun load(rid: String): Triple<List<Sample>, Long, String?> {
        val j = File("../docs/viewer/reports/$rid.json").readText()
        val csv = Regex("\"samples_csv\":\"(.*?)\"").find(j)!!.groupValues[1].replace("\\n", "\n")
        val t = Regex("\"event\":\\{\"t\":(\\d+)").find(j)!!.groupValues[1].toLong()
        val addr = Regex("\"address\":\"([^\"]*)\"").find(j)?.groupValues?.get(1)
        return Triple(Sample.parseAll(csv), t, addr)
    }

    @Test fun laneChangeReport_fromEmulator() {
        val out = StringBuilder()
        for (rid in listOf("R-20261113-011502-3173", "R-20261114-005437-A48C", "R-20261114-011213-C4DB")) {
            val (rows, t, addr) = load(rid)
            FaultFactSheet.build(rows, t, addr).forEach { out.append("$rid|${it.key}|${it.level}|${it.lean}|${it.text}|${it.markT}\n") }
        }
        File("build/factsheet_kotlin.txt").writeText(out.toString())
        val (rows, t, addr) = load("R-20261113-011502-3173")
        val r = FaultFactSheet.build(rows, t, addr).associateBy { it.key }
        assertEquals(Lean.FOR_DRIVER, r["signal"]!!.lean)
        assertTrue(r["signal"]!!.text, r["signal"]!!.text.contains("법정 30m 이상"))
        assertEquals(Level.CONFIRMED, r["night_light"]!!.level)
    }
}
