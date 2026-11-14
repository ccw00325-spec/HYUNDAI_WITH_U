package com.withu.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.withu.app.AppState
import com.withu.app.MainActivity
import com.withu.app.Pending
import com.withu.app.RecorderService
import com.withu.app.ReportService
import com.withu.app.Route
import com.withu.app.Settings
import com.withu.app.Sig
import com.withu.core.GuideStep
import com.withu.core.IssueCheck
import com.withu.core.Payer
import com.withu.core.Level
import com.withu.core.Lean
import com.withu.core.FactorRow
import com.withu.core.Retention
import com.withu.core.Sample
import com.withu.core.StatementPrompt
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private val SEOUL: TimeZone = TimeZone.getTimeZone("Asia/Seoul")
private fun kfmt(p: String) = SimpleDateFormat(p, Locale.KOREA).apply { timeZone = SEOUL }
private val hms = kfmt("HH:mm:ss")
private val md = kfmt("M월 d일 (E)")
private val ymdhms = kfmt("yyyy.MM.dd HH:mm:ss")
private fun svc() = RecorderService.inst
private fun fmtKph(v: Float?) = v?.let { "%.0f".format(it) } ?: "-"

@Composable
fun WithUApp(act: MainActivity) {
    val route by AppState.route.collectAsState()
    val toast by AppState.toast.collectAsState()
    LaunchedEffect(toast) { if (toast != null) { delay(3500); AppState.toast.value = null } }
    BoxWithConstraints(Modifier.fillMaxSize().background(C.bg)) {
        val wide = maxWidth >= 1200.dp
        Column(Modifier.fillMaxSize()) {
            TopBar(route, act)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (route) {
                    Route.ONBOARD -> Onboard(act, wide)
                    Route.HOME -> Home(wide)
                    Route.CONFIRM -> Confirm(wide)
                    Route.GUIDE -> Guide(wide)
                    Route.REPORT112 -> Report112(act, wide)
                    Route.STATEMENT -> Statement(wide)
                    Route.REPORT_MAKE -> ReportMake(act, wide)
                    Route.REPORT_ISSUED -> ReportIssued(wide)
                    Route.VAULT -> Vault(act, wide)
                    Route.SETTINGS -> SettingsScreen(act, wide)
                }
            }
        }
        toast?.let { msg ->
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp).clip(RoundedCornerShape(24.dp)).background(C.navy).padding(horizontal = 36.dp, vertical = 22.dp)) {
                T(msg, 26.sp, Color.White, FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun TopBar(route: Route, act: MainActivity) {
    val alive by AppState.serviceAlive.collectAsState()
    val pending by AppState.pending.collectAsState()
    val accident by AppState.accident.collectAsState()
    val guide by AppState.guide.collectAsState()
    AppState.tick.collectAsState().value
    Row(Modifier.fillMaxWidth().height(104.dp).background(Color.White).padding(horizontal = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.clickable { if (route != Route.ONBOARD && route != Route.CONFIRM) AppState.home() }, verticalAlignment = Alignment.CenterVertically) {
            T("WiT", 38.sp, C.navy, FontWeight.ExtraBold); T("H", 38.sp, C.brand, FontWeight.ExtraBold); T(" U", 38.sp, C.navy, FontWeight.ExtraBold)
        }
        if (route != Route.HOME && route != Route.ONBOARD && route != Route.CONFIRM) {
            Box(Modifier.width(24.dp)); Chip("‹ 뒤로", ChipKind.PLAIN) { if (!AppState.pop()) AppState.home() }
        }
        Spacer1()
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            when {
                pending != null -> Chip("사고 확인 대기", ChipKind.REC, dot = true)
                accident != null && guide.index >= 0 && !guide.done && (route == Route.GUIDE || route == Route.REPORT112 || route == Route.STATEMENT) -> Chip("사고 대응 모드", ChipKind.REC, dot = true)
                alive -> Chip("기록 중 · 7일 보관", ChipKind.OK, dot = true)
                else -> Chip("기록 준비 중", ChipKind.PLAIN)
            }
            val g = Sig.gear; val s = Sig.speedKph
            Chip(when { g == 'P' -> "P · 주차"; g == null -> "기어 확인 중"; (s ?: 0f) < 0.5f -> "$g · 정차"; else -> "$g · ${fmtKph(s)}km/h" }, ChipKind.INFO)
            if (route == Route.HOME) Chip("설정", ChipKind.PLAIN) { AppState.go(Route.SETTINGS) }
            if (route != Route.ONBOARD) Chip("최소화  —", ChipKind.PLAIN) { act.minimize() }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(C.line))
}

/** 넓은 창은 좌우 2단, 좁은 창(내비 옆 세로)은 위아래로 쌓고 스크롤 */
@Composable
private fun TwoCol(wide: Boolean, leftWeight: Float = 1.25f, rightWeight: Float = 1f, left: @Composable ColumnScope.() -> Unit, right: @Composable ColumnScope.() -> Unit) {
    if (wide) Row(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 40.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        Column(Modifier.weight(leftWeight).fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(28.dp), content = left)
        Column(Modifier.weight(rightWeight).fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(28.dp), content = right)
    } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 34.dp, vertical = 30.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) { left(); right() }
}

// ======================= 처음 시작 =======================
@Composable
private fun Onboard(act: MainActivity, wide: Boolean) {
    val rows = listOf(
        Triple(Ico.BRAKE, "무엇을 기록하나요", "속도, 브레이크, 방향지시등, 기어, 조향각, 전조등, 와이퍼, 운전석 문, 위치, 크루즈·경고등, 도로 제한속도. 영상은 남기지 않아요"),
        Triple(Ico.ARCHIVE, "얼마나 보관하나요", "모든 운행은 7일 뒤 자동으로 지워요. 사고로 확인했거나 리포트를 발급한 운행만 1년간 보존해요"),
        Triple(Ico.SHIELD, "언제 밖으로 나가나요", "차 안에만 암호화해 두고, 리포트를 발급할 때만 그 시각 앞뒤 5분(모두 10분) 기록을 휴대폰으로 넘겨요"),
    )
    Row(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = if (wide) 80.dp else 34.dp, vertical = 40.dp), horizontalArrangement = Arrangement.spacedBy(60.dp), verticalAlignment = Alignment.CenterVertically) {
        if (wide) Wedi(Mood.HAPPY, 300.dp)
        Column(Modifier.weight(1f)) {
            H1("안녕하세요, 위디예요.\n운전하는 동안 곁에서 기록할게요", if (wide) 58.sp else 44.sp)
            rows.forEach { (ic, t, d) -> Row(Modifier.padding(top = 30.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) { IconBox(ic, C.soft, C.brand); Column { T(t, 31.sp, weight = FontWeight.ExtraBold); D(d, size = 25.sp) } } }
            Btn("동의하고 시작하기", BtnKind.PRI, Modifier.padding(top = 44.dp).width(520.dp)) {
                act.settings.onboarded = true; act.requestRuntimePermissions(); AppState.home()
            }
        }
    }
}

// ======================= 홈 =======================
@Composable
private fun Home(wide: Boolean) {
    val trips by AppState.trips.collectAsState()
    val events by AppState.events.collectAsState()
    val bytes by AppState.storageBytes.collectAsState()
    AppState.tick.collectAsState().value
    val now = System.currentTimeMillis()
    val pinned = trips.count { it.pin != null }
    TwoCol(wide, left = {
        WCard(bg = C.navy) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(30.dp)) {
                Wedi(Mood.HAPPY, 150.dp)
                Column {
                    T("위디", 26.sp, Color.White.copy(alpha = .7f), FontWeight.SemiBold)
                    H1("오늘도 곁에서\n조용히 기록할게요", 50.sp, Color.White)
                    D("주행 기록은 7일 뒤 자동으로 지워요. 사고로 확인한 기록만 1년간 보존해요.", C.navySub, modifier = Modifier.padding(top = 12.dp))
                }
            }
        }
        // 사이렌 버튼
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(C.warn).clickable { svc()?.siren("home_button") }.padding(horizontal = 44.dp, vertical = 38.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                IconBox(Ico.SIREN, Color.White, C.warn, 110.dp, round = true)
                Column {
                    H1("사고가 났어요", 56.sp, Color.White)
                    D("누르면 사고인지 한 번 묻고, 지금 위치에 맞춰 대응과 신고를 안내해요 · 주행 중에는 위디 버블을 길게 누르세요", Color.White.copy(alpha = .9f))
                }
            }
        }
        WCard {
            Row(verticalAlignment = Alignment.CenterVertically) { H2("이렇게 사고를 알아채요"); Spacer1(); Tag("묻는 건 이때뿐") }
            Row(Modifier.padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf("급제동", "정차", "P단 변경").forEachIndexed { i, s ->
                    Box(Modifier.clip(RoundedCornerShape(16.dp)).background(if (i == 2) C.brand else C.soft).padding(horizontal = 22.dp, vertical = 14.dp)) { T(s, 26.sp, if (i == 2) Color.White else C.brand, FontWeight.ExtraBold) }
                    if (i < 2) T("→", 28.sp, C.mute, FontWeight.Bold)
                }
            }
            D("신호 대기처럼 D단으로 섰다가 다시 출발하면 묻지 않아요. 기록은 그대로 7일간 남아 있어요.", modifier = Modifier.padding(top = 16.dp))
        }
    }, right = {
        WCard {
            Row(verticalAlignment = Alignment.CenterVertically) { H2("기록 보관함"); Spacer1(); K("최근 7일") }
            Row(Modifier.padding(top = 22.dp).fillMaxWidth()) {
                Stat("운행", trips.count { now - it.started <= 7 * Retention.DAY }.toString(), "건", Modifier.weight(1f))
                Stat("1년 보존", pinned.toString(), "건", Modifier.weight(1f), if (pinned > 0) C.warn else C.navy)
                Stat("저장 공간", "%.1f".format(bytes / 1_048_576.0), "MB", Modifier.weight(1f))
            }
            Btn("보관함 보기", BtnKind.SEC, Modifier.fillMaxWidth().padding(top = 22.dp), 88.dp, 28.sp) { AppState.go(Route.VAULT) }
        }
        WCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) { IconBox(Ico.DOC, C.soft, C.brand); Column { H2("사고 리포트 제작"); K("날짜·시각만 고르면 앞뒤 5분 기록으로") } }
            events.firstOrNull { it.kind == "ACCIDENT_CONFIRMED" || it.kind == "HARD_BRAKE" || it.kind == "SIREN" }?.let { e ->
                D("최근 기록된 순간: ${md.format(Date(e.t))} ${hms.format(Date(e.t))} · ${kindName(e.kind)}", modifier = Modifier.padding(top = 14.dp))
            }
            Btn("리포트 만들기", BtnKind.PRI, Modifier.fillMaxWidth().padding(top = 18.dp), 88.dp, 28.sp) { AppState.go(Route.REPORT_MAKE) }
        }
        WCard {
            H2("이 차량에서 기록되는 신호")
            val sig = listOf(Triple(Ico.SPEED, "속도", Sig.speedKph != null), Triple(Ico.GEAR, "기어", Sig.gear != null), Triple(Ico.BRAKE, "브레이크", Sig.brake != null),
                Triple(Ico.TURN, "방향지시등", Sig.turn != null), Triple(Ico.STEER, "조향각", Sig.steer != null), Triple(Ico.LIGHT, "전조등", Sig.lowBeam != null),
                Triple(Ico.WIPER, "와이퍼", Sig.wiper != null), Triple(Ico.DOOR, "운전석 문", Sig.driverDoor != null), Triple(Ico.PIN, "위치", Sig.lat != null))
            sig.chunked(2).forEach { pair ->
                Row(Modifier.padding(top = 14.dp)) {
                    pair.forEach { (ic, n, ok) ->
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            IconBox(ic, C.soft, C.brand, 52.dp); T(n, 26.sp, weight = FontWeight.Bold); Spacer1()
                            T(if (ok) "연결" else "대기", 24.sp, if (ok) C.okFg else C.mute, FontWeight.ExtraBold); Box(Modifier.width(18.dp))
                        }
                    }
                    if (pair.size == 1) Box(Modifier.weight(1f))
                }
            }
        }
    })
}

