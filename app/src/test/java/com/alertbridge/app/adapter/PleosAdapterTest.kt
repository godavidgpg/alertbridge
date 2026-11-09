package com.alertbridge.app.adapter

import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.area.WheelValue
import ai.pleos.playground.vehicle.constant.control.OilLevelState
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import com.alertbridge.engine.AlertBridgeEngine
import com.alertbridge.engine.fixture.Expected
import com.alertbridge.engine.fixture.Scenario
import com.alertbridge.engine.fixture.ScenarioJson
import com.alertbridge.engine.fixture.mismatches
import com.alertbridge.engine.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 더미 차량이 실제 SDK 형식으로 값을 보내고, 실제 어댑터를 거쳐 엔진까지 간다. */
class PleosAdapterTest {
    private val resources = File("../engine/src/test/resources")
    private val allCaps = CapabilityKey.entries.associateWith<CapabilityKey, Boolean?> { it != CapabilityKey.HAZARD_STATE }

    private fun scenarios(): List<Scenario> = File(resources, "scenarios").listFiles()!!.sortedBy { it.name }
        .map { ScenarioJson.scenario(it.readText(Charsets.UTF_8)) }

    private fun profile(s: Scenario) = s.profile?.let { ScenarioJson.profile(File(resources, it).readText(Charsets.UTF_8)) }

    private class Rig(caps: Map<CapabilityKey, Boolean?>) {
        var now = 0L
        val raw = mutableListOf<String>()
        val out = mutableListOf<Signal>()
        val vehicle = DummyPleosVehicle(caps, { raw += it })
        val source = PleosSignalSource(vehicle.apis, emptyMap()) { now }
        val capabilities: CapabilityMap = runBlocking { source.capabilities() }
        val unregister = source.register { out += it.signal }
        fun send(s: Signal) = vehicle.deliver(VehicleEvent(now, s))
    }

    /** 시나리오를 Pleos 형식으로 재생해 엔진 결과를 얻는다. */
    private fun play(s: Scenario, rawLog: MutableList<String> = mutableListOf()): EngineResult? {
        var now = 0L
        val vehicle = DummyPleosVehicle(s.capabilities, { rawLog += it })
        val aux = mapOf(
            CapabilityKey.VEHICLE_SPEED to s.capabilities[CapabilityKey.VEHICLE_SPEED],
            CapabilityKey.LOCATION to s.capabilities[CapabilityKey.LOCATION],
        )
        val source = PleosSignalSource(vehicle.apis, aux) { now }
        val engine = AlertBridgeEngine.withDefaultPolicy()
        engine.configure(runBlocking { source.capabilities() }, profile(s), s.vehicle)
        vehicle.auxSink = { engine.onEvent(VehicleEvent(now, it)) }
        val unregister = source.register(engine::onEvent)

        class Step(val t: Long, val order: Int, val run: () -> Unit)
        val steps = s.events.map { e -> Step(e.tMs, 0) { vehicle.deliver(e) } } +
            s.userActions.map { u -> Step(u.tMs, 1) { engine.onUserAction(u) } } +
            s.triage.map { a -> Step(a.tMs, 2) { engine.onTriage(a) } }
        steps.sortedWith(compareBy({ it.t }, { it.order })).forEach {
            now = it.t
            it.run()
        }
        now = s.runUntilMs ?: steps.maxOf { it.t }
        val result = engine.currentResult(now)
        unregister()
        assertEquals(0, vehicle.registeredCount, "${s.id}: 리스너 해제 누락")
        return result
    }

    @Test
    fun `시나리오 12개가 Pleos 형식을 거쳐도 기대 결과를 낸다`() {
        val failures = scenarios().associate { s ->
            // SDK는 에어백 종류를 주지 않는다. S03은 측면(LEFT) 대신 "종류 구분 불가" 규칙(FRONT)이 된다.
            val expected: Expected = if (s.id.startsWith("S03")) s.expected.copy(impactZonesContain = listOf(Zone.FRONT)) else s.expected
            val result = play(s)
            s.id to if (result == null) listOf("결과 없음") else expected.mismatches(result)
        }.filterValues { it.isNotEmpty() }
        assertTrue(failures.isEmpty(), failures.entries.joinToString("\n") { "${it.key}: ${it.value}" })
        assertEquals(12, scenarios().size)
    }

