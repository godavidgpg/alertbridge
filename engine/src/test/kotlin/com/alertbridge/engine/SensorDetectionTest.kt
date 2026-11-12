package com.alertbridge.engine

import com.alertbridge.engine.model.AdasKind
import com.alertbridge.engine.model.CapabilityKey
import com.alertbridge.engine.model.CapabilityMap
import com.alertbridge.engine.model.Confidence
import com.alertbridge.engine.model.DoorArea
import com.alertbridge.engine.model.RoadType
import com.alertbridge.engine.model.Severity
import com.alertbridge.engine.model.Signal
import com.alertbridge.engine.model.TriggerConfidence
import com.alertbridge.engine.model.TriggerId
import com.alertbridge.engine.model.VehicleEvent
import com.alertbridge.engine.model.Zone
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 급정거 없이 일어나는 접촉 사고 감지: 충격 센서, 주행 보조 경고 직후 정차, 감속 정차 후 문 열림 */
class SensorDetectionTest {
    private fun engine(vararg caps: CapabilityKey): AlertBridgeEngine =
        AlertBridgeEngine(Fixtures.policy, Fixtures.messages).also {
            it.configure(CapabilityMap((caps.toList() + CapabilityKey.VEHICLE_SPEED + CapabilityKey.DOOR_POSITION + CapabilityKey.LOCATION).associateWith { true }))
        }

    private fun AlertBridgeEngine.at(t: Long, s: Signal) = onEvent(VehicleEvent(t, s))

    @Nested inner class Impact {
        @Test fun `정차 중 2_5G 이상 충격은 HIGH, 뒤에서 받히면 부딪힌 곳은 뒤`() {
            val e = engine(CapabilityKey.IMPACT_SENSOR)
            e.at(0, Signal.VehicleSpeed(0f))
            e.at(1000, Signal.ImpactAccel(2.8f, longitudinalG = 2.7f, lateralG = 0.2f))
            val r = e.currentResult(1500)!!
            assertEquals(TriggerId.T_IMPACT, r.candidate.trigger)
            assertEquals(TriggerConfidence.HIGH, r.candidate.confidence)
            assertEquals(listOf(Zone.REAR to Confidence.HIGH), r.damage.impactZones.map { it.zone to it.confidence })
        }

        @Test fun `기준 미만 충격(급제동·과속방지턱 수준)은 무시한다`() {
            val e = engine(CapabilityKey.IMPACT_SENSOR)
            e.at(0, Signal.VehicleSpeed(30f))
            e.at(1000, Signal.ImpactAccel(0.9f, -0.9f, 0f))
            assertNull(e.currentResult(1500))
        }

        @Test fun `센서를 지원하지 않으면 충격 값이 와도 쓰지 않는다`() {
            val e = engine()
            e.at(0, Signal.VehicleSpeed(0f))
            e.at(1000, Signal.ImpactAccel(3.0f, 3.0f, 0f))
            assertNull(e.currentResult(1500))
        }

        @Test fun `약한 충격은 MEDIUM, 방향이 비슷하면 모서리로 본다`() {
            val e = engine(CapabilityKey.IMPACT_SENSOR)
            e.at(0, Signal.VehicleSpeed(10f))
            // 뒤로 밀리고(앞에서 부딪힘) 오른쪽으로 밀림(왼쪽에서 부딪힘) → 앞 왼쪽
            e.at(1000, Signal.ImpactAccel(1.6f, longitudinalG = -1.1f, lateralG = 1.0f))
            val r = e.currentResult(1500)!!
            assertEquals(TriggerConfidence.MEDIUM, r.candidate.confidence)
            assertEquals(Zone.FRONT_LEFT, r.damage.impactZones.single().zone)
        }

        @Test fun `방향을 모르면 사고는 감지하되 부딪힌 곳은 비운다`() {
            val e = engine(CapabilityKey.IMPACT_SENSOR)
            e.at(0, Signal.VehicleSpeed(0f))
            e.at(1000, Signal.ImpactAccel(2.0f))
            val r = e.currentResult(1500)!!
            assertEquals(TriggerId.T_IMPACT, r.candidate.trigger)
            assertEquals(emptyList(), r.damage.impactZones)
        }

        @Test fun `4G 이상이면 심각도 S2`() {
            val e = engine(CapabilityKey.IMPACT_SENSOR)
            e.at(0, Signal.VehicleSpeed(0f))
            e.at(1000, Signal.ImpactAccel(4.5f, 4.4f, 0.3f))
            e.onTriage(com.alertbridge.engine.model.TriageAnswer(com.alertbridge.engine.model.TriageQ.IS_ACCIDENT, com.alertbridge.engine.model.Answer.YES, 2000))
            assertEquals(Severity.S2, e.currentResult(2500)!!.damage.severity)
        }
    }

