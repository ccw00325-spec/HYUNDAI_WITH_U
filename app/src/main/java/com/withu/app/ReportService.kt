package com.withu.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.withu.core.Billing
import com.withu.core.FactExtractor
import com.withu.core.Hash
import com.withu.core.HashChain
import com.withu.core.Issue
import com.withu.core.IssueCheck
import com.withu.core.Payer
import com.withu.core.ReportId
import com.withu.core.ReportWindow
import com.withu.core.Sample
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 사고 리포트 발급: 앞뒤 5분 묶음을 꺼내 사슬·서명을 검증하고, 보험 담당자용 JSON·QR을 만든다. 발급 시 그 운행을 1년 보존한다. */
object ReportService {
    data class Plan(val anchor: Long, val anchorKind: String?, val check: IssueCheck, val samples: Int, val coverage: Double, val payer: Payer)

    fun plan(store: Store, settings: Settings, userT: Long, now: Long = System.currentTimeMillis()): Plan {
        val (anchor, mark) = ReportWindow.anchor(userT, store.eventMarks(userT, ReportWindow.SNAP_MS))
        val rows = store.chunksBetween(anchor - ReportWindow.HALF_MS, anchor + ReportWindow.HALF_MS).sumOf { c -> Sample.parseAll(c.data).count { it.t in ReportWindow.range(anchor) } }
        val check = Issue.check(anchor, now, rows, store.reportAt(anchor)?.rid)
        return Plan(anchor, mark?.kind, check, rows, Issue.coverage(rows), Billing.payer(settings.insurer, Settings.PARTNERS))
    }