@Composable private fun Stat(k: String, v: String, unit: String, modifier: Modifier, color: Color = C.navy) = Column(modifier) {
    K(k); Row(verticalAlignment = Alignment.Bottom) { T(v, 56.sp, color, FontWeight.ExtraBold); T(unit, 28.sp, C.sub, FontWeight.Bold, Modifier.padding(start = 6.dp, bottom = 8.dp)) }
}

/** 발급한 리포트 다시 열기 (파일이 깨졌어도 화면이 죽지 않게) */
private fun openReport(act: MainActivity, rid: String) {
    val s = svc() ?: return
    try { ReportService.show(act, s.store, rid) } catch (e: Exception) { com.withu.app.L("ERROR reopen $rid ${e.javaClass.simpleName}: ${e.message}"); AppState.toast.value = "리포트 파일을 열지 못했어요" }
}

private fun kindName(k: String) = when (k) { "HARD_BRAKE" -> "급제동"; "SIREN" -> "사이렌"; "ACCIDENT_CONFIRMED" -> "사고 확인"; "DISMISSED" -> "사고 아님 응답"; "AIRBAG" -> "에어백 전개"; else -> k }

// ======================= 사고 확인 =======================
@Composable
private fun Confirm(wide: Boolean) {
    val p by AppState.pending.collectAsState()
    val pend = p ?: Pending(System.currentTimeMillis(), 0f, "manual")
    val siren = pend.why == "siren"
    TwoCol(wide, 1.4f, 0.8f, left = {
        WCard(pad = 56.dp) {
            Tag(if (siren) "사이렌 버튼 · ${hms.format(Date(pend.t))}" else "급제동 → 정차 → P단 · ${hms.format(Date(pend.t))}", C.recBg, C.warn, 26.sp)
            H1("지금 사고가 난\n상황인가요?", if (wide) 84.sp else 64.sp, modifier = Modifier.padding(top = 26.dp, bottom = 18.dp))
            D(if (siren) "사이렌 버튼을 누르셨어요. 아니라면 ‘아니요’를 눌러 주세요." else "시속 ${fmtKph(pend.fromKph)}km에서 급제동한 뒤 멈추고, P단으로 바꾸셨어요.", size = 30.sp)
            D("앞뒤 기록은 이미 차 안에 안전하게 남기고 있어요.", size = 30.sp)
            val btns: @Composable (Modifier, Modifier) -> Unit = { a, b ->
                Btn("예, 사고가 났어요", BtnKind.WARN, a) { svc()?.answer(true) }
                Btn("아니요, 괜찮아요", BtnKind.SEC, b) { svc()?.answer(false) }
            }
            if (wide) Row(Modifier.padding(top = 48.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) { btns(Modifier.weight(1.2f), Modifier.weight(1f)) }
            else Column(Modifier.padding(top = 40.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) { btns(Modifier.fillMaxWidth(), Modifier.fillMaxWidth()) }
        }
    }, right = {
        WCard { Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Wedi(Mood.WORRY, 190.dp); H2("많이 놀라셨죠?"); D("천천히 하셔도 돼요.\n위디가 순서대로 도와드릴게요.") } }
        WCard { K("‘아니요’를 누르면"); T("기록은 그대로 7일간 보관돼요. 나중에 ‘사고 리포트 제작’에서 이 시각을 골라 꺼낼 수 있어요.", 27.sp, weight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp)) }
    })
}

