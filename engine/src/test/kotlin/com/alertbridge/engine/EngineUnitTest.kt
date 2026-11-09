package com.alertbridge.engine

import com.alertbridge.engine.coverage.CoverageMatcher
import com.alertbridge.engine.model.*
import com.alertbridge.engine.policy.PolicyError
import com.alertbridge.engine.policy.PolicyLoader
import com.alertbridge.engine.policy.renderPolicyDoc
import com.alertbridge.engine.session.SessionMachine
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun engine(
    caps: CapabilityMap = CapabilityMap.ALL_SUPPORTED,
    driver: OccupantProfile? = null,
    vehicle: VehicleProfile? = null,
) = AlertBridgeEngine(Fixtures.policy, Fixtures.messages).also { it.configure(caps, driver, vehicle) }

private fun AlertBridgeEngine.sig(t: Long, s: Signal) = onEvent(VehicleEvent(t, s))
private fun AlertBridgeEngine.speed(t: Long, kmh: Float) = sig(t, Signal.VehicleSpeed(kmh))
private fun AlertBridgeEngine.answer(t: Long, q: TriageQ, a: Answer) = onTriage(TriageAnswer(q, a, t))

/** 40km/h에서 제어된 급정차. [a]가 감속 시작 시각이다. */
private fun AlertBridgeEngine.controlledStop(a: Long) {
    sig(a - 400, Signal.BrakeState(true))
    speed(a, 40f); speed(a + 600, 30f); speed(a + 1100, 20f); speed(a + 2100, 10f); speed(a + 3600, 0f)
}

private fun road(type: RoadType, confidence: Float = 0.9f, name: String? = "테스트로") =
    Signal.Location(37.5, 127.0, 5f, type, confidence, name)

private val insurance = InsurancePolicy("한빛손해보험", "P-1", "1588-0001", "1588-0002", setOf(Coverage.DAEMUL, Coverage.JASIN), freeTowKm = 40)
private val driver = OccupantProfile("d", "운전자", insurance = insurance)

class EngineUnitTest {

    @Nested
    inner class Detection {
        @Test fun `수동 신고는 바로 사고로 보고 사고 여부를 묻지 않는다`() {
            val e = engine()
            e.onUserAction(UserActionEvent.ManualReport(100))
            val r = e.currentResult(100)!!
            assertEquals(TriggerId.T_MANUAL, r.candidate.trigger)
            assertEquals(TriggerConfidence.CONFIRMED, r.candidate.confidence)
            assertEquals(IncidentKind.ACCIDENT, r.candidate.kind)
            assertTrue(r.pendingQuestion != TriageQ.IS_ACCIDENT)
        }

        @Test fun `에어백은 응답을 기다리지 않고 R0 계획을 낸다`() {
            val e = engine()
            e.sig(0, Signal.SeatAirbagDeployed(SeatArea.ROW1_LEFT, true))
            val r = e.currentResult(1)!!
            assertEquals(TriggerId.T_AIRBAG, r.candidate.trigger)
            assertEquals(TriageQ.IS_ACCIDENT, r.pendingQuestion, "질문은 한다")
            assertEquals(ActionId.CALL_119, r.plan.first()?.id, "그러나 계획은 이미 있다")
        }

        @Test fun `급정차 조건을 만족해야 트리거가 난다`() {
            val e = engine()
            e.controlledStop(10_000)
            val r = e.currentResult(14_000)!!
            assertEquals(TriggerId.T_HARD_STOP, r.candidate.trigger)
            assertEquals(TriggerConfidence.HIGH, r.candidate.confidence)
            assertEquals(10_000, r.candidate.suspicionMs)
        }

        @Test fun `3초에 30km 미만 감속은 급정차가 아니다`() {
            val e = engine()
            e.sig(0, Signal.BrakeState(true))
            e.speed(0, 40f); e.speed(3000, 20f); e.speed(6000, 0f)
            assertNull(e.currentResult(7000))
        }

        @Test fun `브레이크를 지원하고 밟지 않았으면 급정차가 아니다`() {
            val e = engine()
            e.sig(0, Signal.BrakeState(false))
            e.speed(0, 40f); e.speed(1000, 5f); e.speed(2000, 0f)
            assertNull(e.currentResult(3000))
        }

        @Test fun `브레이크를 읽을 수 없으면 신뢰도를 낮춰 트리거한다`() {
            val caps = CapabilityMap(CapabilityKey.entries.associateWith { it != CapabilityKey.BRAKE_PEDAL })
            val e = engine(caps)
            e.speed(0, 40f); e.speed(1000, 5f); e.speed(2000, 0f)
            val r = e.currentResult(3000)!!
            assertEquals(TriggerId.T_HARD_STOP, r.candidate.trigger)
            assertEquals(TriggerConfidence.MEDIUM, r.candidate.confidence)
        }

        @Test fun `10초 안에 멈추지 않으면 급정차가 아니다`() {
            val e = engine()
            e.sig(0, Signal.BrakeState(true))
            e.speed(0, 40f); e.speed(1000, 8f); e.speed(12_000, 8f); e.speed(13_000, 0f)
            assertNull(e.currentResult(14_000))
        }

        @Test fun `비상등은 지원될 때만 정차 트리거가 된다`() {
            val on = engine()
            on.speed(0, 0f)
            on.sig(10_000, Signal.TurnSignalState(TurnSignal.HAZARD))
            assertEquals(TriggerId.T_STOP_HAZARD, on.currentResult(10_000)!!.candidate.trigger)

            val caps = CapabilityMap(CapabilityKey.entries.associateWith { if (it == CapabilityKey.HAZARD_STATE) null else true })
            val off = engine(caps)
            off.speed(0, 0f)
            off.sig(10_000, Signal.TurnSignalState(TurnSignal.HAZARD))
            assertNull(off.currentResult(10_000))
        }

        @Test fun `정차 30초 뒤의 비상등은 트리거가 아니다`() {
            val e = engine()
            e.speed(0, 0f)
            e.sig(31_000, Signal.TurnSignalState(TurnSignal.HAZARD))
            assertNull(e.currentResult(31_000))
        }

        @Test fun `신뢰도 낮은 고속도로 정차는 트리거가 아니다`() {
            val e = engine()
            e.speed(0, 0f)
            e.sig(100, road(RoadType.EXPRESSWAY_MAIN, 0.5f))
            e.sig(200, Signal.DoorPosition(DoorArea.ROW1_LEFT, true))
            assertNull(e.currentResult(300))
        }

        @Test fun `주행 중 새 경고는 고장 흐름으로 시작하고 사고 질문을 하지 않는다`() {
            val e = engine()
            e.speed(0, 60f)
            e.sig(10_000, Signal.PowerDownWarning(true))
            val r = e.currentResult(10_000)!!
            assertEquals(IncidentKind.FAULT, r.candidate.kind)
            assertTrue(r.pendingQuestion != TriageQ.IS_ACCIDENT)
            assertEquals(Severity.S0, r.damage.severity)
        }

        @Test fun `시동 직후 이미 켜진 경고는 트리거가 아니다`() {
            val e = engine()
            e.speed(0, 0f)
            e.sig(1000, Signal.FluidLevel(FluidKind.ENGINE_OIL, LevelState.LOW))
            assertNull(e.currentResult(1000))
        }

        @Test fun `읽기 오류 값은 경고가 아니다`() {
            val e = engine()
            e.speed(0, 0f)
            e.sig(10_000, Signal.FluidLevel(FluidKind.ENGINE_OIL, LevelState.ERROR))
            e.sig(10_001, Signal.Telltale("TURN_SIGNAL_LEFT", "BLINK"))
            assertNull(e.currentResult(10_001))
        }

        @Test fun `사고 아님 뒤 같은 정차로 다시 묻지 않고 다시 달리면 풀린다`() {
            val e = engine()
            e.controlledStop(10_000)
            e.answer(15_000, TriageQ.IS_ACCIDENT, Answer.NO)
            assertEquals(SessionState.DISMISSED, e.currentResult(15_000)!!.session.state)
            e.speed(16_000, 0f)
            assertEquals(SessionState.DISMISSED, e.currentResult(16_000)!!.session.state)

            e.speed(30_000, 40f)
            e.controlledStop(40_000)
            assertEquals(SessionState.SAFETY_CHECK, e.currentResult(44_000)!!.session.state)
        }

        @Test fun `지원되지 않는 신호로 온 에어백은 무시한다`() {
            val caps = CapabilityMap(mapOf(CapabilityKey.SEAT_AIRBAG_DEPLOYED to false))
            val e = engine(caps)
            e.sig(0, Signal.SeatAirbagDeployed(SeatArea.ROW1_LEFT, true))
            assertNull(e.currentResult(1))
        }
    }