    @Test
    fun `S03 실제 SDK 형식에서는 정면 MEDIUM으로 추정하되 신고 문장에는 넣지 않는다`() {
        val r = play(scenarios().first { it.id.startsWith("S03") })!!
        val z = r.damage.impactZones.single()
        assertEquals(Zone.FRONT, z.zone)
        assertEquals(Confidence.MEDIUM, z.confidence)
        assertEquals(listOf(ReasonCode.AIRBAG_UNKNOWN_KIND), z.because)
        val scripts = r.plan.actions.mapNotNull { it.script?.text }.joinToString(" | ")
        assertFalse("충격 위치" in scripts, scripts)
        assertTrue("에어백" in scripts && "안전벨트" in scripts, scripts)
    }

    @Test
    fun `실제로 Pleos 콜백을 거친다`() {
        val raw = mutableListOf<String>()
        play(scenarios().first { it.id.startsWith("S03") }, raw)
        assertTrue(raw.contains("onSeatOccupancy[ROW_2_RIGHT](true)"), "$raw")
        assertTrue(raw.contains("onSeatBeltBuckledState[ROW_2_RIGHT](false)"), "$raw")
        assertTrue(raw.contains("onSeatAirbagDeployed[ROW_1_LEFT](true)"), "$raw")
    }

    @Test
    fun `S08 미지원 신호에는 리스너를 등록하지 않고 false로 넘긴다`() {
        val rig = Rig(mapOf(CapabilityKey.SEAT_AIRBAG_DEPLOYED to false, CapabilityKey.SEAT_OCCUPANCY to false))
        assertEquals(false, rig.capabilities.supported[CapabilityKey.SEAT_AIRBAG_DEPLOYED])
        assertEquals(0, rig.vehicle.registeredCount)
        rig.send(Signal.SeatOccupancy(SeatArea.ROW1_LEFT, true))
        assertTrue(rig.out.isEmpty())
    }

    @Test
    fun `지원 여부 확인이 실패하면 false가 아니라 null이다`() {
        val rig = Rig(emptyMap())
        assertNull(rig.capabilities.supported[CapabilityKey.SEAT_AIRBAG_DEPLOYED])
        assertTrue(CapabilityKey.SEAT_AIRBAG_DEPLOYED in rig.capabilities.supported)
        assertFalse(rig.capabilities.isSupported(CapabilityKey.SEAT_AIRBAG_DEPLOYED))
    }

    @Test
    fun `비상등은 SDK에 없어 항상 알 수 없음이다`() {
        val rig = Rig(allCaps + (CapabilityKey.HAZARD_STATE to true))
        assertNull(rig.capabilities.supported[CapabilityKey.HAZARD_STATE])
    }

    @Test
    fun `상수 변환표가 빠짐없다`() {
        assertEquals(SeatArea.ROW2_RIGHT, PleosMapping.seat(SeatValue.ROW_2_RIGHT))
        assertEquals(SeatArea.entries.toSet(), SeatValue.entries.map(PleosMapping::seat).toSet())
        assertEquals(WheelArea.entries.toSet(), WheelValue.entries.mapNotNull(PleosMapping::wheel).toSet())
        assertNull(PleosMapping.wheel(WheelValue.UNKNOWN))
        assertEquals(DoorArea.entries.toSet(), DoorValue.entries.map(PleosMapping::door).toSet())
        assertEquals(TurnSignal.NONE, PleosMapping.turn(TurnSignalValue.OFF))
        assertEquals(LevelState.CRITICALLY_LOW, PleosMapping.level(OilLevelState.CRITICALLY_LOW))
    }