// ======================= 대응 안내 =======================
@Composable
private fun Guide(wide: Boolean) {
    val g by AppState.guide.collectAsState()
    val acc by AppState.accident.collectAsState()
    val addr by AppState.address.collectAsState()
    val cur = g.steps.getOrNull(g.index)
    TwoCol(wide, 1f, 1.1f, left = {
        WCard {
            Row(verticalAlignment = Alignment.CenterVertically) { H2("지금 안전부터 확보하세요"); Spacer1() }
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Tag(g.road, if (g.highway) C.recBg else C.soft, if (g.highway) C.warn else C.brand)
                K(addr ?: acc?.address ?: Sig.lat?.let { "%.4f, %.4f".format(it, Sig.lon) } ?: "위치 확인 중", size = 23.sp)
            }
            g.steps.forEachIndexed { i, s ->
                val state = when { g.done || i < g.index -> "done"; i == g.index -> "now"; else -> "next" }
                Row(Modifier.fillMaxWidth().padding(vertical = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Box(Modifier.size(64.dp).clip(CircleShape).background(when (state) { "done" -> C.okBg; "now" -> C.brand; else -> C.sec }), contentAlignment = Alignment.Center) {
                        T(if (state == "done") "✓" else "${i + 1}", 30.sp, when (state) { "done" -> C.okFg; "now" -> Color.White; else -> C.sub }, FontWeight.ExtraBold)
                    }
                    Column(Modifier.weight(1f)) {
                        T(s.title, if (wide) 32.sp else 30.sp, if (state == "next") C.navy.copy(alpha = .45f) else C.navy, FontWeight.ExtraBold)
                        K(if (state == "now" && g.paused) "운전석 문이 열려 안내를 잠시 멈췄어요" else s.hint, if (state == "now" && g.paused) C.warn else C.sub)
                    }
                }
                Divider()
            }
        }
    }, right = {
        WCard(bg = C.navy) {
            Tag(when { g.done -> "안내 완료"; g.paused -> "${g.index + 1}단계 · 일시정지"; else -> "${g.index + 1}단계 / ${g.steps.size}" }, C.recBg, C.warn)
            H1(if (g.done) "안내를 모두 마쳤어요" else cur?.title ?: "", if (wide) 64.sp else 46.sp, Color.White, Modifier.padding(top = 22.dp))
            D(if (g.done) "이제 신고 가이드로 말할 내용을 확인하고, 생각날 때 진술을 녹음해 두세요." else if (g.paused) "다시 타고 문을 닫으면 이어서 안내할게요." else cur?.speech ?: "", C.navySub, modifier = Modifier.padding(top = 18.dp))
            Row(Modifier.padding(top = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.clickable { svc()?.listenForNext() }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Wave(16, 5, C.navyAccent); T("“다음 단계”라고 말해도 돼요", 26.sp, C.navyAccent, FontWeight.Bold)
                }
                Spacer1(); if (wide) Wedi(Mood.GUIDE, 120.dp)
            }
            if (cur == GuideStep.REPORT || g.done) Btn("신고 가이드 열기", BtnKind.PRI, Modifier.fillMaxWidth().padding(top = 10.dp)) { AppState.go(Route.REPORT112) }
            if (!g.done) Btn(if (cur == GuideStep.REPORT) "안내 마치기" else "다음 단계", if (cur == GuideStep.REPORT) BtnKind.GHOST else BtnKind.PRI, Modifier.fillMaxWidth().padding(top = 10.dp), enabled = !g.paused) { svc()?.guideNext("button") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Btn("신고 가이드", BtnKind.SEC, Modifier.weight(1f), 96.dp, 28.sp) { AppState.go(Route.REPORT112) }
            Btn("진술 녹음", BtnKind.SEC, Modifier.weight(1f), 96.dp, 28.sp) { AppState.go(Route.STATEMENT) }
            Btn("리포트 제작", BtnKind.SEC, Modifier.weight(1f), 96.dp, 28.sp) { AppState.go(Route.REPORT_MAKE) }
        }
    })
}

// ======================= 신고 가이드 =======================
@Composable
private fun Report112(act: MainActivity, wide: Boolean) {
    val acc by AppState.accident.collectAsState()
    val addr by AppState.address.collectAsState()
    val g by AppState.guide.collectAsState()
    val s = act.settings
    val t = acc?.t ?: System.currentTimeMillis()
    val done = g.steps.take(if (g.done) g.steps.size else maxOf(g.index, 0)).filter { it != GuideStep.REPORT }.joinToString(", ") { it.title }
    val lines = listOf(
        "위치" to ((addr ?: acc?.address ?: "주소 확인 중") + (Sig.lat?.let { "\n좌표 %.4f, %.4f".format(it, Sig.lon) } ?: "")),
        "시각" to "${md.format(Date(t))} ${hms.format(Date(t))}경",
        "상황" to when {
            acc == null -> "교통사고가 났습니다"
            acc?.why == "siren" -> "교통사고가 났습니다 (직접 신고)"
            (acc?.fromKph ?: 0f) < 1f -> "교통사고가 났습니다"
            else -> "시속 ${fmtKph(acc?.fromKph)}km로 가다 급제동 후 사고가 났습니다"
        },
        "부상" to "다친 사람이 있는지 확인해 말해 주세요",
        "내 차" to "${s.carColor} ${s.carModel} · ${s.plate}",
        "현재" to (if (done.isBlank()) "안전 조치 중입니다" else "${done}까지 마쳤습니다"),
    )
    TwoCol(wide, 1.55f, 1f, left = {
        WCard(pad = 44.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) { H2("신고할 때 이렇게 말씀하세요", size = 40.sp); Spacer1(); Tag("자동 작성") }
            lines.forEach { (k, v) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Box(Modifier.width(96.dp).clip(RoundedCornerShape(12.dp)).background(C.sec).padding(vertical = 6.dp), contentAlignment = Alignment.Center) { T(k, 22.sp, C.sub, FontWeight.ExtraBold) }
                    T(v, 31.sp, weight = FontWeight.Bold)
                }
                Divider()
            }
            Row(Modifier.padding(top = 24.dp).clickable { svc()?.say(lines.joinToString(". ") { "${it.first}, ${it.second.substringBefore('\n')}" }) }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Wave(12); T("한 줄씩 읽어 드릴게요 · 눌러서 듣기", 26.sp, C.brand, FontWeight.Bold)
            }
        }
    }, right = {
        val nums = mutableListOf(Triple("112", "경찰 · 사고 신고", true), Triple("119", "구급 · 화재", false), Triple(s.insurerPhone, "${s.insurer} · 사고 접수", false))
        if (acc?.highway == true) nums.add(2, Triple("1588-2504", "한국도로공사 · 고속도로 사고", false))
        nums.forEach { (n, d, main) ->
            WCard(pad = 30.dp) { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                IconBox(Ico.PHONE, if (main) C.brand else C.sec, if (main) Color.White else C.navy); Column { T(n, 48.sp, weight = FontWeight.ExtraBold); K(d) } } }
        }
        WCard { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Wedi(Mood.GUIDE, 110.dp); D("번호는 차량 전화나 휴대폰으로 걸어 주세요. 통화하는 동안 이 화면을 보면서 차분히 말씀하시면 돼요.", size = 25.sp) } }
    })
}