    /** 반환: 사람이 읽는 결과 한 줄 */
    fun issue(ctx: Context, store: Store, settings: Settings, userT: Long, address: String?): String {
        val now = System.currentTimeMillis()
        val p = plan(store, settings, userT, now)
        when (val c = p.check) {
            is IssueCheck.Duplicate -> { show(ctx, store, c.rid); return "DUPLICATE ${c.rid} (no new charge)" }
            is IssueCheck.Future -> { AppState.toast.value = "아직 오지 않은 시각이에요"; return "FUTURE" }
            is IssueCheck.Collecting -> { AppState.toast.value = "직후 5분 기록이 모이는 중이에요 (${c.remainMs / 1000}초 남음)"; return "COLLECTING ${c.remainMs}ms" }
            IssueCheck.NoData -> { AppState.toast.value = "그 시각의 기록이 없어요 (7일이 지났거나 운행 기록 없음)"; return "NO_DATA" }
            IssueCheck.Ok -> {}
        }
        val anchor = p.anchor
        val chunks = store.chunksBetween(anchor - ReportWindow.HALF_MS, anchor + ReportWindow.HALF_MS)
        val checks = HashChain.verify(chunks) { store.verifySig(it) }
        val chainOk = checks.all { it.ok }
        checks.filter { !it.ok }.forEach { L("REPORT chunk m0=${it.chunk.m0} hash_ok=${it.hashOk} link_ok=${it.linkOk} sig_ok=${it.sigOk}  <-- MISMATCH") }
        val rows = chunks.flatMap { Sample.parseAll(it.data) }.filter { it.t in ReportWindow.range(anchor) }.sortedBy { it.t }
        val facts = FactExtractor.extract(rows, anchor)
        val at = rows.lastOrNull { it.t <= anchor }
        val rid = ReportId.make(anchor, "$now")
        val stmts = JSONArray().apply {
            store.statements(anchor).forEach { put(JSONObject().put("t", it.t).put("opening", it.opening).put("transcript", it.transcript)
                .put("audio", File(it.audio).name).put("audio_sha256", it.sha).put("duration_s", it.durationS)) }
        }
        val payerJson = when (val pay = p.payer) {
            is Payer.Insurer -> JSONObject().put("type", "INSURER").put("name", pay.name).put("note", "B2B 제휴 계약에 따라 보험사가 건당 비용 부담")
            is Payer.Personal -> JSONObject().put("type", "PERSONAL").put("price_won", pay.priceWon).put("note", "휴대폰 웹 리포트에서 건당 결제 (결제 연동은 출시 단계)")
        }
        val f = JSONObject()
            .put("speed_at_event_kph", facts.speedAtEventKph ?: JSONObject.NULL)
            .put("max_speed_30s_kph", facts.maxSpeed30sKph ?: JSONObject.NULL)
            .put("turn_signal", facts.turn?.let { JSONObject().put("side", if (it.side == 'R') "RIGHT" else "LEFT").put("on_before_s", it.onBeforeS).put("distance_before_m", it.distanceM).put("speed_at_on_kph", it.speedAtOnKph ?: JSONObject.NULL) } ?: JSONObject().put("side", "NONE"))
            .put("brake_on_before_s", facts.brakeOnBeforeS ?: JSONObject.NULL)
            .put("max_abs_steer_10s_deg", facts.maxAbsSteer10sDeg)
            .put("low_beam_at_event", facts.lowBeam ?: JSONObject.NULL).put("wiper_at_event", facts.wiper ?: JSONObject.NULL)
            .put("gear_P_after_s", facts.parkAfterS ?: JSONObject.NULL).put("driver_door_open_after_s", facts.doorOpenAfterS ?: JSONObject.NULL)
        val o = JSONObject()
            .put("schema", "withu.report/1").put("rid", rid).put("issued_at", now)
            .put("app", JSONObject().put("name", "WiTH U").put("version", BuildConfig.VERSION_NAME).put("platform", "Pleos Connect (Android Automotive 14)"))
            .put("vehicle", JSONObject().put("model", settings.carModel).put("color", settings.carColor).put("plate", settings.plate))
            .put("event", JSONObject().put("t", anchor).put("user_time", userT).put("anchor", p.anchorKind ?: "user_time"))
            .put("window", JSONObject().put("from", anchor - ReportWindow.HALF_MS).put("to", anchor + ReportWindow.HALF_MS).put("hz", 10).put("samples", rows.size).put("coverage", p.coverage))
            .put("location", JSONObject().put("lat", at?.lat ?: JSONObject.NULL).put("lon", at?.lon ?: JSONObject.NULL).put("address", address ?: JSONObject.NULL))
            .put("facts", f)
            .put("unsupported", JSONArray(listOf("가속 페달", "비상등", "브레이크 압력", "충격 세기", "차선 종류", "상대 차량")))
            .put("statements", stmts)
            .put("payer", payerJson)
            .put("samples_csv", Sample.HEADER + "\n" + rows.joinToString("\n") { it.csv() })
            .put("proof", JSONObject().put("alg", "SHA-256 hash chain per minute + ECDSA P-256 (Android Keystore)").put("pubkey", store.publicKeyB64())
                .put("chain_ok", chainOk).put("chunks", JSONArray().apply { checks.forEach { k -> put(JSONObject().put("trip", k.chunk.trip).put("m0", k.chunk.m0).put("m1", k.chunk.m1).put("n", k.chunk.n)
                    .put("prev", k.chunk.prev).put("hash", k.chunk.hash).put("sig", k.chunk.sig).put("data", k.chunk.data).put("ok", k.ok)) } }))
            .put("retention", JSONObject().put("pinned_days", 365).put("reason", "report_issued"))
            .put("notice", "이 리포트는 차량이 기록한 조작 신호를 그대로 보여 줄 뿐, 과실 비율을 판단하지 않습니다.")
        val json = o.toString()
        val sha = Hash.sha256(json)
        val url = ReportId.viewerUrl(Settings.VIEWER, rid, sha)
        val dir = File(ctx.filesDir, "reports").also { it.mkdirs() }
        val jf = File(dir, "$rid.json"); jf.writeText(json)
        val qr = qr(url); File(dir, "${rid}_qr.png").outputStream().use { qr.compress(Bitmap.CompressFormat.PNG, 100, it) }
        store.insertReport(ReportRow(rid, anchor, sha, now, chainOk, url, payerJson.getString("type")), jf.absolutePath)
        store.pinTripsBetween(anchor - ReportWindow.HALF_MS, anchor + ReportWindow.HALF_MS, now, "report_issued").also { L("RETENTION report window pinned trips=$it") }
        AppState.issued.value = IssuedReport(rid, anchor, url, sha, chainOk, chunks.size, rows.size, facts, p.payer, address, qr)
        L("REPORT issued rid=$rid anchor=$anchor(${p.anchorKind ?: "user_time"}) samples=${rows.size} chunks=${chunks.size} chain_ok=$chainOk bytes=${json.length} sha256=$sha")
        L("REPORT facts speed=${facts.speedAtEventKph} turn=${facts.turn} brake=${facts.brakeOnBeforeS} steer=${facts.maxAbsSteer10sDeg} park=${facts.parkAfterS} door=${facts.doorOpenAfterS}")
        L("QR url=$url")
        android.os.Handler(android.os.Looper.getMainLooper()).post { AppState.go(Route.REPORT_ISSUED) }
        return "ISSUED $rid chain_ok=$chainOk"
    }

    /** 이미 발급한 리포트 다시 보기 */
    fun show(ctx: Context, store: Store, rid: String) {
        val r = store.reports().firstOrNull { it.rid == rid } ?: return
        val jf = File(ctx.filesDir, "reports/$rid.json")
        if (!jf.exists()) return
        val o = JSONObject(jf.readText())
        val rows = Sample.parseAll(o.getString("samples_csv"))
        val payer = o.getJSONObject("payer").let { if (it.getString("type") == "INSURER") Payer.Insurer(it.getString("name")) else Payer.Personal(it.getInt("price_won")) }
        AppState.issued.value = IssuedReport(rid, r.anchor, r.url, r.sha, r.chainOk, o.getJSONObject("proof").getJSONArray("chunks").length(), rows.size,
            FactExtractor.extract(rows, r.anchor), payer, o.getJSONObject("location").optString("address").takeIf { it.isNotBlank() && it != "null" }, qr(r.url))
        android.os.Handler(android.os.Looper.getMainLooper()).post { AppState.go(Route.REPORT_ISSUED) }
        L("REPORT reopened $rid")
    }

    fun qr(text: String): Bitmap {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 640, 640, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2))
        val px = IntArray(m.width * m.height) { i -> if (m.get(i % m.width, i / m.width)) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(px, m.width, m.height, Bitmap.Config.ARGB_8888)
    }
}
