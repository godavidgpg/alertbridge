package com.alertbridge.app.adapter

import ai.pleos.playground.vehicle.api.Brake
import ai.pleos.playground.vehicle.api.Door
import ai.pleos.playground.vehicle.api.EvBattery
import ai.pleos.playground.vehicle.api.Light
import ai.pleos.playground.vehicle.api.Safety
import ai.pleos.playground.vehicle.api.Seat
import ai.pleos.playground.vehicle.api.SteeringWheel
import ai.pleos.playground.vehicle.api.Tire
import ai.pleos.playground.vehicle.api.TurnSignal
import ai.pleos.playground.vehicle.api.Wiper
import ai.pleos.playground.vehicle.api.model.area.Area
import ai.pleos.playground.vehicle.api.model.area.DoorArea as PleosDoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea as PleosSeatArea
import ai.pleos.playground.vehicle.api.model.area.WheelArea as PleosWheelArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.api.model.telltale.TelltaleData
import ai.pleos.playground.vehicle.api.model.telltale.Telltales
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.area.WheelValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.LightState
import ai.pleos.playground.vehicle.constant.control.OilLevelState
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.constant.telltale.TelltaleState
import ai.pleos.playground.vehicle.constant.telltale.TelltaleType
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.LightStateListener
import ai.pleos.playground.vehicle.listener.OilLevelStateListener
import ai.pleos.playground.vehicle.listener.PowerDownWarningIndicatorListener
import ai.pleos.playground.vehicle.listener.SeatAirbagDeployedListener
import ai.pleos.playground.vehicle.listener.SeatBeltBuckledStateListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.TelltaleInfoListener
import ai.pleos.playground.vehicle.listener.TirePressureListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import ai.pleos.playground.vehicle.listener.WiperStateListener
import com.alertbridge.engine.fixture.Scenario
import com.alertbridge.engine.model.CapabilityKey
import com.alertbridge.engine.model.CapabilityMap
import com.alertbridge.engine.model.FluidKind
import com.alertbridge.engine.model.Signal
import com.alertbridge.engine.model.TurnSignal as EngineTurnSignal
import com.alertbridge.engine.model.VehicleEvent
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * 실차 없이 Pleos SDK 형식을 재현하는 더미 차량. SDK의 실제 인터페이스·상수·콜백 형식을 그대로 쓴다.
 * (SeatValue 영역, OilLevelState, 정수 도어 위치, Float 타이어 압력, Boolean? 콜백, 에어백 종류 없음, 비상등 없음)
 *
 * 값 자체는 더미이며 실차 동작을 보증하지 않는다. 예: 타이어 기준값 200, 도어 범위 0~100은 임의 값이다.
 */