// ======================= 진술 녹음 =======================
@Composable
private fun Statement(wide: Boolean) {
    val rec by AppState.rec.collectAsState()
    val list by AppState.statements.collectAsState()
    val acc by AppState.accident.collectAsState()
    val opening = remember(acc) { svc()?.statementOpening() ?: StatementPrompt.opening(System.currentTimeMillis(), null) }
    TwoCol(wide, 1.4f, 1f, left = {
        WCard(bg = C.navy, pad = 50.dp) {
            K("위디가 묻습니다", C.navyAccent); H1("이렇게 시작해 보세요", if (wide) 52.sp else 42.sp, Color.White, Modifier.padding(top = 10.dp))
            T("“$opening …”", 34.sp, Color.White, FontWeight.Bold, Modifier.padding(top = 18.dp))
            Column(Modifier.padding(top = 22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StatementPrompt.QUESTIONS.forEachIndexed { i, q -> T("${i + 1}. $q", 26.sp, C.navySub) }
            }
            Box(Modifier.fillMaxWidth().padding(vertical = 28.dp), contentAlignment = Alignment.Center) { Wave(40, 7, C.brand2, 110.dp, if (rec.recording) rec.level * 3 else 0.2f) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Btn(if (rec.recording) "■  녹음 끝내기 (${rec.seconds}초)" else "●  녹음 시작", if (rec.recording) BtnKind.WARN else BtnKind.PRI, Modifier.weight(1f)) {
                    if (rec.recording) svc()?.stopRecording() else svc()?.startRecording()
                }
            }
            K(if (rec.transcribing) "받아쓰는 중이에요…" else "말이 끝나면 ‘녹음 끝내기’를 눌러 주세요 · 녹음 원본과 확인값은 사고 기록과 함께 1년 보존", C.navyAccent, 22.sp)
        }
    }, right = {
        WCard {
            H2("저장된 진술")
            if (list.isEmpty()) D("아직 녹음한 진술이 없어요.", modifier = Modifier.padding(top = 16.dp))
            list.take(4).forEach { s ->
                Column(Modifier.padding(top = 18.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(C.bg).padding(horizontal = 24.dp, vertical = 20.dp)) {
                    Row { K("${hms.format(Date(s.t))} · ${s.durationS}초"); Spacer1(); K("SHA ${s.sha.take(8)}", C.okFg, 20.sp) }
                    T(s.transcript.ifBlank { "(받아쓰기 결과 없음 — 녹음 원본은 저장됨)" }, 27.sp, if (s.transcript.isBlank()) C.mute else C.navy, FontWeight.SemiBold, Modifier.padding(top = 6.dp))
                }
            }
        }
        WCard { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Wedi(Mood.CALM, 100.dp); D("기억은 시간이 지나면 흐려져요. 생각나는 대로 천천히 말씀해 주세요. 상대방과의 대화는 녹음하지 않아요.", size = 25.sp) } }
    })
}

// ======================= 사고 리포트 제작 =======================
@Composable
private fun ReportMake(act: MainActivity, wide: Boolean) {
    val events by AppState.events.collectAsState()
    val trips by AppState.trips.collectAsState()
    val acc by AppState.accident.collectAsState()
    AppState.tick.collectAsState().value
    val init = remember { acc?.t ?: events.firstOrNull { it.kind == "ACCIDENT_CONFIRMED" }?.t ?: events.firstOrNull()?.t ?: (System.currentTimeMillis() - 600_000) }
    var t by remember { mutableLongStateOf(init) }
    var preview by remember { mutableStateOf<List<Sample>>(emptyList()) }
    var plan by remember { mutableStateOf<ReportService.Plan?>(null) }
    LaunchedEffect(t, AppState.tick.value / 5000) {
        val s = svc() ?: return@LaunchedEffect
        try { val p = ReportService.plan(s.store, s.settings, t, unsealed = s.pendingSamples()); plan = p; preview = s.previewWindow(p.anchor) }
        catch (e: Exception) { com.withu.app.L("ERROR report plan ${e.javaClass.simpleName}: ${e.message}") }
    }
    val days = (0..6).map { d -> Calendar.getInstance(SEOUL).apply { add(Calendar.DAY_OF_YEAR, -d); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis }
    val dayOfT = days.firstOrNull { t >= it } ?: days.last()
    TwoCol(wide, 1f, 1.15f, left = {
        WCard {
            H2("1. 사고 날짜")
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                days.reversed().forEach { d ->
                    val has = trips.any { it.started < d + Retention.DAY && (it.ended ?: Long.MAX_VALUE) >= d }
                    val sel = d == dayOfT
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(if (sel) C.brand else if (has) C.soft else C.sec).clickable(enabled = has) { t = d + (t - dayOfT) }.padding(vertical = 14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        T(kfmt("E").format(Date(d)), 20.sp, if (sel) Color.White else C.sub, FontWeight.Bold)
                        T(kfmt("d").format(Date(d)), 32.sp, if (sel) Color.White else if (has) C.brand else C.mute, FontWeight.ExtraBold)
                    }
                }
            }
            K("파란 날짜에 운행 기록이 있어요 · 7일이 지난 기록은 자동으로 지워져요", size = 21.sp)
        }
        WCard {
            H2("2. 사고 시각")
            T(hms.format(Date(t)), 84.sp, C.navy, FontWeight.ExtraBold, Modifier.padding(top = 6.dp))
            // 좁은 버튼에서 ‘−1시간’이 두 줄로 꺾이지 않게 앞으로·뒤로를 두 줄로 나눈다
            listOf(listOf("−1시간" to -3_600_000L, "−1분" to -60_000L, "−10초" to -10_000L), listOf("+10초" to 10_000L, "+1분" to 60_000L, "+1시간" to 3_600_000L)).forEach { row ->
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { (l, d) -> Btn(l, BtnKind.SEC, Modifier.weight(1f), 72.dp, 24.sp) { t += d } }
                }
            }
            val dayEvents = events.filter { it.t in dayOfT until dayOfT + Retention.DAY && it.kind != "DISMISSED" }
            if (dayEvents.isNotEmpty()) {
                K("이날 기록된 순간 · 누르면 그 시각으로", modifier = Modifier.padding(top = 20.dp))
                Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    dayEvents.take(5).forEach { e ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (e.t == t) C.soft else C.bg).clickable { t = e.t }.padding(horizontal = 22.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Tag(kindName(e.kind), if (e.confirmed) C.recBg else C.soft, if (e.confirmed) C.warn else C.brand); Box(Modifier.width(16.dp))
                            T(hms.format(Date(e.t)), 28.sp, weight = FontWeight.ExtraBold); Spacer1(); K("${fmtKph(e.fromKph)}km/h")
                        }
                    }
                }
            }
        }
    }, right = {
        val p = plan
        WCard {
            Row(verticalAlignment = Alignment.CenterVertically) { H2("앞뒤 5분 미리보기"); Spacer1(); if (p != null) K("기록 ${(p.coverage * 100).toInt()}%") }
            if (p?.anchorKind != null && p.anchor != t) K("고른 시각 근처의 ‘${kindName(p.anchorKind)}’ ${hms.format(Date(p.anchor))}을 기준으로 맞췄어요", C.brand, 22.sp)
            SpeedChart(preview, p?.anchor ?: t, Modifier.fillMaxWidth().height(300.dp).padding(top = 14.dp))
            val (msg, col) = when (val c = p?.check) {
                null -> "기록을 찾는 중…" to C.sub
                IssueCheck.Ok -> "리포트를 만들 수 있어요 · 샘플 ${p.samples}개" to C.okFg
                is IssueCheck.Collecting -> "직후 5분 기록이 모이는 중이에요 · ${c.remainMs / 60_000}분 ${(c.remainMs / 1000) % 60}초 남음" to C.amber
                is IssueCheck.Future -> "아직 오지 않은 시각이에요" to C.warn
                IssueCheck.NoData -> "이 시각의 기록이 없어요 (운행 기록 없음 또는 7일 경과)" to C.warn
                is IssueCheck.Duplicate -> "이미 발급한 리포트가 있어요 (${c.rid}) · 다시 결제하지 않아요" to C.brand
            }
            T(msg, 26.sp, col, FontWeight.Bold, Modifier.padding(top = 14.dp))
        }
        WCard {
            H2("3. 비용")
            val payer = p?.payer
            Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                IconBox(Ico.SHIELD, C.okBg, C.okFg)
                Column {
                    T(when (payer) { is Payer.Insurer -> "보험사 부담 · 0원"; is Payer.Personal -> "개인 결제 · %,d원".format(payer.priceWon); null -> "-" }, 32.sp, weight = FontWeight.ExtraBold)
                    K(when (payer) { is Payer.Insurer -> "${payer.name}와 B2B 계약 · 건당 비용을 보험사가 내요"; else -> "휴대폰에서 QR을 열면 결제 후 전체 리포트가 열려요" })
                }
            }
            val c = p?.check
            Btn(if (c is IssueCheck.Duplicate) "발급한 리포트 보기" else "리포트 발급", BtnKind.PRI, Modifier.fillMaxWidth().padding(top = 22.dp), enabled = c == IssueCheck.Ok || c is IssueCheck.Duplicate) {
                val s = svc() ?: return@Btn
                if (c is IssueCheck.Duplicate) openReport(act, c.rid) else s.issueReport(t)
            }
            K("발급하면 이 운행 기록은 1년간 보존돼요 · 과실 비율은 판단하지 않아요", size = 21.sp, modifier = Modifier.padding(top = 12.dp))
        }
    })
}