    @Nested
    inner class NoResponse {
        @Test fun `30초 전에는 R0가 아니고 30초가 되면 R0다`() {
            val e = engine()
            e.controlledStop(10_000)
            val deadline = e.currentResult(14_000)!!.questionDeadlineMs!!
            assertEquals(13_600 + 30_000, deadline)
            assertFalse(RiskGrade.R0 in e.currentResult(deadline - 1)!!.risk.has)
            val r = e.currentResult(deadline)!!
            assertTrue(RiskGrade.R0 in r.risk.has)
            assertEquals(ReasonCode.CONFIRM_NO_RESPONSE, r.risk.grades.first().because.first())
            assertNull(r.pendingQuestion, "무응답 뒤에는 더 묻지 않는다")
        }

        @Test fun `답이 없어도 계속 달리면 R0로 올리지 않고 닫는다`() {
            val e = engine()
            e.controlledStop(10_000)
            e.speed(14_000, 50f)
            val r = e.currentResult(50_000)!!
            assertEquals(SessionState.DISMISSED, r.session.state)
            assertTrue(r.risk.grades.isEmpty())
        }

        @Test fun `무응답 뒤 늦은 아니오는 사건을 닫고 기록을 버린다`() {
            val e = engine()
            e.controlledStop(10_000)
            assertTrue(RiskGrade.R0 in e.currentResult(44_000)!!.risk.has)
            e.answer(50_000, TriageQ.IS_ACCIDENT, Answer.NO)
            val r = e.currentResult(50_000)!!
            assertEquals(SessionState.DISMISSED, r.session.state)
            assertNull(r.session.caseCard)
            assertEquals(0, e.retainedEventCount())
        }

        @Test fun `에어백이 터졌으면 아니오라고 해도 닫지 않는다`() {
            val e = engine()
            e.sig(0, Signal.SeatAirbagDeployed(SeatArea.ROW1_LEFT, true))
            e.answer(1000, TriageQ.IS_ACCIDENT, Answer.NO)
            val r = e.currentResult(1000)!!
            assertTrue(RiskGrade.R0 in r.risk.has)
            assertEquals(SessionState.SAFETY_CHECK, r.session.state)
        }

        @Test fun `보호자 동의가 없으면 무응답이어도 자동 알림이 아니다`() {
            val e = engine(driver = driver.copy(guardianPhone = "010", guardianAutoNotifyConsent = false))
            e.controlledStop(10_000)
            val a = e.currentResult(44_000)!!.plan.find(ActionId.NOTIFY_GUARDIAN)!!
            assertFalse(a.autoExecutable)
            assertTrue(a.needsConfirm)
        }

        @Test fun `화면을 조작하는 사람은 무응답으로 처리하지 않는다`() {
            val e = engine(driver = driver.copy(guardianPhone = "010", guardianAutoNotifyConsent = true))
            e.sig(0, Signal.SeatAirbagDeployed(SeatArea.ROW2_LEFT, true))
            e.onUserAction(UserActionEvent.SupportSelected(ActionId.CALL_119, 5_000))
            e.onUserAction(UserActionEvent.ContactAttempted(ActionId.CALL_119, 6_000))
            // 119와 통화하느라 오래 답하지 못해도 "응답 없음"이 아니다.
            val r = e.currentResult(120_000)!!
            assertEquals(TriageQ.IS_ACCIDENT, r.pendingQuestion)
            assertNull(r.questionDeadlineMs)
            assertFalse(r.risk.grades.flatMap { it.because }.contains(ReasonCode.CONFIRM_NO_RESPONSE))
            assertFalse("응답하지 않습니다" in r.plan.find(ActionId.CALL_119)!!.script!!.text)
            assertFalse(r.plan.find(ActionId.NOTIFY_GUARDIAN)!!.autoExecutable)
            assertEquals(ActionId.CALL_119, r.plan.first()?.id, "R0는 그대로다")
        }

        @Test fun `보호자 알림 완료 이벤트는 사용자 조작이 아니다`() {
            val e = engine()
            e.controlledStop(10_000)
            e.onUserAction(UserActionEvent.GuardianNotified(14_000))
            assertTrue(RiskGrade.R0 in e.currentResult(44_000)!!.risk.has)
        }

        @Test fun `무응답이 아닌 질문의 시간 초과는 다음 질문으로 넘어간다`() {
            val e = engine()
            e.sig(0, road(RoadType.URBAN))
            e.onUserAction(UserActionEvent.ManualReport(1000))
            assertEquals(TriageQ.INJURY, e.currentResult(1000)!!.pendingQuestion)
            val r = e.currentResult(31_000)!!
            assertEquals(TriageQ.FIRE_SMOKE, r.pendingQuestion)
            assertFalse(RiskGrade.R0 in r.risk.has)
        }
    }

