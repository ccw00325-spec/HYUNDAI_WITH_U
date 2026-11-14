package com.withu.core

enum class GuideStep(val title: String, val speech: String, val hint: String) {
    EVACUATE("안전한 곳으로 대피", "가드레일 밖이나 인도처럼 안전한 곳으로 이동하세요. 차로를 건너거나 차 뒤에 서 있지 마세요.", "문이 열리면 안내를 잠시 멈추고, 닫히면 이어 갑니다"),
    SHOULDER("움직이면 갓길로", "차가 움직이면 비상등을 켠 채 오른쪽 갓길 끝으로 천천히 옮겨 주세요. 움직이지 않으면 그대로 두세요.", "차가 움직이지 않으면 바로 ‘다음 단계’를 누르세요"),
    HAZARD("비상등 켜기", "비상등을 켜 주세요.", "켰으면 ‘다음 단계’라고 말하거나 버튼을 누르세요"),
    TRUNK("트렁크 열기", "트렁크를 열어 뒤차에 사고를 알려 주세요.", "뒤따르는 차가 멀리서도 알아보게 합니다"),
    TUNNEL_STOP("갓길에 세우고 시동 끄기", "터널 안이에요. 차가 움직이면 갓길에 바짝 세우고 시동을 꺼 주세요. 열쇠는 꽂아 둔 채로 두세요.", "구조대가 차를 옮길 수 있도록 열쇠를 두고 내립니다"),
    TUNNEL_EXIT("유도등 따라 비상구로", "벽의 유도등을 따라 가까운 비상구나 터널 입구로 걸어서 나가세요. 연기가 보이면 자세를 낮추세요.", "차로 쪽으로 걷지 말고 벽을 따라 이동하세요"),
    CHECK("부상·현장 확인", "다친 사람이 있는지 확인하고, 차량과 주변 사진을 남겨 주세요.", "사진은 휴대폰으로, 기록은 차가 이미 남기고 있어요"),
    REPORT("신고 가이드", "신고 가이드를 열게요. 112, 119, 보험사 순서로 안내합니다.", "말할 내용을 화면에 정리해 드려요"),
}

/**
 * 도로 판단과 안내 순서.
 * 고속도로는 한국도로공사 2차 사고 예방 요령 '비트밖스'(비상등 → 트렁크 → 가드레일 밖 대피 → 스마트폰 신고)에
 * 차가 움직일 때 갓길로 옮기는 단계를 더한다. 일반 도로는 비상등 → 부상·현장 확인 → 대피 → 신고.
 */
enum class RoadType(val ko: String) { GENERAL("일반 도로"), HIGHWAY("고속도로"), TUNNEL("터널") }

object RoadContext {
    const val HIGHWAY_SPEED_KPH = 80f
    fun isHighway(prevSpeedKph: Float?, address: String?): Boolean {
        if (prevSpeedKph != null && prevSpeedKph > HIGHWAY_SPEED_KPH) return true
        val a = address ?: return false
        return a.contains("고속도로") || a.contains("자동차전용") || a.contains("고속화")
    }
    /** 위치(주소)와 직전 속도로 도로 유형을 고른다. 주소에 '터널'이 있으면 고속도로여도 터널 요령을 따른다 */
    fun classify(prevSpeedKph: Float?, address: String?): RoadType = when {
        address?.contains("터널") == true -> RoadType.TUNNEL
        isHighway(prevSpeedKph, address) -> RoadType.HIGHWAY
        else -> RoadType.GENERAL
    }
    fun steps(road: RoadType): List<GuideStep> = when (road) {
        RoadType.TUNNEL -> listOf(GuideStep.HAZARD, GuideStep.TUNNEL_STOP, GuideStep.TUNNEL_EXIT, GuideStep.REPORT)
        else -> steps(road == RoadType.HIGHWAY)
    }
    fun steps(highway: Boolean): List<GuideStep> =
        if (highway) listOf(GuideStep.HAZARD, GuideStep.SHOULDER, GuideStep.TRUNK, GuideStep.EVACUATE, GuideStep.REPORT)
        else listOf(GuideStep.HAZARD, GuideStep.CHECK, GuideStep.EVACUATE, GuideStep.REPORT)
}

/** 사고 직후 단계별 안내. 문이 열리면 멈추고, 차 밖에서 하는 단계(트렁크·대피)는 문이 닫히면 다음으로 넘어간다. */
class GuideFlow {
    companion object { val OUTSIDE = setOf(GuideStep.TRUNK, GuideStep.EVACUATE, GuideStep.TUNNEL_EXIT) }
    var steps: List<GuideStep> = emptyList(); private set
    var index = -1; private set
    var paused = false; private set
    var highway = false; private set
    var road = RoadType.GENERAL; private set
    private var doorOpen = false

    val active: Boolean get() = index in steps.indices
    val done: Boolean get() = steps.isNotEmpty() && index >= steps.size
    val current: GuideStep? get() = steps.getOrNull(index)

    fun start(highway: Boolean): GuideStep = start(if (highway) RoadType.HIGHWAY else RoadType.GENERAL)

    fun start(road: RoadType): GuideStep {
        this.road = road; this.highway = road != RoadType.GENERAL; steps = RoadContext.steps(road); index = 0; paused = false; doorOpen = false
        return steps[0]
    }

    /** 다음 단계로. 멈춤 중이거나 끝났으면 아무것도 하지 않고 false */
    fun next(): Boolean {
        if (!active || paused) return false
        index++
        return true
    }

    /** 운전석 문 상태 변화. 반환값: 안내를 다시 말해야 하면 true */
    fun onDoor(open: Boolean): Boolean {
        if (!active) { doorOpen = open; return false }
        var speak = false
        if (open && !doorOpen) paused = true
        if (!open && doorOpen && paused) {
            paused = false
            if (current in OUTSIDE) index++
            speak = active
        }
        doorOpen = open
        return speak
    }
}
