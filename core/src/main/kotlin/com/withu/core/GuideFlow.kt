package com.withu.core

enum class GuideStep(val title: String, val speech: String, val hint: String) {
    EVACUATE("안전한 곳으로 대피", "가드레일 밖이나 인도처럼 안전한 곳으로 이동하세요.", "문이 열리면 안내를 잠시 멈추고, 닫히면 이어 갑니다"),
    HAZARD("비상등 켜기", "비상등을 켜 주세요.", "켰으면 ‘다음 단계’라고 말하거나 버튼을 누르세요"),
    TRUNK("트렁크 열기", "트렁크를 열어 뒤차에 사고를 알려 주세요.", "뒤따르는 차가 멀리서도 알아보게 합니다"),
    CHECK("부상·현장 확인", "다친 사람이 있는지 확인하고, 차량과 주변 사진을 남겨 주세요.", "사진은 휴대폰으로, 기록은 차가 이미 남기고 있어요"),
    REPORT("신고 가이드", "신고 가이드를 열게요. 112, 119, 보험사 순서로 안내합니다.", "말할 내용을 화면에 정리해 드려요"),
}

/** 도로 판단: 고속도로면 대피를 먼저 안내한다 */
object RoadContext {
    const val HIGHWAY_SPEED_KPH = 80f
    fun isHighway(prevSpeedKph: Float?, address: String?): Boolean {
        if (prevSpeedKph != null && prevSpeedKph > HIGHWAY_SPEED_KPH) return true
        val a = address ?: return false
        return a.contains("고속도로") || a.contains("자동차전용") || a.contains("고속화")
    }
    fun steps(highway: Boolean): List<GuideStep> =
        if (highway) listOf(GuideStep.EVACUATE, GuideStep.HAZARD, GuideStep.TRUNK, GuideStep.REPORT)
        else listOf(GuideStep.HAZARD, GuideStep.CHECK, GuideStep.EVACUATE, GuideStep.REPORT)
}

/** 사고 직후 단계별 안내. 대피 단계는 운전석 문 열림/닫힘에 맞춰 멈추고 이어 간다. */
class GuideFlow {
    var steps: List<GuideStep> = emptyList(); private set
    var index = -1; private set
    var paused = false; private set
    var highway = false; private set
    private var doorOpen = false

    val active: Boolean get() = index in steps.indices
    val done: Boolean get() = steps.isNotEmpty() && index >= steps.size
    val current: GuideStep? get() = steps.getOrNull(index)

    fun start(highway: Boolean): GuideStep {
        this.highway = highway; steps = RoadContext.steps(highway); index = 0; paused = false; doorOpen = false
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
            if (current == GuideStep.EVACUATE) index++
            speak = active
        }
        doorOpen = open
        return speak
    }
}