@Composable
private fun SpeedChart(rows: List<Sample>, anchor: Long, modifier: Modifier) {
    Canvas(modifier.clip(RoundedCornerShape(18.dp)).background(C.bg)) {
        val w = size.width; val h = size.height; val pad = 18f
        fun x(t: Long) = pad + (t - (anchor - 300_000)).toFloat() / 600_000f * (w - 2 * pad)
        val maxV = maxOf(80f, rows.maxOfOrNull { it.spd ?: 0f } ?: 0f)
        fun y(v: Float) = h - 50f - v / maxV * (h - 90f)
        for (v in listOf(0f, maxV / 2, maxV)) drawLine(C.line, Offset(pad, y(v)), Offset(w - pad, y(v)), 2f)
        rows.filter { it.turn == 'L' || it.turn == 'R' }.forEach { drawLine(C.amber.copy(alpha = .35f), Offset(x(it.t), h - 40f), Offset(x(it.t), h - 20f), 3f) }
        rows.filter { it.brk == true }.forEach { drawLine(C.warn.copy(alpha = .35f), Offset(x(it.t), h - 18f), Offset(x(it.t), h - 4f), 3f) }
        val path = Path(); var started = false
        rows.forEach { s -> val v = s.spd ?: return@forEach; if (!started) { path.moveTo(x(s.t), y(v)); started = true } else path.lineTo(x(s.t), y(v)) }
        if (started) drawPath(path, C.brand, style = Stroke(5f, join = StrokeJoin.Round))
        drawLine(C.navy, Offset(x(anchor), 10f), Offset(x(anchor), h - 4f), 4f)
    }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp)) { K("−5분", size = 20.sp); Spacer1(); K("기준 ${hms.format(Date(anchor))}", C.navy, 20.sp); Spacer1(); K("+5분", size = 20.sp) }
    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) { K("— 속도", C.brand, 20.sp); K("▮ 방향지시등", C.amber, 20.sp); K("▮ 브레이크", C.warn, 20.sp) }
}