    @Nested
    inner class Damage {
        private fun accident(e: AlertBridgeEngine, t: Long = 100) = e.onUserAction(UserActionEvent.ManualReport(t))

        @Test fun `정면 에어백은 FRONT HIGH`() {
            val e = engine()
            e.sig(0, Signal.SeatAirbagDeployed(SeatArea.ROW1_LEFT, true, AirbagKind.FRONT))
            assertEquals(ZoneEstimate(Zone.FRONT, Confidence.HIGH, listOf(ReasonCode.AIRBAG_FRONT)), e.currentResult(0)!!.damage.impactZones.single())
        }

        @Test fun `측면 에어백은 좌석 쪽 HIGH`() {
            val e = engine()
            e.sig(0, Signal.SeatAirbagDeployed(SeatArea.ROW2_RIGHT, true, AirbagKind.CURTAIN))
            val z = e.currentResult(0)!!.damage.impactZones.single()
            assertEquals(Zone.RIGHT, z.zone)
            assertEquals(Confidence.HIGH, z.confidence)
        }

        @Test fun `실제 SDK처럼 종류를 모르면 앞좌석 전개는 FRONT MEDIUM`() {
            val e = engine()
            e.sig(0, Signal.SeatAirbagDeployed(SeatArea.ROW1_RIGHT, true, null))
            val z = e.currentResult(0)!!.damage.impactZones.single()
            assertEquals(Zone.FRONT, z.zone)
            assertEquals(Confidence.MEDIUM, z.confidence)
        }

        @Test fun `뒷좌석 에어백 종류를 모르면 위치를 추정하지 않는다`() {
            val e = engine()
            e.sig(0, Signal.SeatAirbagDeployed(SeatArea.ROW2_LEFT, true, null))
            val d = e.currentResult(0)!!.damage
            assertTrue(d.impactZones.isEmpty())
            assertEquals(Severity.S3, d.severity)
        }

        @Test fun `충돌 뒤 타이어 저압은 해당 모서리 MEDIUM이고 S2 주행 불가`() {
            val e = engine()
            e.speed(0, 0f)
            accident(e, 10_000)
            e.sig(10_500, Signal.LowTirePressure(WheelArea.RIGHT_REAR, true))
            val d = e.currentResult(11_000)!!.damage
            assertEquals(Zone.REAR_RIGHT, d.impactZones.single().zone)
            assertEquals(Confidence.MEDIUM, d.impactZones.single().confidence)
            assertEquals(Severity.S2, d.severity)
            assertEquals(Tri.NO, d.drivable)
        }

        @Test fun `왼쪽 깜빡이와 왼쪽 조향이면 LEFT LOW`() {
            val e = engine()
            e.speed(0, 40f)
            e.sig(6000, Signal.TurnSignalState(TurnSignal.LEFT))
            e.sig(7000, Signal.SteeringAngle(-10f))
            e.controlledStop(8000)
            e.answer(13_000, TriageQ.IS_ACCIDENT, Answer.YES)
            val z = e.currentResult(13_000)!!.damage.impactZones.single()
            assertEquals(Zone.LEFT, z.zone)
            assertEquals(Confidence.LOW, z.confidence)
        }

        @Test fun `깜빡이만 있고 조향이 없으면 추정하지 않는다`() {
            val e = engine()
            e.speed(0, 40f)
            e.sig(6000, Signal.TurnSignalState(TurnSignal.RIGHT))
            e.controlledStop(8000)
            e.answer(13_000, TriageQ.IS_ACCIDENT, Answer.YES)
            val d = e.currentResult(13_000)!!.damage
            assertTrue(d.impactZones.isEmpty())
            assertTrue(d.needsPhotoCheck)
        }

        @Test fun `5초보다 전에 꺼진 깜빡이는 근거가 아니다`() {
            val e = engine()
            e.speed(0, 40f)
            e.sig(1000, Signal.TurnSignalState(TurnSignal.RIGHT))
            e.sig(1500, Signal.SteeringAngle(10f))
            e.sig(2000, Signal.TurnSignalState(TurnSignal.NONE))
            e.sig(2100, Signal.SteeringAngle(0f))
            e.controlledStop(10_000)
            e.answer(15_000, TriageQ.IS_ACCIDENT, Answer.YES)
            assertTrue(e.currentResult(15_000)!!.damage.impactZones.isEmpty())
        }

        @Test fun `급격한 속도 차는 S2다`() {
            val e = engine()
            e.sig(0, Signal.BrakeState(true))
            e.speed(0, 60f); e.speed(10_000, 60f); e.speed(10_300, 0f)
            e.answer(11_000, TriageQ.IS_ACCIDENT, Answer.YES)
            val d = e.currentResult(13_000)!!.damage
            assertTrue(d.deltaVKmh!! >= 25f, "deltaV=${d.deltaVKmh}")
            assertEquals(Severity.S2, d.severity)
        }

        @Test fun `차속을 모르면 deltaV는 null이다`() {
            val caps = CapabilityMap(CapabilityKey.entries.associateWith { it != CapabilityKey.VEHICLE_SPEED })
            val e = engine(caps)
            accident(e)
            assertNull(e.currentResult(5000)!!.damage.deltaVKmh)
        }

        @Test fun `경미 사고는 주행 가능 응답을 따른다`() {
            val e = engine()
            accident(e)
            assertEquals(Tri.UNKNOWN, e.currentResult(100)!!.damage.drivable)
            e.answer(200, TriageQ.DRIVABLE, Answer.YES)
            assertEquals(Tri.YES, e.currentResult(200)!!.damage.drivable)
            e.answer(300, TriageQ.DRIVABLE, Answer.NO)
            assertEquals(Tri.NO, e.currentResult(300)!!.damage.drivable)
        }

        @Test fun `앞좌석 값을 읽어야 에어백 NO라고 말한다`() {
            val e = engine()
            e.sig(0, Signal.SeatAirbagDeployed(SeatArea.ROW1_LEFT, false))
            accident(e)
            assertEquals(Tri.UNKNOWN, e.currentResult(100)!!.damage.airbagDeployed)
            e.sig(200, Signal.SeatAirbagDeployed(SeatArea.ROW1_RIGHT, false))
            assertEquals(Tri.NO, e.currentResult(200)!!.damage.airbagDeployed)
        }
    }

