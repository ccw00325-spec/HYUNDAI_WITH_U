package com.withu.app

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.withu.core.Facts
import com.withu.core.GuideStep
import com.withu.core.Payer
import kotlinx.coroutines.flow.MutableStateFlow

fun L(msg: String) { Log.i("WITHU", msg) }

/** 화면 경로 */
enum class Route { ONBOARD, HOME, CONFIRM, GUIDE, REPORT112, STATEMENT, REPORT_MAKE, REPORT_ISSUED, VAULT, SETTINGS }

/** 최신 차량 신호. null = 미지원 또는 아직 수신 못 함 */
object Sig {
    @Volatile var speedKph: Float? = null
    @Volatile var gear: Char? = null
    @Volatile var brake: Boolean? = null
    @Volatile var turn: Char? = null
    @Volatile var steer: Float? = null
    @Volatile var lowBeam: Boolean? = null
    @Volatile var wiper: Boolean? = null
    @Volatile var driverDoor: Boolean? = null
    @Volatile var airbag: Boolean? = null
    @Volatile var lat: Double? = null
    @Volatile var lon: Double? = null
}

data class Pending(val t: Long, val fromKph: Float, val why: String)          // why: hard_brake+stop+P / siren / airbag
data class Accident(val t: Long, val fromKph: Float, val why: String, val address: String?, val highway: Boolean)
data class GuideUi(val steps: List<GuideStep> = emptyList(), val index: Int = -1, val paused: Boolean = false, val highway: Boolean = false, val done: Boolean = false)
data class StatementRow(val id: Long, val eventT: Long, val t: Long, val opening: String, val transcript: String, val audio: String, val sha: String, val durationS: Int)
data class TripRow(val id: Long, val started: Long, val ended: Long?, val keepUntil: Long, val pin: String?, val chunks: Int)
data class EventRow(val t: Long, val kind: String, val confirmed: Boolean, val fromKph: Float)
data class ReportRow(val rid: String, val anchor: Long, val sha: String, val created: Long, val chainOk: Boolean, val url: String, val payer: String)
data class IssuedReport(val rid: String, val anchor: Long, val url: String, val sha: String, val chainOk: Boolean, val chunks: Int, val samples: Int,
                        val facts: Facts, val payer: Payer, val address: String?, val qr: Bitmap?)
data class RecState(val recording: Boolean = false, val seconds: Int = 0, val level: Float = 0f, val transcribing: Boolean = false)

/** 화면과 서비스가 함께 보는 상태 */
object AppState {
    val route = MutableStateFlow(Route.HOME)
    val back = ArrayDeque<Route>()
    val serviceAlive = MutableStateFlow(false)
    val pending = MutableStateFlow<Pending?>(null)
    val accident = MutableStateFlow<Accident?>(null)
    val guide = MutableStateFlow(GuideUi())
    val address = MutableStateFlow<String?>(null)
    val statements = MutableStateFlow<List<StatementRow>>(emptyList())
    val rec = MutableStateFlow(RecState())
    val trips = MutableStateFlow<List<TripRow>>(emptyList())
    val events = MutableStateFlow<List<EventRow>>(emptyList())
    val reports = MutableStateFlow<List<ReportRow>>(emptyList())
    val issued = MutableStateFlow<IssuedReport?>(null)
    val storageBytes = MutableStateFlow(0L)
    val tick = MutableStateFlow(0L)            // 1초마다 증가 (신호 표시 갱신용)
    val toast = MutableStateFlow<String?>(null)

    fun go(r: Route, push: Boolean = true) {
        val cur = route.value
        if (cur == r) return
        if (push && cur != Route.CONFIRM) back.addLast(cur)
        route.value = r; L("UI route=$r")
    }
    fun pop(): Boolean { val p = back.removeLastOrNull() ?: return false; route.value = p; L("UI back -> $p"); return true }
    fun home() { back.clear(); route.value = Route.HOME; L("UI route=HOME (reset)") }
}

/** 사용자 설정 (차량·보험사). 제휴 보험사는 B2B 계약 예시 목록 */
class Settings(ctx: Context) {
    private val p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    var onboarded: Boolean get() = p.getBoolean("onboarded", false); set(v) = p.edit().putBoolean("onboarded", v).apply()
    var carModel: String get() = p.getString("carModel", "아이오닉 5")!!; set(v) = p.edit().putString("carModel", v).apply()
    var carColor: String get() = p.getString("carColor", "흰색")!!; set(v) = p.edit().putString("carColor", v).apply()
    var plate: String get() = p.getString("plate", "12가 3456")!!; set(v) = p.edit().putString("plate", v).apply()
    var insurer: String get() = p.getString("insurer", PARTNERS.first())!!; set(v) = p.edit().putString("insurer", v).apply()
    var insurerPhone: String get() = p.getString("insurerPhone", "1588-0000")!!; set(v) = p.edit().putString("insurerPhone", v).apply()

    companion object {
        /** B2B 제휴 보험사 (시연용 예시 이름 — 실제 계약사 아님) */
        val PARTNERS = setOf("WiTH U 제휴 예시손해보험")
        val INSURERS = listOf("WiTH U 제휴 예시손해보험", "비제휴 보험사", "모름 / 미가입")
        const val VIEWER = "https://ccw00325-spec.github.io/HYUNDAI_WITH_U/viewer/"
    }
}