// ======================= 리포트 발급 완료 · QR =======================
@Composable
private fun ReportIssued(wide: Boolean) {
    val r by AppState.issued.collectAsState()
    val rep = r ?: return Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { D("발급한 리포트가 없어요") }
    Row(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = if (wide) 64.dp else 34.dp, vertical = 40.dp), horizontalArrangement = Arrangement.spacedBy(60.dp), verticalAlignment = Alignment.CenterVertically) {
        if (wide) WCard(pad = 36.dp, fill = false) { rep.qr?.let { Image(it.asImageBitmap(), "리포트 QR", Modifier.size(500.dp)) } }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Chip(if (rep.chainOk) "무결성 확인됨" else "무결성 불일치", if (rep.chainOk) ChipKind.OK else ChipKind.REC, dot = true)
                Chip("1년 보존", ChipKind.INFO)
            }
            H1("휴대폰 카메라로\n찍어 주세요", if (wide) 58.sp else 44.sp)
            if (!wide) rep.qr?.let { Image(it.asImageBitmap(), "리포트 QR", Modifier.size(420.dp)) }
            D("보험 담당자가 한눈에 보는 웹 리포트가 열리고, 같은 내용을 PDF로 받을 수 있어요.", size = 28.sp)
            WCard(pad = 28.dp) {
                KV("리포트 번호", rep.rid)
                KV("기준 시각", ymdhms.format(Date(rep.anchor)))
                KV("기록 범위", "앞뒤 5분 · ${rep.samples}개 신호 · ${rep.chunks}개 묶음 검증")
                KV("비용", when (val p = rep.payer) { is Payer.Insurer -> "보험사 부담 (${p.name})"; is Payer.Personal -> "개인 %,d원".format(p.priceWon) }, C.brand)
            }
            if (rep.factors.isNotEmpty()) FactorSheet(rep.factors)
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Btn("보관함", BtnKind.SEC, Modifier.weight(1f), 96.dp, 28.sp) { AppState.go(Route.VAULT) }
                Btn("홈으로", BtnKind.PRI, Modifier.weight(1f), 96.dp, 28.sp) { AppState.home() }
            }
        }
    }
}