    @Nested
    inner class RiskAndPlan {
        @Test fun `벨트 미착용은 S2 이상에서만 R0다`() {
            val e = engine()
            e.sig(0, Signal.SeatOccupancy(SeatArea.ROW2_LEFT, true))
            e.sig(0, Signal.SeatBeltBuckled(SeatArea.ROW2_LEFT, false))
            e.onUserAction(UserActionEvent.ManualReport(10_000))
            assertFalse(RiskGrade.R0 in e.currentResult(10_000)!!.risk.has)
            e.sig(10_500, Signal.LowTirePressure(WheelArea.LEFT_FRONT, true))
            assertTrue(RiskGrade.R0 in e.currentResult(10_500)!!.risk.has)
        }

        @Test fun `좌석 점유를 모르면 벨트 미착용만으로 R0가 아니다`() {
            val e = engine()
            e.sig(0, Signal.SeatBeltBuckled(SeatArea.ROW2_LEFT, false))
            e.onUserAction(UserActionEvent.ManualReport(10_000))
            e.sig(10_500, Signal.LowTirePressure(WheelArea.LEFT_FRONT, true))
            assertFalse(RiskGrade.R0 in e.currentResult(10_500)!!.risk.has)
        }

        @Test fun `R0와 R1이 겹치면 119 다음에 대피가 들어가고 중복이 없다`() {
            val e = engine(driver = driver.copy(guardianPhone = "010"))
            e.speed(0, 0f)
            e.sig(100, road(RoadType.EXPRESSWAY_MAIN))
            e.onUserAction(UserActionEvent.ManualReport(10_000))
            e.answer(10_100, TriageQ.INJURY, Answer.YES)
            val r = e.currentResult(10_100)!!
            assertEquals(listOf(RiskGrade.R0, RiskGrade.R1), r.risk.grades.map { it.grade })
            val ids = r.plan.actions.map { it.id }
            assertEquals(ActionId.CALL_119, ids[0])
            assertEquals(ActionId.BLUELINK_SOS_GUIDE, ids[1])
            assertEquals(ActionId.EVACUATE_GUIDE, ids[2])
            assertEquals(ActionId.NOTIFY_GUARDIAN, ids[3])
            assertEquals(ids.size, ids.distinct().size)
        }

        @Test fun `화재면 119 이유에 먼저 떨어지라는 안내가 들어간다`() {
            val e = engine()
            e.onUserAction(UserActionEvent.ManualReport(100))
            e.answer(200, TriageQ.FIRE_SMOKE, Answer.YES)
            val a = e.currentResult(200)!!.plan.first()!!
            assertEquals("reason.call119.fire", a.reason.key)
            assertTrue("불이나 연기" in a.script!!.text)
        }

        @Test fun `R2 의심 응답이면 112가 1순위이고 R3는 빠진다`() {
            val e = engine()
            e.sig(0, road(RoadType.URBAN))
            e.onUserAction(UserActionEvent.ManualReport(100))
            e.answer(200, TriageQ.OTHER_PARTY_SUSPICIOUS, Answer.YES)
            val r = e.currentResult(200)!!
            assertEquals(listOf(RiskGrade.R2), r.risk.grades.map { it.grade })
            assertEquals(ActionId.CALL_112, r.plan.first()?.id)
            assertTrue(r.plan.find(ActionId.TOW_GUARD) != null)
        }

        @Test fun `보행자 수동 선택은 R2다`() {
            val e = engine()
            e.onUserAction(UserActionEvent.ManualReport(100))
            e.onUserAction(UserActionEvent.IncidentDetail(pedestrianOrBicycle = true, tMs = 200))
            assertTrue(RiskGrade.R2 in e.currentResult(200)!!.risk.has)
        }

        @Test fun `야간 도로 위 정차는 R1, 주차장은 아니다`() {
            fun grades(type: RoadType): Set<RiskGrade> {
                val e = engine()
                e.speed(0, 0f)
                e.sig(0, Signal.LowBeamLight(true))
                e.sig(0, road(type))
                e.onUserAction(UserActionEvent.ManualReport(100))
                return e.currentResult(100)!!.risk.has
            }
            assertTrue(RiskGrade.R1 in grades(RoadType.URBAN))
            assertFalse(RiskGrade.R1 in grades(RoadType.PARKING))
        }

        @Test fun `도로 신뢰도가 낮으면 고속도로 질문을 하고 답하기 전에는 R1이 아니다`() {
            val e = engine()
            e.speed(0, 0f)
            e.sig(0, road(RoadType.EXPRESSWAY_MAIN, 0.5f))
            e.onUserAction(UserActionEvent.ManualReport(100))
            val r = e.currentResult(100)!!
            assertEquals(TriageQ.IS_EXPRESSWAY, r.pendingQuestion)
            assertFalse(RiskGrade.R1 in r.risk.has)
        }

        @Test fun `2504는 터널에서 제안하지 않고 큰 차도 제안하지 않는다`() {
            fun has2504(type: RoadType, vehicle: VehicleProfile?): Boolean {
                val e = engine(vehicle = vehicle)
                e.speed(0, 0f)
                e.sig(0, road(type))
                e.onUserAction(UserActionEvent.ManualReport(100))
                return e.currentResult(100)!!.plan.find(ActionId.CALL_2504) != null
            }
            val small = VehicleProfile(sizeClass = VehicleSizeClass.SMALL)
            assertTrue(has2504(RoadType.EXPRESSWAY_MAIN, small))
            assertFalse(has2504(RoadType.TUNNEL, small))
            assertFalse(has2504(RoadType.EXPRESSWAY_MAIN, VehicleProfile(sizeClass = VehicleSizeClass.LARGE)))
            assertTrue(has2504(RoadType.EXPRESSWAY_MAIN, null), "크기 미등록은 정책값에 따라 안내한다")
        }

        @Test fun `2504 문구는 무료나 배차를 보장하지 않는다`() {
            val e = engine()
            e.speed(0, 0f)
            e.sig(0, road(RoadType.EXPRESSWAY_MAIN))
            e.onUserAction(UserActionEvent.ManualReport(100))
            val text = e.currentResult(100)!!.plan.find(ActionId.CALL_2504)!!.reason.text
            assertTrue("가능성" in text)
            assertFalse("무료" in text || "배차" in text)
        }

        @Test fun `보험이 없어도 보험사 조치는 번호 없이 안내하고 죽지 않는다`() {
            val e = engine()
            e.onUserAction(UserActionEvent.ManualReport(100))
            val r = e.currentResult(100)!!
            assertNull(r.coverage)
            assertNull(r.plan.find(ActionId.CALL_INSURER_CLAIM)!!.contact!!.number)
        }

        @Test fun `R3 이유에 도로교통법 54조 근거가 있다`() {
            val e = engine()
            e.onUserAction(UserActionEvent.ManualReport(100))
            assertTrue("제54조" in e.currentResult(100)!!.plan.find(ActionId.MOVE_TO_SAFETY)!!.reason.text)
        }

        @Test fun `고장 뒤 사고가 나면 고장 경고는 사고 근거가 아니다`() {
            val e = engine()
            e.speed(0, 40f)
            e.sig(20_000, Signal.FluidLevel(FluidKind.ENGINE_OIL, LevelState.LOW))
            assertEquals(IncidentKind.FAULT, e.currentResult(20_000)!!.candidate.kind)
            e.controlledStop(60_000)
            e.answer(65_000, TriageQ.IS_ACCIDENT, Answer.YES)
            val r = e.currentResult(65_000)!!
            assertEquals(IncidentKind.ACCIDENT, r.candidate.kind)
            assertEquals(Severity.S1, r.damage.severity)
            assertEquals(listOf(RiskGrade.R3), r.risk.grades.map { it.grade })
        }

        @Test fun `사고 질문에 아니오여도 새 경고가 있으면 고장 흐름으로 간다`() {
            val e = engine()
            e.speed(0, 40f)
            e.controlledStop(10_000)
            e.sig(14_000, Signal.PowerDownWarning(true))
            e.answer(15_000, TriageQ.IS_ACCIDENT, Answer.NO)
            val r = e.currentResult(15_000)!!
            assertEquals(IncidentKind.FAULT, r.candidate.kind)
            assertEquals(SessionState.SAFETY_CHECK, r.session.state)
            assertEquals(listOf(RiskGrade.R4), r.risk.grades.map { it.grade })
            assertNull(r.session.caseCard)
        }
    }

