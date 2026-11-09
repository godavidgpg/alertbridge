package com.alertbridge.app.adapter

import ai.pleos.playground.vehicle.Vehicle
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
import ai.pleos.playground.vehicle.api.model.area.DoorArea as PleosDoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea as PleosSeatArea
import ai.pleos.playground.vehicle.api.model.area.WheelArea as PleosWheelArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.api.model.telltale.Telltales
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.area.WheelValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.LightState
import ai.pleos.playground.vehicle.constant.control.OilLevelState
import ai.pleos.playground.vehicle.constant.control.WiperState
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
import com.alertbridge.engine.model.CapabilityKey
import com.alertbridge.engine.model.CapabilityMap
import com.alertbridge.engine.model.DoorArea
import com.alertbridge.engine.model.FluidKind
import com.alertbridge.engine.model.LevelState
import com.alertbridge.engine.model.SeatArea
import com.alertbridge.engine.model.Signal
import com.alertbridge.engine.model.TurnSignal as EngineTurnSignal
import com.alertbridge.engine.model.VehicleEvent
import com.alertbridge.engine.model.WheelArea
import kotlin.coroutines.resume
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine

/** 어댑터가 쓰는 Pleos API. 실차는 [from], 시험은 DummyPleosVehicle이 채운다. */
class PleosVehicleApis(
    val safety: Safety,
    val seat: Seat,
    val tire: Tire,
    val door: Door,
    val turnSignal: TurnSignal,
    val brake: Brake,
    val steeringWheel: SteeringWheel,
    val evBattery: EvBattery,
    val light: Light,
    val wiper: Wiper,
) {
    companion object {
        fun from(v: Vehicle) = PleosVehicleApis(
            v.getSafety(), v.getSeat(), v.getTire(), v.getDoor(), v.getTurnSignal(),
            v.getBrake(), v.getSteeringWheel(), v.getEvBattery(), v.getLight(), v.getWiper(),
        )
    }
}

/** Pleos 상수 → 엔진 enum 변환표. docs/pleos-api-mapping.md 5절 */
object PleosMapping {
    fun seat(v: SeatValue): SeatArea = when (v) {
        SeatValue.ROW_1_LEFT -> SeatArea.ROW1_LEFT
        SeatValue.ROW_1_CENTER -> SeatArea.ROW1_CENTER
        SeatValue.ROW_1_RIGHT -> SeatArea.ROW1_RIGHT
        SeatValue.ROW_2_LEFT -> SeatArea.ROW2_LEFT
        SeatValue.ROW_2_CENTER -> SeatArea.ROW2_CENTER
        SeatValue.ROW_2_RIGHT -> SeatArea.ROW2_RIGHT
        SeatValue.ROW_3_LEFT -> SeatArea.ROW3_LEFT
        SeatValue.ROW_3_CENTER -> SeatArea.ROW3_CENTER
        SeatValue.ROW_3_RIGHT -> SeatArea.ROW3_RIGHT
    }

    fun wheel(v: WheelValue): WheelArea? = when (v) {
        WheelValue.LEFT_FRONT -> WheelArea.LEFT_FRONT
        WheelValue.RIGHT_FRONT -> WheelArea.RIGHT_FRONT
        WheelValue.LEFT_REAR -> WheelArea.LEFT_REAR
        WheelValue.RIGHT_REAR -> WheelArea.RIGHT_REAR
        WheelValue.UNKNOWN -> null
    }

    fun door(v: DoorValue): DoorArea = when (v) {
        DoorValue.ROW_1_LEFT -> DoorArea.ROW1_LEFT
        DoorValue.ROW_1_RIGHT -> DoorArea.ROW1_RIGHT
        DoorValue.ROW_2_LEFT -> DoorArea.ROW2_LEFT
        DoorValue.ROW_2_RIGHT -> DoorArea.ROW2_RIGHT
        DoorValue.ROW_3_LEFT -> DoorArea.ROW3_LEFT
        DoorValue.ROW_3_RIGHT -> DoorArea.ROW3_RIGHT
        DoorValue.HOOD -> DoorArea.HOOD
        DoorValue.REAR -> DoorArea.REAR
    }