/** 수정요소 사실표: 과실비율 인정기준 항목 순서로 차량 기록 근거를 보여 준다 (비율은 계산하지 않음) */
@Composable
private fun FactorSheet(rows: List<FactorRow>) {
    val forN = rows.count { it.lean == Lean.FOR_DRIVER }; val againstN = rows.count { it.lean == Lean.AGAINST_DRIVER }
    WCard(pad = 28.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            H2("수정요소 사실표", size = 30.sp); Spacer1()
            Tag("뒷받침 $forN", C.okBg, C.okFg); Tag("불리할 수 있음 $againstN", C.recBg, C.warn)
        }
        K("과실비율 인정기준 항목 순서 · 비율은 계산하지 않아요", size = 22.sp, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp))
        rows.forEach { r ->
            val (bg, fg) = when (r.lean) { Lean.FOR_DRIVER -> C.okBg to C.okFg; Lean.AGAINST_DRIVER -> C.recBg to C.warn; else -> C.bg to C.sub }
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(Modifier.width(300.dp)) { T(r.factor, 24.sp, weight = FontWeight.Bold); Tag(r.level.ko, bg, fg, 20.sp) }
                T(r.text, 24.sp, if (r.level == Level.UNKNOWN || r.level == Level.NA) C.sub else C.navy, modifier = Modifier.weight(1f))
            }
            Divider()
        }
    }
}

