package com.alertbridge.app.ui.driver

import com.alertbridge.engine.AlertBridgeEngine
import com.alertbridge.engine.model.Answer
import com.alertbridge.engine.model.CapabilityMap
import com.alertbridge.engine.model.SeatArea
import com.alertbridge.engine.model.Signal
import com.alertbridge.engine.model.TriageAnswer
import com.alertbridge.engine.model.TriageQ
import com.alertbridge.engine.model.UserActionEvent
import com.alertbridge.engine.model.VehicleEvent
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 엔진 결과 → 운전자 화면 단계. 실제 엔진을 돌려서 확인한다. */
class DriverStepTest {
    private fun engine() = AlertBridgeEngine.withDefaultPolicy().also { it.configure(CapabilityMap.ALL_SUPPORTED) }
    private fun AlertBridgeEngine.sig(t: Long, s: Signal) = onEvent(VehicleEvent(t, s))
    private fun AlertBridgeEngine.hardStop(a: Long) {
        sig(a - 400, Signal.BrakeState(true))
        listOf(0L to 40f, 600L to 30f, 1100L to 20f, 2100L to 10f, 3600L to 0f).forEach { (dt, v) -> sig(a + dt, Signal.VehicleSpeed(v)) }
    }

    @Test
    fun `사건이 없으면 대기`() {
        assertEquals(DriverStep.IDLE, driverStep(engine().currentResult(0)))
    }

    @Test
    fun `급정차 뒤 사고 확인, 예라고 하면 질문, 다 답하면 대응 계획`() {
        val e = engine()
        e.sig(0, Signal.VehicleSpeed(40f))
        e.hardStop(10_000)
        assertEquals(DriverStep.CONFIRM, driverStep(e.currentResult(14_000)))
        e.onTriage(TriageAnswer(TriageQ.IS_ACCIDENT, Answer.YES, 15_000))
        assertEquals(DriverStep.TRIAGE, driverStep(e.currentResult(15_000)))
        listOf(TriageQ.IS_EXPRESSWAY, TriageQ.INJURY, TriageQ.FIRE_SMOKE, TriageQ.DRIVABLE, TriageQ.OTHER_PARTY_FLED, TriageQ.OTHER_PARTY_SUSPICIOUS)
            .forEachIndexed { i, q -> e.onTriage(TriageAnswer(q, Answer.NO, 16_000L + i)) }
        assertEquals(DriverStep.PLAN, driverStep(e.currentResult(17_000)))
    }

    @Test
    fun `아니오면 대기로 돌아간다`() {
        val e = engine()
        e.sig(0, Signal.VehicleSpeed(40f))
        e.hardStop(10_000)
        e.onTriage(TriageAnswer(TriageQ.IS_ACCIDENT, Answer.NO, 15_000))
        assertEquals(DriverStep.IDLE, driverStep(e.currentResult(15_000)))
    }

    @Test
    fun `에어백이 터지면 질문이 남아도 대응 계획을 먼저 보여 준다`() {
        val e = engine()
        e.sig(0, Signal.SeatAirbagDeployed(SeatArea.ROW1_LEFT, true))
        val r = e.currentResult(1)!!
        assertEquals(TriageQ.IS_ACCIDENT, r.pendingQuestion)
        assertEquals(DriverStep.PLAN, driverStep(r))
    }

    @Test
    fun `무응답이면 대응 계획과 SOS 안내`() {
        val e = engine()
        e.sig(0, Signal.VehicleSpeed(40f))
        e.hardStop(10_000)
        val r = e.currentResult(44_000)!!
        assertEquals(DriverStep.PLAN, driverStep(r))
        assertTrue(isNoResponse(r))
    }

    @Test
    fun `차가 움직이는 중이면 질문을 화면에 띄우지 않는다`() {
        val e = engine()
        e.onUserAction(UserActionEvent.ManualReport(1_000))
        e.sig(2_000, Signal.VehicleSpeed(30f))
        assertEquals(DriverStep.VOICE_ONLY, driverStep(e.currentResult(2_000)))
    }

    @Test
    fun `남은 초는 올림`() {
        val e = engine()
        e.sig(0, Signal.VehicleSpeed(40f))
        e.hardStop(10_000)
        val r = e.currentResult(14_000)!!
        assertEquals(30, secondsLeft(r, r.questionDeadlineMs!! - 29_001))
        assertEquals(0, secondsLeft(r, r.questionDeadlineMs!! + 5))
    }
}