    fun turn(v: TurnSignalValue): EngineTurnSignal = when (v) {
        TurnSignalValue.OFF -> EngineTurnSignal.NONE
        TurnSignalValue.LEFT -> EngineTurnSignal.LEFT
        TurnSignalValue.RIGHT -> EngineTurnSignal.RIGHT
    }

    fun level(v: OilLevelState): LevelState = when (v) {
        OilLevelState.NORMAL -> LevelState.NORMAL
        OilLevelState.LOW -> LevelState.LOW
        OilLevelState.CRITICALLY_LOW -> LevelState.CRITICALLY_LOW
        OilLevelState.HIGH -> LevelState.HIGH
        OilLevelState.ERROR -> LevelState.ERROR
    }
}

/**
 * Pleos Vehicle SDK 2.3.2의 콜백을 엔진 입력으로 바꾼다.
 *
 * - check...Capabilities: 읽기 가능 → true, AccessType NONE·WRITE 또는 영역 없음 → false, 오류 → null(알 수 없음)
 * - 지원되는 신호·영역에만 리스너를 등록한다.
 * - 콜백 값이 null이거나 onFailed가 오면 이벤트를 만들지 않는다. false로 바꾸면 "없음"으로 오해되기 때문이다.
 * - 차속·위치는 Pleos Vehicle API가 아니므로 [auxCapabilities]로 지원 여부만 받는다. 값은 호출자가 넣는다.
 */