// ======================= 기록 보관함 =======================
@Composable
private fun Vault(act: MainActivity, wide: Boolean) {
    val trips by AppState.trips.collectAsState()
    val reports by AppState.reports.collectAsState()
    val bytes by AppState.storageBytes.collectAsState()
    val now = System.currentTimeMillis()
    TwoCol(wide, 1.3f, 1f, left = {
        WCard {
            Row(verticalAlignment = Alignment.CenterVertically) { H2("운행 기록"); Spacer1(); K("${trips.size}건 · %.1fMB".format(bytes / 1_048_576.0)) }
            if (trips.isEmpty()) D("아직 운행 기록이 없어요.", modifier = Modifier.padding(top = 16.dp))
            trips.take(12).forEach { tr ->
                Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    IconBox(Ico.CAR, if (tr.pin != null) C.recBg else C.soft, if (tr.pin != null) C.warn else C.brand)
                    Column(Modifier.weight(1f)) {
                        T("${md.format(Date(tr.started))} ${hms.format(Date(tr.started)).take(5)} → ${tr.ended?.let { hms.format(Date(it)).take(5) } ?: "운행 중"}", 28.sp, weight = FontWeight.Bold)
                        K("1분 묶음 ${tr.chunks}개 · 모두 서명됨")
                    }
                    if (tr.pin != null) Tag("1년 보존 · ${if (tr.pin == "report_issued") "리포트 발급" else "사고 확인"}", C.recBg, C.warn)
                    else Tag(if (tr.ended == null) "기록 중" else "D-${Retention.daysLeft(tr.keepUntil, now)} 자동 삭제", C.sec, C.sub)
                }
                Divider()
            }
        }
    }, right = {
        WCard(bg = C.navy) {
            H2("보관 규칙", Color.White)
            listOf("모든 운행은 끝난 뒤 7일이 지나면 하나씩 자동으로 지워요", "사고로 확인하거나 리포트를 발급한 운행은 1년간 보존해요", "기록은 고칠 수 없어요. 1분마다 확인값을 이어 붙이고 차량 키로 서명해요")
                .forEach { T("· $it", 26.sp, C.navySub, modifier = Modifier.padding(top = 12.dp)) }
        }
        WCard {
            H2("발급한 리포트")
            if (reports.isEmpty()) D("아직 발급한 리포트가 없어요.", modifier = Modifier.padding(top = 16.dp))
            reports.take(5).forEach { r ->
                Row(Modifier.fillMaxWidth().padding(top = 16.dp).clip(RoundedCornerShape(18.dp)).background(C.bg).clickable { openReport(act, r.rid) }.padding(horizontal = 22.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { T(r.rid, 24.sp, weight = FontWeight.ExtraBold); K("${ymdhms.format(Date(r.anchor))} · ${if (r.payer == "INSURER") "보험사 부담" else "개인 결제"}", size = 21.sp) }
                    Tag(if (r.chainOk) "검증됨" else "불일치", if (r.chainOk) C.okBg else C.recBg, if (r.chainOk) C.okFg else C.warn)
                }
            }
        }
    })
}

// ======================= 설정 =======================
@Composable
private fun SettingsScreen(act: MainActivity, wide: Boolean) {
    val s = act.settings
    var model by remember { mutableStateOf(s.carModel) }
    var color by remember { mutableStateOf(s.carColor) }
    var plate by remember { mutableStateOf(s.plate) }
    var insurer by remember { mutableStateOf(s.insurer) }
    var phone by remember { mutableStateOf(s.insurerPhone) }
    TwoCol(wide, 1f, 1f, left = {
        WCard {
            H2("내 차 정보 · 신고 가이드에 쓰여요")
            Field("차종", model) { model = it }; Field("색상", color) { color = it }; Field("차량 번호", plate) { plate = it }
        }
    }, right = {
        WCard {
            H2("가입 보험사")
            Settings.INSURERS.forEach { name ->
                Row(Modifier.fillMaxWidth().padding(top = 14.dp).clip(RoundedCornerShape(18.dp)).background(if (insurer == name) C.soft else C.bg).clickable { insurer = name }.padding(horizontal = 24.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    T(name, 27.sp, if (insurer == name) C.brand else C.navy, FontWeight.Bold); Spacer1()
                    if (name in Settings.PARTNERS) Tag("제휴 · 리포트 무료", C.okBg, C.okFg)
                }
            }
            Field("보험사 사고 접수 번호", phone) { phone = it }
            Btn("저장", BtnKind.PRI, Modifier.fillMaxWidth().padding(top = 24.dp), 96.dp, 30.sp) {
                s.carModel = model; s.carColor = color; s.plate = plate; s.insurer = insurer; s.insurerPhone = phone
                AppState.toast.value = "저장했어요"; AppState.pop()
            }
        }
    })
}

@Composable private fun Field(label: String, value: String, onChange: (String) -> Unit) = Column(Modifier.padding(top = 18.dp)) {
    K(label)
    BasicTextField(value, onChange, singleLine = true, textStyle = TextStyle(fontFamily = Pretendard, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = C.navy),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).border(2.dp, C.line, RoundedCornerShape(16.dp)).padding(horizontal = 22.dp, vertical = 18.dp))
}