    @Nested inner class Adas {
        @Test fun `긴급제동 직후 정차는 HIGH, 차로 이탈 경고는 쓰지 않는다`() {
            val e = engine(CapabilityKey.ADAS)
            e.at(0, Signal.VehicleSpeed(20f))
            e.at(1000, Signal.AdasWarning(AdasKind.EMERGENCY_BRAKING, true))
            e.at(4000, Signal.VehicleSpeed(0f))
            val r = e.currentResult(4500)!!
            assertEquals(TriggerId.T_ADAS_STOP, r.candidate.trigger)
            assertEquals(TriggerConfidence.HIGH, r.candidate.confidence)

            val lane = engine(CapabilityKey.ADAS)
            lane.at(0, Signal.VehicleSpeed(20f))
            lane.at(1000, Signal.AdasWarning(AdasKind.LANE_DEPARTURE, true))
            lane.at(4000, Signal.VehicleSpeed(0f))
            assertNull(lane.currentResult(4500))
        }

        @Test fun `경고 후 10초가 지나 정차하면 쓰지 않는다`() {
            val e = engine(CapabilityKey.ADAS)
            e.at(0, Signal.VehicleSpeed(20f))
            e.at(1000, Signal.AdasWarning(AdasKind.FORWARD_COLLISION, true))
            e.at(12000, Signal.VehicleSpeed(0f))
            assertNull(e.currentResult(12500))
        }
    }

    @Nested inner class StopDoor {
        private fun stopThenDoor(road: RoadType): AlertBridgeEngine {
            val e = engine()
            e.at(0, Signal.Location(37.5, 127.0, 5f, road, 0.9f))
            e.at(0, Signal.VehicleSpeed(30f))
            e.at(20000, Signal.VehicleSpeed(30f))
            e.at(21500, Signal.VehicleSpeed(22f))
            e.at(23000, Signal.VehicleSpeed(14f))
            e.at(24500, Signal.VehicleSpeed(6f))
            e.at(25500, Signal.VehicleSpeed(0f))
            e.at(30000, Signal.DoorPosition(DoorArea.ROW1_LEFT, true))
            return e
        }

        @Test fun `차로 위 감속 정차 후 문이 열리면 LOW로 묻는다`() {
            val r = stopThenDoor(RoadType.URBAN).currentResult(30500)!!
            assertEquals(TriggerId.T_STOP_DOOR, r.candidate.trigger)
            assertEquals(TriggerConfidence.LOW, r.candidate.confidence)
        }

        @Test fun `주차장에서는 문이 열려도 사고로 보지 않는다`() {
            assertNull(stopThenDoor(RoadType.PARKING).currentResult(30500))
        }
    }

    @Nested inner class FreeTow {
        @Test fun `도로 판정이 안 돼도 운전자가 고속도로라고 답하고 주행 불가면 1588-2504를 안내한다`() {
            val e = engine(CapabilityKey.IMPACT_SENSOR)
            e.at(0, Signal.VehicleSpeed(0f))
            e.at(1000, Signal.ImpactAccel(2.8f, 2.7f, 0.2f))
            val q = com.alertbridge.engine.model.TriageQ.entries
            fun answer(t: Long, question: com.alertbridge.engine.model.TriageQ, a: com.alertbridge.engine.model.Answer) =
                e.onTriage(com.alertbridge.engine.model.TriageAnswer(question, a, t))
            answer(2000, com.alertbridge.engine.model.TriageQ.IS_ACCIDENT, com.alertbridge.engine.model.Answer.YES)
            answer(3000, com.alertbridge.engine.model.TriageQ.IS_EXPRESSWAY, com.alertbridge.engine.model.Answer.YES)
            answer(4000, com.alertbridge.engine.model.TriageQ.DRIVABLE, com.alertbridge.engine.model.Answer.NO)
            val r = e.currentResult(5000)!!
            val tow = r.plan.actions.first { it.id == com.alertbridge.engine.model.ActionId.CALL_2504 }
            assertEquals("1588-2504", tow.contact?.number)
            assert(q.isNotEmpty())
        }
    }
}