class PleosSignalSource(
    private val apis: PleosVehicleApis,
    private val auxCapabilities: Map<CapabilityKey, Boolean?> = emptyMap(),
    private val clock: () -> Long = System::currentTimeMillis,
) : SignalSource {

    private sealed interface Probe<out T> {
        data class Readable<T>(val value: T) : Probe<T>
        data object NotReadable : Probe<Nothing>
        data object Failed : Probe<Nothing>
    }

    private fun Probe<*>.toCapability(): Boolean? = when (this) {
        is Probe.Readable -> true
        Probe.NotReadable -> false
        Probe.Failed -> null
    }

    private fun CapabilityConfig.readable() = accessType == AccessType.READ || accessType == AccessType.READ_WRITE

    /** 값이 없는 check 호출 */
    private suspend fun probe(call: (ok: (CapabilityConfig) -> Unit, err: (Exception) -> Unit) -> Unit): Probe<Unit> =
        suspendCancellableCoroutine { c ->
            call({ cfg -> c.resume(if (cfg.readable()) Probe.Readable(Unit) else Probe.NotReadable) }, { c.resume(Probe.Failed) })
        }

    /** 영역 목록을 함께 주는 check 호출. 영역이 비면 지원하지 않는 것으로 본다. */
    private suspend fun <T> probeList(call: (ok: (CapabilityConfig, List<T>) -> Unit, err: (Exception) -> Unit) -> Unit): Probe<List<T>> =
        suspendCancellableCoroutine { c ->
            call(
                { cfg, list -> c.resume(if (cfg.readable() && list.isNotEmpty()) Probe.Readable(list) else Probe.NotReadable) },
                { c.resume(Probe.Failed) },
            )
        }

    private var airbagSeats = emptyList<SeatValue>()
    private var occupancySeats = emptyList<SeatValue>()
    private var beltSeats = emptyList<SeatValue>()
    private var tireWheels = emptyList<WheelValue>()
    private var doorMins = emptyMap<DoorValue, Int>()
    private var fluids = emptyList<FluidKind>()
    private val on = mutableSetOf<CapabilityKey>()

    override suspend fun capabilities(): CapabilityMap {
        val a = apis
        val result = mutableMapOf<CapabilityKey, Boolean?>()
        fun record(key: CapabilityKey, p: Probe<*>) {
            result[key] = p.toCapability()
            if (p is Probe.Readable) on += key
        }

        val airbag = probeList<PleosSeatArea> { ok, err -> a.safety.checkAirbagDeployedCapabilities(ok, err) }
        record(CapabilityKey.SEAT_AIRBAG_DEPLOYED, airbag)
        airbagSeats = (airbag as? Probe.Readable)?.value.orEmpty().flatMap { it.values.toList() }

        val occupancy = probeList<PleosSeatArea> { ok, err -> a.seat.checkSeatOccupancyCapabilities(ok, err) }
        record(CapabilityKey.SEAT_OCCUPANCY, occupancy)
        occupancySeats = (occupancy as? Probe.Readable)?.value.orEmpty().flatMap { it.values.toList() }

        val belt = probeList<PleosSeatArea> { ok, err -> a.seat.checkSeatBeltBuckledCapabilities(ok, err) }
        record(CapabilityKey.SEAT_BELT_BUCKLED, belt)
        beltSeats = (belt as? Probe.Readable)?.value.orEmpty().flatMap { it.values.toList() }

        // 저압 기준값과 현재 압력을 둘 다 읽어야 저압인지 판단할 수 있다.
        val lowTire = probeList<PleosWheelArea> { ok, err -> a.tire.checkLowTirePressureCapabilities(ok, err) }
        val tire = probeList<PleosWheelArea> { ok, err -> a.tire.checkTirePressureCapabilities(ok, err) }
        record(CapabilityKey.LOW_TIRE_PRESSURE, if (lowTire is Probe.Readable) tire else lowTire)
        if (lowTire is Probe.Readable && tire is Probe.Readable) {
            val readable = tire.value.flatMap { it.values.toList() }.toSet()
            tireWheels = lowTire.value.flatMap { it.values.toList() }.filter { it in readable && PleosMapping.wheel(it) != null }
        }

        val door = suspendCancellableCoroutine<Probe<Map<DoorValue, Int>>> { c ->
            a.door.checkDoorPositionCapabilities(
                { cfg: CapabilityConfig, bounds: Map<PleosDoorArea, ai.pleos.playground.vehicle.api.model.capability.AreaBounds<Int>> ->
                    val mins = bounds.flatMap { (area, b) -> area.values.map { it to b.minValue } }.toMap()
                    c.resume(if (cfg.readable() && mins.isNotEmpty()) Probe.Readable(mins) else Probe.NotReadable)
                },
                { c.resume(Probe.Failed) },
            )
        }
        record(CapabilityKey.DOOR_POSITION, door)
        doorMins = (door as? Probe.Readable)?.value.orEmpty()

        record(CapabilityKey.TURN_SIGNAL, probe { ok, err -> a.turnSignal.checkTurnSignalStateCapabilities(ok, err) })
        record(CapabilityKey.BRAKE_PEDAL, probe { ok, err -> a.brake.checkBrakePedalStatusCapabilities(ok, err) })
        record(CapabilityKey.STEERING_ANGLE, suspendCancellableCoroutine<Probe<Unit>> { c ->
            a.steeringWheel.checkSteeringWheelAngleCapabilities(
                { cfg: CapabilityConfig, _: ai.pleos.playground.vehicle.api.model.capability.AreaBounds<Float> ->
                    c.resume(if (cfg.readable()) Probe.Readable(Unit) else Probe.NotReadable)
                },
                { c.resume(Probe.Failed) },
            )
        })
        record(CapabilityKey.POWER_DOWN_WARNING, probe { ok, err -> a.safety.checkPowerDownWarningIndicatorCapabilities(ok, err) })
        record(CapabilityKey.TELLTALES, probe { ok, err -> a.safety.checkTelltalesCapabilities(ok, err) })
        record(CapabilityKey.EV_SOC, probe { ok, err -> a.evBattery.checkEvBatteryStateOfChargeCapabilities(ok, err) })

        val fluidProbes = mapOf(
            FluidKind.ENGINE_OIL to probeList<OilLevelState> { ok, err -> a.safety.checkEngineOilLevelStateCapabilities(ok, err) },
            FluidKind.BRAKE_FLUID to probeList<OilLevelState> { ok, err -> a.safety.checkBrakeFluidLevelStateCapabilities(ok, err) },
            FluidKind.TRANSMISSION_OIL to probeList<OilLevelState> { ok, err -> a.safety.checkTransmissionOilLevelStateCapabilities(ok, err) },
        )
        fluids = fluidProbes.filterValues { it is Probe.Readable }.keys.toList()
        record(
            CapabilityKey.FLUID_LEVEL,
            when {
                fluids.isNotEmpty() -> Probe.Readable(Unit)
                fluidProbes.values.any { it == Probe.Failed } -> Probe.Failed
                else -> Probe.NotReadable
            },
        )

        record(CapabilityKey.LOW_BEAM, probeList<LightState> { ok, err -> a.light.checkLowBeamLightStateCapabilities(ok, err) })
        record(CapabilityKey.WIPER, suspendCancellableCoroutine<Probe<Unit>> { c ->
            a.wiper.checkWiperStateCapabilities(
                { cfg: CapabilityConfig, areas: Map<WindowArea, List<WiperState>> ->
                    val front = areas.keys.any { WindowValue.FRONT_WINDSHIELD in it.values }
                    c.resume(if (cfg.readable() && front) Probe.Readable(Unit) else Probe.NotReadable)
                },
                { c.resume(Probe.Failed) },
            )
        })

        // SDK에 비상등 값이 없다. 추정 방법을 실차로 확인하기 전까지 알 수 없음이다. TODO(VERIFY)
        result[CapabilityKey.HAZARD_STATE] = null
        result += auxCapabilities
        return CapabilityMap(result)
    }

    /** 지원되는 신호에 리스너를 등록하고 현재 값을 한 번 읽는다. 돌려준 함수를 부르면 모두 해제한다. */
    fun register(sink: (VehicleEvent) -> Unit): () -> Unit {
        val a = apis
        val undo = mutableListOf<() -> Unit>()
        fun emit(s: Signal) = sink(VehicleEvent(clock(), s))
        val ignore: (Exception) -> Unit = { }

        airbagSeats.forEach { v ->
            val area = PleosSeatArea(v)
            val seat = PleosMapping.seat(v)
            // SDK는 에어백 종류를 주지 않으므로 airbagKind는 항상 null이다.
            val l = object : SeatAirbagDeployedListener {
                override fun onSeatAirbagDeployUpdated(p0: Boolean?) { p0?.let { emit(Signal.SeatAirbagDeployed(seat, it, null)) } }
                override fun onFailed(p0: Exception) = Unit
            }
            a.safety.registerSeatAirbagDeployed(area, l)
            a.safety.hasSeatAirbagBeenDeployed(area, l::onSeatAirbagDeployUpdated, ignore)
            undo += { a.safety.unregisterSeatAirbagDeployed(area, l) }
        }
        occupancySeats.forEach { v ->
            val area = PleosSeatArea(v)
            val seat = PleosMapping.seat(v)
            val l = object : SeatOccupancyListener {
                override fun onSeatOccupancyUpdated(p0: Boolean?) { p0?.let { emit(Signal.SeatOccupancy(seat, it)) } }
                override fun onFailed(p0: Exception) = Unit
            }
            a.seat.registerSeatOccupancy(area, l)
            a.seat.getCurrentSeatOccupancy(area, l::onSeatOccupancyUpdated, ignore)
            undo += { a.seat.unregisterSeatOccupancy(area, l) }
        }
        beltSeats.forEach { v ->
            val area = PleosSeatArea(v)
            val seat = PleosMapping.seat(v)
            val l = object : SeatBeltBuckledStateListener {
                override fun onSeatBeltBuckledStateUpdated(p0: Boolean?) { p0?.let { emit(Signal.SeatBeltBuckled(seat, it)) } }
                override fun onFailed(p0: Exception) = Unit
            }
            a.seat.registerSeatBeltBuckledState(area, l)
            a.seat.getCurrentSeatBeltBuckledState(area, l::onSeatBeltBuckledStateUpdated, ignore)
            undo += { a.seat.unregisterSeatBeltBuckledState(area, l) }
        }
        tireWheels.forEach { v ->
            val area = PleosWheelArea(v)
            val wheel = PleosMapping.wheel(v) ?: return@forEach
            // 기준값을 못 읽으면 저압 여부를 판단하지 않는다. getLowTirePressure의 의미는 TODO(VERIFY)
            a.tire.getLowTirePressure(area, read@{ threshold ->
                if (threshold == null) return@read
                val l = object : TirePressureListener {
                    override fun onTirePressureUpdated(p0: Float?) { p0?.let { emit(Signal.LowTirePressure(wheel, it < threshold)) } }
                    override fun onFailed(p0: Exception) = Unit
                }
                a.tire.registerTirePressure(area, l)
                undo += { a.tire.unregisterTirePressure(area, l) }
            }, ignore)
        }
        doorMins.forEach { (v, min) ->
            val area = PleosDoorArea(v)
            val door = PleosMapping.door(v)
            val l = object : DoorPositionListener {
                override fun onPositionUpdated(p0: Int?) { p0?.let { emit(Signal.DoorPosition(door, it > min)) } }
                override fun onFailed(p0: Exception) = Unit
            }
            a.door.registerDoorPosition(area, l)
            a.door.getCurrentDoorPosition(area, l::onPositionUpdated, ignore)
            undo += { a.door.unregisterDoorPosition(area, l) }
        }
        if (CapabilityKey.TURN_SIGNAL in on) {
            val l = object : TurnSignalStateListener {
                override fun onTurnSignalStateUpdated(p0: TurnSignalValue?) { p0?.let { emit(Signal.TurnSignalState(PleosMapping.turn(it))) } }
                override fun onFailed(p0: Exception) = Unit
            }
            a.turnSignal.registerTurnSignalState(l)
            a.turnSignal.getCurrentTurnSignalState(l::onTurnSignalStateUpdated, ignore)
            undo += { a.turnSignal.unregisterTurnSignalState(l) }
        }
        if (CapabilityKey.BRAKE_PEDAL in on) {
            val l = object : BrakePedalStatusListener {
                override fun onBrakePedalStatusUpdated(p0: Boolean?) { p0?.let { emit(Signal.BrakeState(it, null)) } }
                override fun onFailed(p0: Exception) = Unit
            }
            a.brake.registerBrakePedalStatus(l)
            a.brake.getCurrentBrakePedalStatus(l::onBrakePedalStatusUpdated, ignore)
            undo += { a.brake.unregisterBrakePedalStatus(l) }
        }
        if (CapabilityKey.STEERING_ANGLE in on) {
            val l = object : SteeringWheelAngleListener {
                override fun onSteeringWheelAngleUpdated(p0: Float?) { p0?.let { emit(Signal.SteeringAngle(it)) } }
                override fun onFailed(p0: Exception) = Unit
            }
            a.steeringWheel.registerSteeringWheelAngle(l)
            a.steeringWheel.getCurrentSteeringWheelAngle(l::onSteeringWheelAngleUpdated, ignore)
            undo += { a.steeringWheel.unregisterSteeringWheelAngle(l) }
        }
        fluids.forEach { kind ->
            val l = object : OilLevelStateListener {
                override fun onOilLevelUpdated(p0: OilLevelState?) { p0?.let { emit(Signal.FluidLevel(kind, PleosMapping.level(it))) } }
                override fun onFailed(p0: Exception) = Unit
            }
            when (kind) {
                FluidKind.ENGINE_OIL -> {
                    a.safety.registerEngineOilLevelState(l)
                    a.safety.getEngineOilLevelState(l::onOilLevelUpdated, ignore)
                    undo += { a.safety.unregisterEngineOilLevelState(l) }
                }
                FluidKind.BRAKE_FLUID -> {
                    a.safety.registerBrakeFluidLevelState(l)
                    a.safety.getBrakeFluidLevelState(l::onOilLevelUpdated, ignore)
                    undo += { a.safety.unregisterBrakeFluidLevelState(l) }
                }
                FluidKind.TRANSMISSION_OIL -> {
                    a.safety.registerTransmissionOilLevelState(l)
                    a.safety.getTransmissionOilLevelState(l::onOilLevelUpdated, ignore)
                    undo += { a.safety.unregisterTransmissionOilLevelState(l) }
                }
            }
        }
        if (CapabilityKey.POWER_DOWN_WARNING in on) {
            val l = object : PowerDownWarningIndicatorListener {
                override fun onPowerDownWarningIndicatorUpdated(p0: Boolean?) { p0?.let { emit(Signal.PowerDownWarning(it)) } }
                override fun onFailed(p0: Exception) = Unit
            }
            a.safety.registerPowerDownWarningIndicator(l)
            a.safety.hasPowerDownWarningIndicator(l::onPowerDownWarningIndicatorUpdated, ignore)
            undo += { a.safety.unregisterPowerDownWarningIndicator(l) }
        }
        if (CapabilityKey.TELLTALES in on) {
            val l = object : TelltaleInfoListener {
                override fun onTelltaleInfoUpdated(p0: Telltales?) {
                    p0?.telltales?.forEach { emit(Signal.Telltale(it.type.name, it.state.name, it.color.toString())) }
                }
                override fun onFailed(p0: Exception) = Unit
            }
            a.safety.registerTelltales(l)
            a.safety.getTelltalesInfo(l::onTelltaleInfoUpdated, ignore)
            undo += { a.safety.unregisterTelltales(l) }
        }
        if (CapabilityKey.EV_SOC in on) {
            val l = object : EvBatteryStateOfChargeListener {
                override fun onEvBatteryStateOfChargeUpdated(p0: Int?) { p0?.let { emit(Signal.EvStateOfCharge(it.toFloat())) } }
                override fun onFailed(p0: Exception) = Unit
            }
            a.evBattery.registerEvBatteryStateOfCharge(l)
            a.evBattery.getEvBatteryStateOfCharge(l::onEvBatteryStateOfChargeUpdated, ignore)
            undo += { a.evBattery.unregisterEvBatteryStateOfCharge(l) }
        }
        if (CapabilityKey.LOW_BEAM in on) {
            val l = object : LightStateListener {
                override fun onLightStateUpdated(p0: LightState?) { p0?.let { emit(Signal.LowBeamLight(it == LightState.ON)) } }
                override fun onFailed(p0: Exception) = Unit
            }
            a.light.registerLowBeamLightState(l)
            a.light.getCurrentLowBeamLightState(l::onLightStateUpdated, ignore)
            undo += { a.light.unregisterLowBeamLightState(l) }
        }
        if (CapabilityKey.WIPER in on) {
            val area = WindowArea(WindowValue.FRONT_WINDSHIELD)
            val l = object : WiperStateListener {
                // OTHER·SERVICE는 작동 중인지 알 수 없어 이벤트를 만들지 않는다.
                override fun onWiperStateUpdated(p0: WiperState?) {
                    if (p0 == WiperState.ON || p0 == WiperState.OFF) emit(Signal.WiperActive(p0 == WiperState.ON))
                }
                override fun onFailed(p0: Exception) = Unit
            }
            a.wiper.registerWiperState(area, l)
            a.wiper.getCurrentWiperState(area, l::onWiperStateUpdated, ignore)
            undo += { a.wiper.unregisterWiperState(area, l) }
        }
        return { undo.forEach { it() } }
    }

    override fun events(): Flow<VehicleEvent> = callbackFlow {
        val unregister = register { trySend(it) }
        awaitClose(unregister)
    }

    override fun nowMs(): Long = clock()
}