    @Nested
    inner class Scripts {
        private fun script(experience: Experience, block: AlertBridgeEngine.() -> Unit = {}): Message {
            val e = engine(driver = driver.copy(experience = experience))
            e.sig(0, road(RoadType.URBAN, name = "테헤란로"))
            e.sig(0, Signal.SeatOccupancy(SeatArea.ROW1_LEFT, true))
            e.sig(0, Signal.SeatOccupancy(SeatArea.ROW2_RIGHT, true))
            e.sig(0, Signal.SeatBeltBuckled(SeatArea.ROW2_RIGHT, false))
            e.sig(0, Signal.SeatAirbagDeployed(SeatArea.ROW1_LEFT, true, AirbagKind.FRONT))
            e.block()
            return e.currentResult(100)!!.plan.find(ActionId.CALL_119)!!.script!!
        }

        @Test fun `일반 모드는 탑승자 카드가 한 문장이다`() {
            val s = script(Experience.NORMAL)
            assertTrue(s.sentences.any { "탑승자 2명" in it && "안전벨트" in it && "에어백" in it }, s.sentences.toString())
        }

        @Test fun `고령 모드는 항목마다 문장을 끊는다`() {
            val s = script(Experience.SENIOR)
            assertFalse(s.sentences.any { "탑승자 2명" in it && "안전벨트" in it }, s.sentences.toString())
            assertTrue(s.sentences.any { "안전벨트" in it })
        }

        @Test fun `신고 문장에는 확실한 충격 위치만 넣고 추정은 화면에만 남긴다`() {
            fun run(kind: AirbagKind?): Pair<String, List<Zone>> {
                val e = engine(driver = driver)
                e.sig(0, Signal.SeatAirbagDeployed(SeatArea.ROW1_LEFT, true, kind))
                val r = e.currentResult(100)!!
                val scripts = r.plan.actions.mapNotNull { it.script?.text }.joinToString(" | ")
                return scripts to r.damage.impactZones.map { it.zone }
            }
            val (sure, sureZones) = run(AirbagKind.SIDE)
            assertTrue("충격 위치는 왼쪽입니다" in sure, sure)
            assertEquals(listOf(Zone.LEFT), sureZones)

            // 실제 SDK처럼 종류를 모르면 FRONT/MEDIUM 추정은 하되 기관에는 말하지 않는다.
            val (guess, guessZones) = run(null)
            assertFalse("충격 위치" in guess, guess)
            assertEquals(listOf(Zone.FRONT), guessZones)
        }

        @Test fun `의료 정보는 동의한 경우에만 들어간다`() {
            fun text(consent: Boolean): String {
                val e = engine(driver = driver.copy(medicalNote = "당뇨", medicalShareConsent = consent))
                e.onUserAction(UserActionEvent.ManualReport(100))
                e.answer(200, TriageQ.INJURY, Answer.YES)
                return e.currentResult(200)!!.plan.find(ActionId.CALL_119)!!.script!!.text
            }
            assertTrue("당뇨" in text(true))
            assertFalse("당뇨" in text(false))
        }

        @Test fun `GPS 좌표만 있으면 오차가 작을 때만 좌표를 불러 준다`() {
            fun text(accuracy: Float?): String {
                val e = engine()
                e.sig(0, Signal.Location(37.51234, 127.04321, accuracy))
                e.onUserAction(UserActionEvent.ManualReport(100))
                e.answer(200, TriageQ.INJURY, Answer.YES)
                return e.currentResult(200)!!.plan.find(ActionId.CALL_119)!!.script!!.text
            }
            assertTrue("위도 37.51234, 경도 127.04321" in text(20f))
            assertTrue("위치를 확인할 수 없어요" in text(500f))
            assertTrue("위치를 확인할 수 없어요" in text(null))
        }

        @Test fun `GPS만 있으면 도로 종류를 몰라 고속도로인지 묻고 2504를 내지 않는다`() {
            val e = engine()
            e.sig(0, Signal.Location(37.5, 127.0, 10f))
            e.onUserAction(UserActionEvent.ManualReport(100))
            val r = e.currentResult(100)!!
            assertEquals(TriageQ.IS_EXPRESSWAY, r.pendingQuestion)
            assertNull(r.plan.find(ActionId.CALL_2504))
        }

        @Test fun `도로명이 없으면 주소를 쓴다`() {
            val e = engine()
            e.sig(0, Signal.Location(37.0, 127.0, null, RoadType.URBAN, 0.9f, null, null, null, "서울시 강남구"))
            e.onUserAction(UserActionEvent.ManualReport(100))
            e.answer(200, TriageQ.INJURY, Answer.YES)
            assertTrue("서울시 강남구" in e.currentResult(200)!!.plan.find(ActionId.CALL_119)!!.script!!.text)
        }
    }

