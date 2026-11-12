package com.alertbridge.engine.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 엔진이 받는 시계열 이벤트.
 *
 * [tMs]는 앱 수신 시각(epoch ms)이다. 차량 제어기끼리 동기화되어 있다는 보장이 없으므로
 * 수십~수백 ms 수준의 순서 오차가 있을 수 있다.
 */
@Serializable
data class VehicleEvent(val tMs: Long, val signal: Signal)

/** Pleos 상수 → 이 enum 변환은 어댑터 책임이다. 변환표: docs/pleos-api-mapping.md */
@Serializable
enum class SeatArea(val row: Int, val side: Side) {
    ROW1_LEFT(1, Side.LEFT), ROW1_CENTER(1, Side.CENTER), ROW1_RIGHT(1, Side.RIGHT),
    ROW2_LEFT(2, Side.LEFT), ROW2_CENTER(2, Side.CENTER), ROW2_RIGHT(2, Side.RIGHT),
    ROW3_LEFT(3, Side.LEFT), ROW3_CENTER(3, Side.CENTER), ROW3_RIGHT(3, Side.RIGHT),
}

enum class Side { LEFT, CENTER, RIGHT }

@Serializable
enum class WheelArea { LEFT_FRONT, RIGHT_FRONT, LEFT_REAR, RIGHT_REAR }

@Serializable
enum class DoorArea { ROW1_LEFT, ROW1_RIGHT, ROW2_LEFT, ROW2_RIGHT, ROW3_LEFT, ROW3_RIGHT, HOOD, REAR }

/** Pleos TurnSignalValue는 OFF/LEFT/RIGHT뿐이다. HAZARD는 어댑터가 추정해 넣을 수 있을 때만 쓴다. */
@Serializable
enum class TurnSignal { NONE, LEFT, RIGHT, HAZARD }

/** Pleos 에어백 API는 종류를 주지 않는다. 어댑터에서는 항상 null이며 값은 픽스처·후속 SDK용이다. */
@Serializable
enum class AirbagKind { FRONT, SIDE, CURTAIN }

@Serializable
enum class FluidKind { BRAKE_FLUID, ENGINE_OIL, TRANSMISSION_OIL }

/** Pleos OilLevelState + UNKNOWN. ERROR는 읽기 실패로 보고 경고로 쓰지 않는다. */
@Serializable
enum class LevelState { NORMAL, LOW, CRITICALLY_LOW, HIGH, ERROR, UNKNOWN }

/** 주행 보조(ADAS) 경고 종류. Pleos ADAS SDK의 FCW·AEB·BSW 상태를 어댑터가 변환한다. */
@Serializable
enum class AdasKind { FORWARD_COLLISION, EMERGENCY_BRAKING, BLIND_SPOT, LANE_DEPARTURE }

@Serializable
enum class RoadType { EXPRESSWAY_MAIN, EXPRESSWAY_SHOULDER, TUNNEL, URBAN, RURAL, PARKING, UNKNOWN }

@Serializable
sealed interface Signal {
    /** Safety.registerSeatAirbagDeployed / hasSeatAirbagBeenDeployed */
    @Serializable @SerialName("SeatAirbagDeployed")
    data class SeatAirbagDeployed(val seat: SeatArea, val deployed: Boolean, val airbagKind: AirbagKind? = null) : Signal

    /** Seat.registerSeatOccupancy / getCurrentSeatOccupancy */
    @Serializable @SerialName("SeatOccupancy")
    data class SeatOccupancy(val seat: SeatArea, val occupied: Boolean) : Signal

    /** Seat.registerSeatBeltBuckledState / getCurrentSeatBeltBuckledState */
    @Serializable @SerialName("SeatBeltBuckled")
    data class SeatBeltBuckled(val seat: SeatArea, val buckled: Boolean) : Signal

    /** Tire.getLowTirePressure(Float) + Tire.registerTirePressure를 어댑터가 비교한 결과 */
    @Serializable @SerialName("LowTirePressure")
    data class LowTirePressure(val wheel: WheelArea, val low: Boolean) : Signal

    /** Door.registerDoorPosition(Integer)를 어댑터가 open으로 변환 */
    @Serializable @SerialName("DoorPosition")
    data class DoorPosition(val door: DoorArea, val open: Boolean) : Signal

    /** TurnSignal.registerTurnSignalState / getCurrentTurnSignalState */
    @Serializable @SerialName("TurnSignalState")
    data class TurnSignalState(val state: TurnSignal) : Signal

    /** Brake.registerBrakePedalStatus. 주차 브레이크는 Brake API에 없어 Telltale로 채우거나 null */
    @Serializable @SerialName("BrakeState")
    data class BrakeState(val pedalPressed: Boolean, val parkingBrakeOn: Boolean? = null) : Signal

    /** SteeringWheel.registerSteeringWheelAngle. 부호는 정책값 steeringRightPositive */
    @Serializable @SerialName("SteeringAngle")
    data class SteeringAngle(val degrees: Float) : Signal

    /** android.car.permission.CAR_SPEED */
    @Serializable @SerialName("VehicleSpeed")
    data class VehicleSpeed(val kmh: Float) : Signal

    /** Safety.register{BrakeFluid,EngineOil,TransmissionOil}LevelState */
    @Serializable @SerialName("FluidLevel")
    data class FluidLevel(val kind: FluidKind, val state: LevelState) : Signal

    /** Safety.registerPowerDownWarningIndicator / hasPowerDownWarningIndicator */
    @Serializable @SerialName("PowerDownWarning")
    data class PowerDownWarning(val on: Boolean) : Signal