class DummyPleosVehicle(
    private val capabilities: Map<CapabilityKey, Boolean?>,
    /** Pleos 콜백이 불릴 때마다 호출 내용을 알린다. 화면·검증용 */
    var onRawCall: (String) -> Unit = {},
    /** 차속·위치는 Pleos Vehicle API가 아니라서 어댑터를 거치지 않고 바로 전달한다. */
    var auxSink: (Signal) -> Unit = {},
) {
    private val listeners = mutableMapOf<Pair<String, Any?>, Any>()
    private val current = mutableMapOf<Pair<String, Any?>, Any?>()

    val registeredCount: Int get() = listeners.size
    fun isRegistered(name: String, area: Any? = null) = (name to area) in listeners

    val apis = PleosVehicleApis(
        safety = api<Safety>(), seat = api<Seat>(), tire = api<Tire>(), door = api<Door>(), turnSignal = api<TurnSignal>(),
        brake = api<Brake>(), steeringWheel = api<SteeringWheel>(), evBattery = api<EvBattery>(), light = api<Light>(), wiper = api<Wiper>(),
    )

    private inline fun <reified T : Any> api(): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, m, args -> handle(m, args ?: emptyArray()) } as T

    private fun areaKey(a: Any?): Any? = (a as? Area)?.let { firstValue(it) }

    private fun firstValue(a: Area): Any? = when (a) {
        is PleosSeatArea -> a.values.first()
        is PleosWheelArea -> a.values.first()
        is PleosDoorArea -> a.values.first()
        is WindowArea -> a.values.first()
        else -> null
    }

    @Suppress("UNCHECKED_CAST")
    private fun handle(m: Method, args: Array<out Any?>): Any? {
        val name = m.name
        when {
            name == "toString" -> return "DummyPleos"
            name == "hashCode" -> return System.identityHashCode(this)
            name == "equals" -> return false
            name.startsWith("check") -> check(name, args)
            name.startsWith("unregister") -> listeners.remove(name.removePrefix("un").let(::channel) to areaKey(args.getOrNull(0).takeIf { args.size == 2 }))
            name.startsWith("register") -> listeners[channel(name) to areaKey(args.getOrNull(0).takeIf { args.size == 2 })] = args.last()!!
            else -> read(name, args)
        }
        return null
    }

    /** registerX와 getCurrentX/hasX/getX를 같은 키로 묶는다. */
    private fun channel(name: String) = name.removePrefix("register")

    private val readChannels = mapOf(
        "hasSeatAirbagBeenDeployed" to "SeatAirbagDeployed", "getCurrentSeatOccupancy" to "SeatOccupancy",
        "getCurrentSeatBeltBuckledState" to "SeatBeltBuckledState", "getCurrentDoorPosition" to "DoorPosition",
        "getCurrentTurnSignalState" to "TurnSignalState", "getCurrentBrakePedalStatus" to "BrakePedalStatus",
        "getCurrentSteeringWheelAngle" to "SteeringWheelAngle", "getEngineOilLevelState" to "EngineOilLevelState",
        "getBrakeFluidLevelState" to "BrakeFluidLevelState", "getTransmissionOilLevelState" to "TransmissionOilLevelState",
        "hasPowerDownWarningIndicator" to "PowerDownWarningIndicator", "getTelltalesInfo" to "Telltales",
        "getEvBatteryStateOfCharge" to "EvBatteryStateOfCharge", "getCurrentLowBeamLightState" to "LowBeamLightState",
        "getCurrentWiperState" to "WiperState",
    )

    @Suppress("UNCHECKED_CAST")
    private fun read(name: String, args: Array<out Any?>) {
        val ok = args[args.size - 2] as (Any?) -> Unit
        val err = args.last() as (Exception) -> Unit
        if (name == "getLowTirePressure") {
            ok(LOW_TIRE_THRESHOLD)
            return
        }
        val area = areaKey(args.getOrNull(0).takeIf { args.size == 3 })
        val key = readChannels[name] to area
        if (key in current) ok(current[key]) else err(IllegalStateException("아직 값 없음: $name"))
    }

    @Suppress("UNCHECKED_CAST")
    private fun check(name: String, args: Array<out Any?>) {
        val cap = when (name) {
            "checkAirbagDeployedCapabilities" -> CapabilityKey.SEAT_AIRBAG_DEPLOYED
            "checkSeatOccupancyCapabilities" -> CapabilityKey.SEAT_OCCUPANCY
            "checkSeatBeltBuckledCapabilities" -> CapabilityKey.SEAT_BELT_BUCKLED
            "checkLowTirePressureCapabilities", "checkTirePressureCapabilities" -> CapabilityKey.LOW_TIRE_PRESSURE
            "checkDoorPositionCapabilities" -> CapabilityKey.DOOR_POSITION
            "checkTurnSignalStateCapabilities" -> CapabilityKey.TURN_SIGNAL
            "checkBrakePedalStatusCapabilities" -> CapabilityKey.BRAKE_PEDAL
            "checkSteeringWheelAngleCapabilities" -> CapabilityKey.STEERING_ANGLE
            "checkEngineOilLevelStateCapabilities", "checkBrakeFluidLevelStateCapabilities", "checkTransmissionOilLevelStateCapabilities" -> CapabilityKey.FLUID_LEVEL
            "checkPowerDownWarningIndicatorCapabilities" -> CapabilityKey.POWER_DOWN_WARNING
            "checkTelltalesCapabilities" -> CapabilityKey.TELLTALES
            "checkEvBatteryStateOfChargeCapabilities" -> CapabilityKey.EV_SOC
            "checkLowBeamLightStateCapabilities" -> CapabilityKey.LOW_BEAM
            "checkWiperStateCapabilities" -> CapabilityKey.WIPER
            else -> error("더미에 없는 check: $name")
        }
        val state = capabilities[cap]
        val err = args.last() as (Exception) -> Unit
        if (state == null) {
            err(IllegalStateException("지원 여부 확인 실패: $name"))
            return
        }
        val cfg = CapabilityConfig(if (state) AccessType.READ else AccessType.NONE)
        when (val ok = args[0]) {
            is Function2<*, *, *> -> (ok as (Any?, Any?) -> Unit)(cfg, payload(name))
            else -> (ok as (Any?) -> Unit)(cfg)
        }
    }

    private fun payload(name: String): Any? = when (name) {
        "checkAirbagDeployedCapabilities", "checkSeatOccupancyCapabilities", "checkSeatBeltBuckledCapabilities" ->
            SeatValue.entries.map { PleosSeatArea(it) }
        "checkLowTirePressureCapabilities", "checkTirePressureCapabilities" ->
            WheelValue.entries.filter { it != WheelValue.UNKNOWN }.map { PleosWheelArea(it) }
        "checkDoorPositionCapabilities" -> DoorValue.entries.associate { PleosDoorArea(it) to AreaBounds(DOOR_CLOSED, DOOR_OPEN) }
        "checkSteeringWheelAngleCapabilities" -> AreaBounds(-540f, 540f)
        "checkWiperStateCapabilities" -> mapOf(WindowArea(WindowValue.FRONT_WINDSHIELD) to WiperState.entries.toList())
        "checkLowBeamLightStateCapabilities" -> LightState.entries.toList()
        else -> OilLevelState.entries.toList()
    }

    private inline fun <reified L> fire(channel: String, area: Any?, value: Any?, call: L.() -> Unit) {
        val key = channel to area
        current[key] = value
        (listeners[key] as? L)?.let {
            onRawCall("on${channel}${area?.let { a -> "[$a]" }.orEmpty()}($value)")
            it.call()
        }
    }

    /** 엔진 형식 시나리오 이벤트를 Pleos 콜백 형식으로 바꿔 보낸다. */
    fun deliver(e: VehicleEvent) {
        when (val s = e.signal) {
            is Signal.SeatAirbagDeployed -> {
                // 시나리오의 airbagKind는 버린다. 실제 SDK도 주지 않는다.
                val v = seat(s.seat.name)
                fire<SeatAirbagDeployedListener>("SeatAirbagDeployed", v, s.deployed) { onSeatAirbagDeployUpdated(s.deployed) }
            }
            is Signal.SeatOccupancy -> seat(s.seat.name).let { v ->
                fire<SeatOccupancyListener>("SeatOccupancy", v, s.occupied) { onSeatOccupancyUpdated(s.occupied) }
            }
            is Signal.SeatBeltBuckled -> seat(s.seat.name).let { v ->
                fire<SeatBeltBuckledStateListener>("SeatBeltBuckledState", v, s.buckled) { onSeatBeltBuckledStateUpdated(s.buckled) }
            }
            is Signal.LowTirePressure -> {
                val psi = if (s.low) TIRE_LOW else TIRE_NORMAL
                fire<TirePressureListener>("TirePressure", WheelValue.valueOf(s.wheel.name), psi) { onTirePressureUpdated(psi) }
            }
            is Signal.DoorPosition -> {
                val pos = if (s.open) DOOR_OPEN else DOOR_CLOSED
                val v = DoorValue.valueOf(s.door.name.replace("ROW", "ROW_"))
                fire<DoorPositionListener>("DoorPosition", v, pos) { onPositionUpdated(pos) }
            }
            is Signal.TurnSignalState -> {
                val v = when (s.state) {
                    EngineTurnSignal.LEFT -> TurnSignalValue.LEFT
                    EngineTurnSignal.RIGHT -> TurnSignalValue.RIGHT
                    EngineTurnSignal.NONE -> TurnSignalValue.OFF
                    EngineTurnSignal.HAZARD -> return // SDK에 비상등 값이 없다.
                }
                fire<TurnSignalStateListener>("TurnSignalState", null, v) { onTurnSignalStateUpdated(v) }
            }
            is Signal.BrakeState -> fire<BrakePedalStatusListener>("BrakePedalStatus", null, s.pedalPressed) { onBrakePedalStatusUpdated(s.pedalPressed) }
            is Signal.SteeringAngle -> fire<SteeringWheelAngleListener>("SteeringWheelAngle", null, s.degrees) { onSteeringWheelAngleUpdated(s.degrees) }
            is Signal.FluidLevel -> {
                val v = OilLevelState.valueOf(if (s.state.name == "UNKNOWN") "ERROR" else s.state.name)
                val channel = when (s.kind) {
                    FluidKind.ENGINE_OIL -> "EngineOilLevelState"
                    FluidKind.BRAKE_FLUID -> "BrakeFluidLevelState"
                    FluidKind.TRANSMISSION_OIL -> "TransmissionOilLevelState"
                }
                fire<OilLevelStateListener>(channel, null, v) { onOilLevelUpdated(v) }
            }
            is Signal.PowerDownWarning -> fire<PowerDownWarningIndicatorListener>("PowerDownWarningIndicator", null, s.on) { onPowerDownWarningIndicatorUpdated(s.on) }
            is Signal.Telltale -> {
                val t = Telltales(listOf(TelltaleData(TelltaleType.valueOf(s.telltaleType), TelltaleState.valueOf(s.state), 0, 0, 0)))
                fire<TelltaleInfoListener>("Telltales", null, t) { onTelltaleInfoUpdated(t) }
            }
            is Signal.EvStateOfCharge -> s.percent.toInt().let { v ->
                fire<EvBatteryStateOfChargeListener>("EvBatteryStateOfCharge", null, v) { onEvBatteryStateOfChargeUpdated(v) }
            }
            is Signal.LowBeamLight -> {
                val v = if (s.on) LightState.ON else LightState.OFF
                fire<LightStateListener>("LowBeamLightState", null, v) { onLightStateUpdated(v) }
            }
            is Signal.WiperActive -> {
                val v = if (s.active) WiperState.ON else WiperState.OFF
                fire<WiperStateListener>("WiperState", WindowValue.FRONT_WINDSHIELD, v) { onWiperStateUpdated(v) }
            }
            is Signal.VehicleSpeed, is Signal.Location -> auxSink(s)
        }
    }

    /** 시험에서 비정상 콜백을 흉내 낸다. */
    internal fun rawListener(channel: String, area: Any? = null): Any? = listeners[channel to area]

    private fun seat(engineName: String) = SeatValue.valueOf(engineName.replace("ROW", "ROW_"))

    companion object {
        const val LOW_TIRE_THRESHOLD = 200f
        const val TIRE_LOW = 170f
        const val TIRE_NORMAL = 230f
        const val DOOR_CLOSED = 0
        const val DOOR_OPEN = 100
    }
}

