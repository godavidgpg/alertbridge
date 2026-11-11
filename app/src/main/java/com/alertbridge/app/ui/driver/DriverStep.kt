package com.alertbridge.app.ui.driver

import com.alertbridge.engine.model.EngineResult
import com.alertbridge.engine.model.ReasonCode
import com.alertbridge.engine.model.RiskGrade
import com.alertbridge.engine.model.SessionState
import com.alertbridge.engine.model.TriageQ

/** 운전자 화면 단계. 엔진 결과만으로 정해지며 UI는 판단하지 않는다. */
enum class DriverStep {
    /** 사건 없음 */
    IDLE,
    /** ① "괜찮으세요? 사고인가요?" */
    CONFIRM,
    /** ② 한 번에 한 질문 */
    TRIAGE,
    /** 주행 중: 화면 질문 대신 음성만 */
    VOICE_ONLY,
    /** ③ 대응 계획 (R0면 질문이 남아 있어도 조치를 먼저 보여 준다) */
    PLAN,
}

fun driverStep(r: EngineResult?): DriverStep {
    if (r == null || r.session.state == SessionState.DISMISSED || r.session.state == SessionState.CLOSED) return DriverStep.IDLE
    val q = r.pendingQuestion
    val lifeRisk = RiskGrade.R0 in r.risk.has
    return when {
        q != null && r.vehicleMoving -> DriverStep.VOICE_ONLY
        q == TriageQ.IS_ACCIDENT && !lifeRisk -> DriverStep.CONFIRM
        q != null && !lifeRisk -> DriverStep.TRIAGE
        else -> DriverStep.PLAN
    }
}

/** 사고 확인 질문에 답이 없어 R0가 된 상태. 화면 맨 위에 SOS 안내를 크게 띄운다. */
fun isNoResponse(r: EngineResult): Boolean = r.risk.grades.any { ReasonCode.CONFIRM_NO_RESPONSE in it.because }

/** 남은 초. 카운트다운 표시용 */
fun secondsLeft(r: EngineResult, nowMs: Long): Int? =
    r.questionDeadlineMs?.let { ((it - nowMs).coerceAtLeast(0) + 999) / 1000 }?.toInt()