    @Nested
    inner class CoverageRules {
        private fun result(policy: InsurancePolicy, vehicle: VehicleProfile? = null, block: AlertBridgeEngine.() -> Unit): CoverageMatch {
            val e = engine(driver = driver.copy(insurance = policy), vehicle = vehicle)
            e.block()
            return e.currentResult(100_000)!!.coverage!!
        }

        @Test fun `배터리 본인부담 추정식`() {
            val b = EvBatteryInfo(20_000_000, 0, 15)
            val twoYears = (2 * 365.25 * 24 * 60 * 60 * 1000).toLong()
            assertEquals(2_666_666, CoverageMatcher.estimateMidKrw(b, twoYears))
            assertEquals("267만 원", CoverageMatcher.formatKrw(CoverageMatcher.estimateMidKrw(b, twoYears)))
            assertEquals(20_000_000, CoverageMatcher.estimateMidKrw(b, twoYears * 100), "내구연한을 넘으면 전액")
            assertEquals(0, CoverageMatcher.estimateMidKrw(b, -1000), "출고 전 시각은 0")
        }

        @Test fun `든 담보만 청구 후보로 보이고 없는 담보는 확인 필요로만 보인다`() {
            val m = result(insurance) { onUserAction(UserActionEvent.ManualReport(100)); onUserAction(UserActionEvent.IncidentDetail(2, tMs = 200)) }
            assertEquals(listOf(Coverage.DAEMUL), m.claim.map { it.coverage })
            assertEquals(listOf(Coverage.JACHA), m.gaps.map { it.coverage })
            assertTrue("확인이 필요해요" in m.gaps.single().message.text)
        }

        @Test fun `면책 문구가 항상 있다`() {
            val m = result(insurance) { onUserAction(UserActionEvent.ManualReport(100)) }
            assertEquals("최종 보상 여부는 약관과 보험사 판단에 따릅니다.", m.disclaimer.text)
        }

        @Test fun `견인 거리가 무료 범위를 넘으면 안내한다`() {
            val p = insurance.copy(coverages = setOf(Coverage.EMERGENCY_TOW), freeTowKm = 40)
            val m = result(p) {
                onUserAction(UserActionEvent.ManualReport(100))
                answer(200, TriageQ.DRIVABLE, Answer.NO)
                onUserAction(UserActionEvent.TowDestinationSet(55f, 300))
            }
            assertTrue("55km" in m.towNote!!.text && "40km" in m.towNote.text)
            assertTrue(Coverage.EMERGENCY_TOW in m.claim.map { it.coverage })
        }

        @Test fun `전기차 방전이면 전용 출동을 안내한다`() {
            val m = result(insurance, VehicleProfile(Powertrain.EV)) {
                sig(0, Signal.EvStateOfCharge(3f))
                onUserAction(UserActionEvent.ManualReport(100))
            }
            assertTrue(Coverage.EV_TOW_SOS in m.gaps.map { it.coverage })
        }

        @Test fun `집에서 멀면 귀가 담보를 후보로 낸다`() {
            val p = insurance.copy(coverages = setOf(Coverage.REMOTE_RETURN))
            val e = engine(driver = driver.copy(insurance = p, homeLat = 35.1, homeLng = 129.0))
            e.sig(0, road(RoadType.URBAN))
            e.onUserAction(UserActionEvent.ManualReport(100))
            assertEquals(listOf(Coverage.REMOTE_RETURN), e.currentResult(100)!!.coverage!!.claim.map { it.coverage })
        }

        @Test fun `가입 권유 문구가 없다`() {
            val banned = listOf("가입하세요", "가입해", "추천", "권유", "특약을 드", "미가입")
            Fixtures.messages.keys.forEach { key ->
                val text = Fixtures.messages.text(key)
                banned.forEach { assertFalse(it in text, "$key: $it") }
            }
        }
    }

