package com.alertbridge.engine

import com.alertbridge.engine.fixture.Scenario
import com.alertbridge.engine.fixture.mismatches
import com.alertbridge.engine.model.*
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoldenScenarioTest {

    @Test
    fun `필수 시나리오 12개가 모두 있다`() {
        val ids = Fixtures.scenarios.map { it.id.take(3) }.toSet()
        assertEquals((1..12).map { "S%02d".format(it) }.toSet(), ids)
    }

    @TestFactory
    fun golden(): List<DynamicTest> = Fixtures.scenarios.map { s ->
        DynamicTest.dynamicTest(s.id) { check(s) }
    }

    private fun check(s: Scenario) {
        val r = assertNotNull(run(s).result, "결과 없음")
        val diff = s.expected.mismatches(r)
        assertTrue(diff.isEmpty(), diff.toString())
    }

    @Test
    fun `S02 - 깜빡이와 조향이 함께 있으면 낮은 신뢰도로 오른쪽`() {
        val z = run(Fixtures.scenario("S02")).result!!.damage.impactZones.single { it.zone == Zone.RIGHT }
        assertEquals(Confidence.LOW, z.confidence)
    }

    @Test
    fun `S05 - 자동 발신 없이 보호자 알림만 자동이고 알림을 보내도 세션은 확정되지 않는다`() {
        val run = run(Fixtures.scenario("S05"))
        val r = run.result!!
        assertEquals(listOf(ActionId.NOTIFY_GUARDIAN), r.plan.actions.filter { it.autoExecutable }.map { it.id })
        run.engine.onUserAction(UserActionEvent.GuardianNotified(run.endMs + 1))
        val after = run.engine.currentResult(run.endMs + 2)!!
        assertEquals(ActionStatus.CONTACT_ATTEMPTED, after.plan.find(ActionId.NOTIFY_GUARDIAN)?.status)
        assertEquals(SessionState.SAFETY_CHECK, after.session.state)
    }

    @Test
    fun `S02 - 제어된 급정차는 속도 차가 계산되고 S2 기준보다 작다`() {
        val deltaV = assertNotNull(run(Fixtures.scenario("S02")).result!!.damage.deltaVKmh)
        assertTrue(deltaV > 0f && deltaV < Fixtures.policy.thresholds.deltaVS2Kmh, "deltaV=$deltaV")
    }

    @Test
    fun `S10 - 사고 전부터 있던 경고는 근거에 없다`() {
        val d = run(Fixtures.scenario("S10")).result!!.damage
        assertTrue(ReasonCode.FLUID_LOW_NEW !in d.because)
        assertTrue(d.evidence.none { it.detail == "ENGINE_OIL" })
    }

    @Test
    fun `S11 - 접수번호를 입력해야 비로소 확정된다`() {
        val run = run(Fixtures.scenario("S11"))
        assertEquals(SessionState.CONTACT_ATTEMPTED, run.result!!.session.state)
        run.engine.onUserAction(UserActionEvent.ReceiptNumberEntered(ActionId.CALL_INSURER_CLAIM, "  ", run.endMs + 1))
        assertEquals(SessionState.CONTACT_ATTEMPTED, run.engine.currentResult(run.endMs + 1)!!.session.state, "빈 번호는 무시")
        run.engine.onUserAction(UserActionEvent.ReceiptNumberEntered(ActionId.CALL_INSURER_CLAIM, "A-123", run.endMs + 2))
        val done = run.engine.currentResult(run.endMs + 2)!!
        assertEquals(SessionState.CONFIRMED, done.session.state)
        assertEquals(ActionStatus.CONFIRMED, done.plan.find(ActionId.CALL_INSURER_CLAIM)?.status)
        assertEquals("A-123", done.session.caseCard!!.receiptNumbers[ActionId.CALL_INSURER_CLAIM])
    }

    @Test
    fun `S12 - 사고가 아니면 잡아 둔 신호를 모두 버린다`() {
        val run = run(Fixtures.scenario("S12"))
        assertNull(run.result!!.session.caseCard)
        assertEquals(0, run.engine.retainedEventCount())
        assertEquals(emptyList(), run.result!!.plan.actions)
    }
}