    /**
     * Safety.registerTelltales / getTelltalesInfo (SDK 2.3.2 이상). TelltaleData 한 건당 이벤트 하나.
     * JSON 구분자 `type`과 충돌하므로 필드 이름은 telltaleType이다.
     */
    @Serializable @SerialName("Telltale")
    data class Telltale(val telltaleType: String, val state: String, val color: String? = null) : Signal

    /** EvBattery.registerEvBatteryStateOfCharge (SDK 2.3.2 이상) */
    @Serializable @SerialName("EvStateOfCharge")
    data class EvStateOfCharge(val percent: Float) : Signal

    /** Light.registerLowBeamLightState. 야간 추정 보조 */
    @Serializable @SerialName("LowBeamLight")
    data class LowBeamLight(val on: Boolean) : Signal

    /** Wiper.registerWiperState. 우천 추정 보조 */
    @Serializable @SerialName("WiperActive")
    data class WiperActive(val active: Boolean) : Signal

    /**
     * 차량 화면 기기의 가속도 센서로 잰 순간 충격(중력 제외, 수평 성분). 단위 G.
     * [longitudinalG] 양수 = 차가 앞으로 밀림(뒤에서 받힘), 음수 = 뒤로 밀림(앞에서 부딪힘).
     * [lateralG] 양수 = 오른쪽으로 밀림(왼쪽에서 받힘), 음수 = 왼쪽으로 밀림(오른쪽에서 받힘).
     * 기기 장착 방향을 모르면 방향은 null이고 크기만 쓴다.
     */
    @Serializable @SerialName("ImpactAccel")
    data class ImpactAccel(val peakG: Float, val longitudinalG: Float? = null, val lateralG: Float? = null) : Signal

    /** 주행 보조 경고가 켜지거나 꺼짐. Pleos ADAS SDK (FORWARD_COLLISION_WARNING_STATE 등) */
    @Serializable @SerialName("AdasWarning")
    data class AdasWarning(val kind: AdasKind, val active: Boolean) : Signal

    /** FusedLocation + 지도 데이터. 판정이 불확실하면 UNKNOWN과 낮은 confidence를 넣는다. */
    @Serializable @SerialName("Location")
    data class Location(
        val lat: Double,
        val lng: Double,
        val accuracyM: Float? = null,
        val roadType: RoadType = RoadType.UNKNOWN,
        val roadTypeConfidence: Float = 0f,
        val roadName: String? = null,
        val direction: String? = null,
        val kmPost: Float? = null,
        val address: String? = null,
    ) : Signal
}

@Serializable
enum class CapabilityKey {
    SEAT_AIRBAG_DEPLOYED, SEAT_OCCUPANCY, SEAT_BELT_BUCKLED, LOW_TIRE_PRESSURE, DOOR_POSITION,
    TURN_SIGNAL, HAZARD_STATE, BRAKE_PEDAL, STEERING_ANGLE, VEHICLE_SPEED, FLUID_LEVEL,
    POWER_DOWN_WARNING, TELLTALES, EV_SOC, LOW_BEAM, WIPER, LOCATION,
    /** 차량 화면 기기의 가속도 센서 */
    IMPACT_SENSOR,
    /** 주행 보조 경고 (Pleos ADAS SDK) */
    ADAS,
}

/**
 * 신호 지원 여부. false = 미지원, null(또는 키 없음) = 알 수 없음.
 * 엔진은 true인 신호만 판단 근거로 쓴다. 미지원을 "없음"으로 해석하지 않는다.
 */
@Serializable
data class CapabilityMap(val supported: Map<CapabilityKey, Boolean?> = emptyMap()) {
    fun isSupported(key: CapabilityKey): Boolean = supported[key] == true

    companion object {
        val ALL_SUPPORTED = CapabilityMap(CapabilityKey.entries.associateWith { true })
    }
}

/** 이 신호를 믿으려면 어떤 capability가 true여야 하는가. */
fun Signal.requiredCapability(): CapabilityKey = when (this) {
    is Signal.ImpactAccel -> CapabilityKey.IMPACT_SENSOR
    is Signal.AdasWarning -> CapabilityKey.ADAS
    is Signal.SeatAirbagDeployed -> CapabilityKey.SEAT_AIRBAG_DEPLOYED
    is Signal.SeatOccupancy -> CapabilityKey.SEAT_OCCUPANCY
    is Signal.SeatBeltBuckled -> CapabilityKey.SEAT_BELT_BUCKLED
    is Signal.LowTirePressure -> CapabilityKey.LOW_TIRE_PRESSURE
    is Signal.DoorPosition -> CapabilityKey.DOOR_POSITION
    is Signal.TurnSignalState -> if (state == TurnSignal.HAZARD) CapabilityKey.HAZARD_STATE else CapabilityKey.TURN_SIGNAL
    is Signal.BrakeState -> CapabilityKey.BRAKE_PEDAL
    is Signal.SteeringAngle -> CapabilityKey.STEERING_ANGLE
    is Signal.VehicleSpeed -> CapabilityKey.VEHICLE_SPEED
    is Signal.FluidLevel -> CapabilityKey.FLUID_LEVEL
    is Signal.PowerDownWarning -> CapabilityKey.POWER_DOWN_WARNING
    is Signal.Telltale -> CapabilityKey.TELLTALES
    is Signal.EvStateOfCharge -> CapabilityKey.EV_SOC
    is Signal.LowBeamLight -> CapabilityKey.LOW_BEAM
    is Signal.WiperActive -> CapabilityKey.WIPER
    is Signal.Location -> CapabilityKey.LOCATION
}