    @Nested
    inner class Session {
        @Test fun `허용된 전이만 된다`() {
            val m = SessionMachine(0)
            assertFalse(m.transition(SessionState.CONFIRMED, 1), "CREATED에서 바로 확정 금지")
            assertTrue(m.transition(SessionState.SAFETY_CHECK, 1))
            assertFalse(m.transition(SessionState.CONFIRMED, 2), "시도 없이 확정 금지")
            assertTrue(m.transition(SessionState.SUPPORT_SELECTED, 3))
            assertTrue(m.transition(SessionState.CONTACT_ATTEMPTED, 4))
            assertFalse(m.transition(SessionState.DISMISSED, 5), "시도 뒤 취소 금지")
            assertTrue(m.transition(SessionState.CONFIRMED, 6))
            assertFalse(m.transition(SessionState.CONTACT_ATTEMPTED, 7), "뒤로 가지 않음")
            assertTrue(m.transition(SessionState.RECOVERY, 8))
            assertTrue(m.transition(SessionState.CLOSED, 9))
            assertFalse(m.transition(SessionState.RECOVERY, 10), "종료 뒤 전이 금지")
            assertTrue(m.isTerminal)
        }

        @Test fun `접수 시도 뒤에는 취소할 수 없다`() {
            val e = engine()
            e.onUserAction(UserActionEvent.ManualReport(100))
            e.onUserAction(UserActionEvent.ContactAttempted(ActionId.CALL_INSURER_CLAIM, 200))
            e.onUserAction(UserActionEvent.CancelSession(300))
            assertEquals(SessionState.CONTACT_ATTEMPTED, e.currentResult(300)!!.session.state)
        }

        @Test fun `안내 조치를 완료해도 세션은 확정되지 않는다`() {
            val e = engine()
            e.onUserAction(UserActionEvent.ManualReport(100))
            e.onUserAction(UserActionEvent.MarkedDone(ActionId.TAKE_PHOTOS, 200))
            val r = e.currentResult(200)!!
            assertEquals(ActionStatus.CONFIRMED, r.plan.find(ActionId.TAKE_PHOTOS)?.status)
            assertEquals(SessionState.SAFETY_CHECK, r.session.state)
        }

        @Test fun `완료한 조치는 되돌아가지 않는다`() {
            val e = engine(driver = driver)
            e.onUserAction(UserActionEvent.ManualReport(100))
            e.onUserAction(UserActionEvent.MarkedDone(ActionId.CALL_INSURER_CLAIM, 200))
            e.onUserAction(UserActionEvent.ContactAttempted(ActionId.CALL_INSURER_CLAIM, 300))
            assertEquals(ActionStatus.CONFIRMED, e.currentResult(300)!!.plan.find(ActionId.CALL_INSURER_CLAIM)?.status)
        }

        @Test fun `사건 카드는 답이 늦어도 충돌 전 신호를 담는다`() {
            val e = engine()
            e.speed(0, 40f)
            e.sig(50_000, Signal.TurnSignalState(TurnSignal.RIGHT))
            e.sig(55_000, Signal.TurnSignalState(TurnSignal.NONE))
            e.controlledStop(60_000)
            // 응답 전 긴 시간이 지나 링 버퍼에서 밀려나도 사건에 묶인 기록은 남는다.
            (65..120).forEach { e.speed(it * 1000L, 0f) }
            e.answer(92_000, TriageQ.IS_ACCIDENT, Answer.YES)
            val card = e.currentResult(120_000)!!.session.caseCard
            // 무응답(93.6초)보다 먼저 답했으므로 R0는 아니다.
            assertNotNull(card)
            assertTrue(card.timeline.any { it.channel == "TURN_SIGNAL" && it.value == "RIGHT" }, "${card.timeline.take(5)}")
        }

        @Test fun `사건이 없으면 결과가 없다`() {
            assertNull(engine().currentResult(0))
        }

        @Test fun `확정 전에는 사건 카드가 없다`() {
            val e = engine()
            e.controlledStop(10_000)
            assertNull(e.currentResult(14_000)!!.session.caseCard)
            e.answer(15_000, TriageQ.IS_ACCIDENT, Answer.YES)
            assertNotNull(e.currentResult(15_000)!!.session.caseCard)
        }
    }

