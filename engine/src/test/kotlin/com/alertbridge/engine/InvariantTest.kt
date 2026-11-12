package com.alertbridge.engine

import com.alertbridge.engine.fixture.Scenario
import com.alertbridge.engine.model.*
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.io.File
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InvariantTest {
    private fun assertClean(r: EngineResult?, caps: CapabilityMap, confirmed: Boolean, label: String) {
        if (r == null) return
        val v = invariantViolations(r, caps, confirmed)
        assertTrue(v.isEmpty(), "$label: $v")
    }

    @TestFactory
    fun `모든 시나리오에서 불변 조건이 유지된다`(): List<DynamicTest> = Fixtures.scenarios.map { s ->
        DynamicTest.dynamicTest(s.id) {
            assertClean(run(s).result, s.capabilityMap, false, s.id)
        }
    }

    /** 신호를 하나씩 끄거나 "알 수 없음"으로 바꿔 전 시나리오를 다시 돌린다. */
    @TestFactory
    fun `어떤 신호가 빠져도 그 신호를 근거로 쓰지 않는다`(): List<DynamicTest> =
        Fixtures.scenarios.flatMap { s ->
            CapabilityKey.entries.flatMap { key ->
                listOf(false, null).map { value ->
                    DynamicTest.dynamicTest("${s.id} $key=$value") {
                        val changed = s.copy(capabilities = s.capabilities + (key to value))
                        assertClean(run(changed).result, changed.capabilityMap, false, "${s.id} $key=$value")
                    }
                }
            }
        }

    @Test
    fun `도로 신뢰도가 낮으면 고속도로 응답이 있어도 2504는 없다`() {
        val base = Fixtures.scenario("S01")
        for (confidence in listOf(0.0f, 0.3f, 0.69f)) {
            val lowered = base.copy(
                events = base.events.map { e ->
                    val sig = e.signal
                    if (sig is Signal.Location) e.copy(signal = sig.copy(roadTypeConfidence = confidence)) else e
                },
                userActions = listOf(UserActionEvent.ManualReport(35_000)),
                triage = listOf(TriageAnswer(TriageQ.IS_EXPRESSWAY, Answer.YES, 36_000)),
            )
            val r = run(lowered).result!!
            assertTrue(r.plan.find(ActionId.CALL_2504) == null, "신뢰도 $confidence")
            assertTrue(RiskGrade.R1 in r.risk.has, "사용자 응답으로 R1은 인정한다")
        }
    }

    @Test
    fun `엔진에는 발신 기능이 없다`() {
        val names = listOf(AlertBridgeEngine::class.java).flatMap { c -> c.methods.map { it.name.lowercase() } }
        assertTrue(names.none { it.contains("dial") || it.contains("placecall") || it.contains("sendsms") }, "$names")
        val root = File(System.getProperty("alertbridge.root"))
        val code = root.resolve("engine/src/main/kotlin").walkTopDown().filter { it.extension == "kt" }
        code.forEach { f ->
            val text = f.readText()
            listOf("ACTION_CALL", "ACTION_DIAL", "Intent(").forEach { assertFalse(it in text, "${f.name}: $it") }
            assertFalse(text.lines().any { it.startsWith("import android") || it.startsWith("import ai.pleos") || it.startsWith("import androidx") }, f.name)
        }
    }

    @Test
    fun `앱은 직접 발신을 쓰지 않는다`() {
        val root = File(System.getProperty("alertbridge.root")).resolve("app/src/main")
        if (!root.exists()) return
        root.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "xml") }.forEach { f ->
            val text = f.readText()
            assertFalse(text.contains("ACTION_CALL"), "${f.name}에 직접 발신 인텐트")
            assertFalse(text.contains("CALL_PHONE"), "${f.name}에 발신 권한")
        }
    }

    /** 무작위 신호·응답·행동 열. 어떤 입력에서도 불변 조건이 깨지거나 예외가 나면 안 된다. */
    @Test
    fun `무작위 입력에서도 불변 조건이 유지된다`() {
        repeat(400) { seed ->
            val rnd = Random(seed)
            val caps = CapabilityMap(CapabilityKey.entries.associateWith { listOf(true, true, true, false, null).random(rnd) })
            val profile = Fixtures.profile(Fixtures.scenarios.random(rnd))
            val vehicle = VehicleProfile(Powertrain.entries.random(rnd), VehicleSizeClass.entries.random(rnd))
            val engine = AlertBridgeEngine(Fixtures.policy, Fixtures.messages)
            engine.configure(caps, profile, vehicle)

            var now = 0L
            var confirmed = false
            var roadConfidence = 0f
            repeat(rnd.nextInt(10, 120)) {
                now += rnd.nextLong(100, 6_000)
                when (rnd.nextInt(10)) {
                    in 0..6 -> {
                        val sig = randomSignal(rnd)
                        if (sig is Signal.Location && caps.isSupported(CapabilityKey.LOCATION)) roadConfidence = sig.roadTypeConfidence
                        engine.onEvent(VehicleEvent(now, sig))
                    }
                    7 -> engine.onTriage(TriageAnswer(TriageQ.entries.random(rnd), Answer.entries.random(rnd), now))
                    else -> {
                        val u = randomUserAction(rnd, now)
                        if (u is UserActionEvent.MarkedDone || u is UserActionEvent.ReceiptNumberEntered) confirmed = true
                        engine.onUserAction(u)
                    }
                }
                val r = engine.currentResult(now)
                assertClean(r, caps, confirmed, "seed=$seed t=$now")
                // 도로 판정이 불확실하면 2504를 제안하지 않는다. 단, 운전자가 고속도로라고 답했고 주행 불가면 문의를 안내한다.
                val userSaysExpressway = r != null && r.damage.drivable == com.alertbridge.engine.model.Tri.NO &&
                    r.risk.grades.any { com.alertbridge.engine.model.ReasonCode.EXPRESSWAY_REPORTED_BY_USER in it.because }
                if (roadConfidence < Fixtures.policy.thresholds.roadTypeMinConfidence && !userSaysExpressway) {
                    assertTrue(r?.plan?.find(ActionId.CALL_2504) == null, "seed=$seed t=$now 신뢰도 $roadConfidence 인데 2504 제안")
                }
            }
        }
    }

    private fun randomSignal(rnd: Random): Signal = when (rnd.nextInt(14)) {
        0 -> Signal.SeatAirbagDeployed(SeatArea.entries.random(rnd), rnd.nextInt(8) == 0, AirbagKind.entries.plus(listOf<AirbagKind?>(null)).random(rnd))
        1 -> Signal.SeatOccupancy(SeatArea.entries.random(rnd), rnd.nextBoolean())
        2 -> Signal.SeatBeltBuckled(SeatArea.entries.random(rnd), rnd.nextBoolean())
        3 -> Signal.LowTirePressure(WheelArea.entries.random(rnd), rnd.nextBoolean())
        4 -> Signal.DoorPosition(DoorArea.entries.random(rnd), rnd.nextBoolean())
        5 -> Signal.TurnSignalState(TurnSignal.entries.random(rnd))
        6 -> Signal.BrakeState(rnd.nextBoolean())
        7 -> Signal.SteeringAngle(rnd.nextInt(-90, 90).toFloat())
        8, 9 -> Signal.VehicleSpeed(listOf(0f, 0f, 3f, 10f, 40f, 80f, 110f).random(rnd))
        10 -> Signal.FluidLevel(FluidKind.entries.random(rnd), LevelState.entries.random(rnd))
        11 -> Signal.PowerDownWarning(rnd.nextBoolean())
        12 -> Signal.Telltale(listOf("AIRBAG", "ENGINE", "TURN_SIGNAL_LEFT").random(rnd), listOf("ON", "OFF", "BLINK").random(rnd))
        else -> Signal.Location(37.0, 127.0, null, RoadType.entries.random(rnd), rnd.nextFloat(), listOf("경부고속도로", null).random(rnd), null, null)
    }

    private fun randomUserAction(rnd: Random, t: Long): UserActionEvent {
        val id = ActionId.entries.random(rnd)
        return when (rnd.nextInt(9)) {
            0 -> UserActionEvent.ManualReport(t)
            1 -> UserActionEvent.SupportSelected(id, t)
            2, 3 -> UserActionEvent.ContactAttempted(id, t)
            4 -> UserActionEvent.GuardianNotified(t)
            5 -> UserActionEvent.IncidentDetail(rnd.nextInt(1, 4), rnd.nextBoolean(), t)
            6 -> if (rnd.nextInt(4) == 0) UserActionEvent.MarkedDone(id, t) else UserActionEvent.TowDestinationSet(rnd.nextFloat() * 100, t)
            7 -> if (rnd.nextInt(4) == 0) UserActionEvent.ReceiptNumberEntered(id, "N-1", t) else UserActionEvent.PhotoTaken(null, null, t)
            else -> listOf(UserActionEvent.CancelSession(t), UserActionEvent.CloseSession(t), UserActionEvent.EnterRecovery(t)).random(rnd)
        }
    }

    @Test
    fun `같은 입력이면 같은 해시가 나온다`() {
        val s: Scenario = Fixtures.scenario("S03")
        val a = run(s).result!!.session.caseCard!!.frozenSha256
        val b = run(s).result!!.session.caseCard!!.frozenSha256
        assertEquals(a, b)
        assertEquals(64, a.length)
    }
}