    @Test
    fun `타이어 압력 Float를 저압 기준값과 비교한다`() {
        val rig = Rig(allCaps)
        rig.send(Signal.LowTirePressure(WheelArea.LEFT_FRONT, true))
        rig.send(Signal.LowTirePressure(WheelArea.LEFT_FRONT, false))
        assertEquals(
            listOf(Signal.LowTirePressure(WheelArea.LEFT_FRONT, true), Signal.LowTirePressure(WheelArea.LEFT_FRONT, false)),
            rig.out.filterIsInstance<Signal.LowTirePressure>(),
        )
        assertTrue(rig.raw.contains("onTirePressure[LEFT_FRONT](170.0)"), "${rig.raw}")
    }

    @Test
    fun `도어 정수 위치를 열림으로 바꾼다`() {
        val rig = Rig(allCaps)
        rig.send(Signal.DoorPosition(DoorArea.ROW1_LEFT, true))
        rig.send(Signal.DoorPosition(DoorArea.ROW1_LEFT, false))
        assertEquals(listOf(true, false), rig.out.filterIsInstance<Signal.DoorPosition>().map { it.open })
        assertTrue(rig.raw.contains("onDoorPosition[ROW_1_LEFT](100)"), "${rig.raw}")
    }

    @Test
    fun `콜백 값이 null이거나 실패하면 이벤트를 만들지 않는다`() {
        val rig = Rig(allCaps)
        val l = rig.vehicle.rawListener("SeatOccupancy", SeatValue.ROW_1_LEFT) as SeatOccupancyListener
        l.onSeatOccupancyUpdated(null)
        l.onFailed(IllegalStateException("서비스 끊김"))
        assertTrue(rig.out.isEmpty(), "${rig.out}")
    }

    @Test
    fun `등록 전 값도 현재 값 읽기로 들어온다`() {
        val vehicle = DummyPleosVehicle(allCaps)
        val source = PleosSignalSource(vehicle.apis)
        runBlocking { source.capabilities() }
        vehicle.deliver(VehicleEvent(0, Signal.SeatOccupancy(SeatArea.ROW1_LEFT, true)))
        val out = mutableListOf<Signal>()
        source.register { out += it.signal }
        assertEquals(listOf<Signal>(Signal.SeatOccupancy(SeatArea.ROW1_LEFT, true)), out)
    }

    @Test
    fun `경고등 목록을 항목별로 펼치고 경고가 아닌 등은 사건을 만들지 않는다`() {
        val engine = AlertBridgeEngine.withDefaultPolicy()
        val rig = Rig(allCaps)
        engine.configure(rig.capabilities)
        rig.send(Signal.Telltale("TURN_SIGNAL_LEFT", "BLINK"))
        rig.out.forEach { engine.onEvent(VehicleEvent(10_000, it)) }
        assertNull(engine.currentResult(10_000))
    }

    @Test
    fun `차량 데이터로 사고 감지부터 119까지 간다`() {
        var now = 0L
        val vehicle = DummyPleosVehicle(allCaps)
        val source = PleosSignalSource(vehicle.apis, emptyMap()) { now }
        val engine = AlertBridgeEngine.withDefaultPolicy()
        engine.configure(runBlocking { source.capabilities() })
        source.register(engine::onEvent)
        fun send(t: Long, s: Signal) { now = t; vehicle.deliver(VehicleEvent(t, s)) }

        send(0, Signal.SeatOccupancy(SeatArea.ROW1_LEFT, true))
        send(0, Signal.SeatBeltBuckled(SeatArea.ROW1_LEFT, false))
        send(10_000, Signal.SeatAirbagDeployed(SeatArea.ROW1_LEFT, true))
        val r = engine.currentResult(10_000)!!
        assertEquals(TriggerId.T_AIRBAG, r.candidate.trigger)
        assertEquals(ActionId.CALL_119, r.plan.first()?.id)
        assertTrue(r.plan.first()!!.needsConfirm)
        assertFalse(r.plan.first()!!.autoExecutable)
    }
}