/**
 * 시나리오 → 더미 Pleos 콜백 → PleosSignalSource(실제 어댑터) → 엔진.
 * [realLocation]을 주면 시나리오의 위치를 버리고 실제 GPS를 쓴다.
 */
class DummyPleosSource(
    scenario: Scenario,
    private val realLocation: SignalSource? = null,
    onRawCall: (String) -> Unit = {},
) : SignalSource {
    val playback = FixtureSignalSource(scenario)
    private val vehicle = DummyPleosVehicle(scenario.capabilities, onRawCall)
    private val aux = mapOf(
        CapabilityKey.VEHICLE_SPEED to scenario.capabilities[CapabilityKey.VEHICLE_SPEED],
        CapabilityKey.LOCATION to scenario.capabilities[CapabilityKey.LOCATION],
    )
    /** 재생 중인 이벤트의 기록 시각. 메인 스레드가 잠깐 멈춰도 시각이 밀려 판정이 달라지지 않게 한다. */
    private var replayingTms: Long? = null
    private val pleos = PleosSignalSource(vehicle.apis, aux) { replayingTms ?: playback.nowMs() }

    override suspend fun capabilities(): CapabilityMap {
        val caps = pleos.capabilities()
        val gps = realLocation?.capabilities()?.supported?.get(CapabilityKey.LOCATION) ?: return caps
        return CapabilityMap(caps.supported + (CapabilityKey.LOCATION to gps))
    }

    override fun nowMs(): Long = playback.nowMs()

    override fun events(): Flow<VehicleEvent> = channelFlow {
        vehicle.auxSink = { s -> if (realLocation == null || s !is Signal.Location) trySend(VehicleEvent(replayingTms ?: nowMs(), s)) }
        val unregister = pleos.register { trySend(it) }
        realLocation?.let { gps -> launch { gps.events().collect { send(it.copy(tMs = nowMs())) } } }
        launch {
            playback.events().collect {
                replayingTms = it.tMs
                vehicle.deliver(it)
                replayingTms = null
            }
        }
        awaitClose(unregister)
    }
}