    @Nested
    inner class Policy {
        private val source = PolicyLoader::class.java.classLoader.getResourceAsStream("policy/policy_v1.json")!!.use { it.readBytes().toString(Charsets.UTF_8) }

        @Test fun `출력에 정책 버전이 있다`() {
            val e = engine()
            e.onUserAction(UserActionEvent.ManualReport(100))
            val r = e.currentResult(100)!!
            assertEquals(Fixtures.policy.version, r.policyVersion)
            assertEquals(Fixtures.policy.version, r.session.caseCard!!.policyVersion)
        }

        @Test fun `R0 첫 조치가 119가 아닌 정책은 거부한다`() {
            val p = Fixtures.policy
            val bad = p.copy(actionOrder = p.actionOrder + (RiskGrade.R0 to p.actionOrder.getValue(RiskGrade.R0).reversed()))
            assertFailsWith<PolicyError> { PolicyLoader.validated(bad, Fixtures.messages) }
        }

        @Test fun `2504 조건을 뺀 정책은 거부한다`() {
            val p = Fixtures.policy
            val def = p.actionDefs.getValue(ActionId.CALL_2504).copy(requires = emptyList())
            assertFailsWith<PolicyError> { PolicyLoader.validated(p.copy(actionDefs = p.actionDefs + (ActionId.CALL_2504 to def)), Fixtures.messages) }
        }

        @Test fun `연결 조치를 전화 외 채널로 바꾼 정책은 거부한다`() {
            val p = Fixtures.policy
            val def = p.actionDefs.getValue(ActionId.CALL_119).copy(channel = ContactChannel.SMS_GUIDE)
            assertFailsWith<PolicyError> { PolicyLoader.validated(p.copy(actionDefs = p.actionDefs + (ActionId.CALL_119 to def)), Fixtures.messages) }
        }

        @Test fun `없는 사실 이름은 불러올 때 실패한다`() {
            assertFailsWith<Exception> { PolicyLoader.parse(source.replace("\"INJURY_YES\"", "\"NO_SUCH_FACT\"")) }
        }

        @Test fun `임계값은 정책을 따른다`() {
            val p = Fixtures.policy
            val strict = p.copy(thresholds = p.thresholds.copy(hardStopDropKmh = 100f))
            val e = AlertBridgeEngine(strict, Fixtures.messages).also { it.configure(CapabilityMap.ALL_SUPPORTED) }
            e.controlledStop(10_000)
            assertNull(e.currentResult(14_000))
        }

        @Test fun `모든 근거 코드에 설명 문구가 있다`() {
            ReasonCode.entries.forEach { assertTrue("reasoncode.$it" in Fixtures.messages, "$it") }
        }

        @Test fun `정책 문서를 만들 수 있다`() {
            val doc = renderPolicyDoc(Fixtures.policy)
            assertTrue("CALL_119" in doc && "noResponseTimeoutMs" in doc)
        }
    }
}
