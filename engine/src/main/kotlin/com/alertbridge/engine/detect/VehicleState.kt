package com.alertbridge.engine.detect

import com.alertbridge.engine.model.DoorArea
import com.alertbridge.engine.model.AirbagKind
import com.alertbridge.engine.model.LevelState
import com.alertbridge.engine.model.SeatArea
import com.alertbridge.engine.model.Signal
import com.alertbridge.engine.model.TurnSignal
import com.alertbridge.engine.model.VehicleEvent
import com.alertbridge.engine.model.WheelArea
import com.alertbridge.engine.policy.Thresholds

/** 최근 [windowMs]의 이벤트만 메모리에 보관한다. 사고 확정 전에는 저장소에 쓰지 않는다. */
class RingBuffer(private val windowMs: Long) {
    private val items = ArrayDeque<VehicleEvent>()

    fun add(e: VehicleEvent) {
        items.addLast(e)
        while (items.isNotEmpty() && items.first().tMs < e.tMs - windowMs) items.removeFirst()
    }

    fun snapshot(): List<VehicleEvent> = items.toList()
}

enum class WarningKind { FLUID, TIRE, POWER_DOWN, TELLTALE }

/** 경고가 켜졌다 꺼질 때까지의 한 구간. 신규 여부는 [onMs]와 충돌 의심 시점을 비교해 정한다. */
data class WarningEpisode(
    val kind: WarningKind,
    val detail: String,
    val onMs: Long,
    val offMs: Long? = null,
    /** 스트림 시작 직후에 이미 켜져 있었다. 언제 켜졌는지 모르므로 트리거로 쓰지 않는다. */
    val baseline: Boolean = false,
)

/**
 * 신호별 최신 값과 경고 이력. 링 버퍼는 60초가 지나면 값을 잃지만, 좌석 점유나
 * 사고 전부터 켜진 경고는 그보다 오래되어도 알아야 하므로 따로 보관한다.
 */
class VehicleState(private val t: Thresholds, private val telltaleWarnings: Set<String>) {
    var firstEventMs: Long? = null; private set
    var speedKmh: Float? = null; private set
    /** 정차 시작 시각. 차속을 모르거나 움직이는 중이면 null */
    var stoppedSinceMs: Long? = null; private set
    var turnSignal: TurnSignal? = null; private set
    var hazardOnMs: Long? = null; private set
    var brakePressed: Boolean? = null; private set
    val occupied = mutableMapOf<SeatArea, Boolean>()
    val buckled = mutableMapOf<SeatArea, Boolean>()
    val airbag = mutableMapOf<SeatArea, Boolean>()
    val airbagKinds = mutableMapOf<SeatArea, AirbagKind?>()
    var firstAirbagMs: Long? = null; private set
    val openDoors = mutableSetOf<DoorArea>()
    var location: Signal.Location? = null; private set
    var evSoc: Float? = null; private set
    var lowBeamOn: Boolean? = null; private set
    var wiperActive: Boolean? = null; private set
    /** 가장 최근 충격과 그 시각 */
    var lastImpact: Signal.ImpactAccel? = null; private set
    var lastImpactMs: Long? = null; private set
    /** 주행 보조 경고가 켜진 시각 (종류별 마지막) */
    val adasOnMs = mutableMapOf<com.alertbridge.engine.model.AdasKind, Long>()
    /** 문이 마지막으로 열린 시각 */
    var lastDoorOpenMs: Long? = null; private set

    private val episodes = mutableListOf<WarningEpisode>()

    val isStopped: Boolean? get() = speedKmh?.let { it <= t.stopSpeedKmh }
    val isMoving: Boolean get() = (speedKmh ?: 0f) >= t.movingSpeedKmh

    /** 값을 반영하고 이번 이벤트로 새로 켜진 경고를 돌려준다. */
    fun apply(e: VehicleEvent): WarningEpisode? {
        if (firstEventMs == null) firstEventMs = e.tMs
        when (val s = e.signal) {
            is Signal.VehicleSpeed -> {
                speedKmh = s.kmh
                if (s.kmh <= t.stopSpeedKmh) { if (stoppedSinceMs == null) stoppedSinceMs = e.tMs } else stoppedSinceMs = null
            }
            is Signal.TurnSignalState -> {
                if (s.state == TurnSignal.HAZARD && turnSignal != TurnSignal.HAZARD) hazardOnMs = e.tMs
                if (s.state != TurnSignal.HAZARD) hazardOnMs = null
                turnSignal = s.state
            }
            is Signal.BrakeState -> brakePressed = s.pedalPressed
            is Signal.SeatOccupancy -> occupied[s.seat] = s.occupied
            is Signal.SeatBeltBuckled -> buckled[s.seat] = s.buckled
            is Signal.SeatAirbagDeployed -> {
                airbag[s.seat] = s.deployed
                if (s.deployed) {
                    airbagKinds[s.seat] = s.airbagKind
                    if (firstAirbagMs == null) firstAirbagMs = e.tMs
                }
            }
            is Signal.DoorPosition -> if (s.open) { openDoors += s.door; lastDoorOpenMs = e.tMs } else openDoors -= s.door
            is Signal.ImpactAccel -> { lastImpact = s; lastImpactMs = e.tMs }
            is Signal.AdasWarning -> if (s.active) adasOnMs[s.kind] = e.tMs
            is Signal.Location -> location = s
            is Signal.EvStateOfCharge -> evSoc = s.percent
            is Signal.LowBeamLight -> lowBeamOn = s.on
            is Signal.WiperActive -> wiperActive = s.active
            is Signal.FluidLevel ->
                return updateWarning(WarningKind.FLUID, s.kind.name, s.state == LevelState.LOW || s.state == LevelState.CRITICALLY_LOW, e.tMs)
            is Signal.LowTirePressure -> return updateWarning(WarningKind.TIRE, s.wheel.name, s.low, e.tMs)
            is Signal.PowerDownWarning -> return updateWarning(WarningKind.POWER_DOWN, "", s.on, e.tMs)
            is Signal.Telltale ->
                if (s.telltaleType in telltaleWarnings) {
                    return updateWarning(WarningKind.TELLTALE, s.telltaleType, s.state == "ON" || s.state == "BLINK", e.tMs)
                }
            is Signal.SteeringAngle -> Unit
        }
        return null
    }

    private fun updateWarning(kind: WarningKind, detail: String, on: Boolean, tMs: Long): WarningEpisode? {
        val idx = episodes.indexOfLast { it.kind == kind && it.detail == detail && it.offMs == null }
        if (on) {
            if (idx >= 0) return null
            val start = firstEventMs ?: tMs
            val ep = WarningEpisode(kind, detail, tMs, baseline = tMs - start < t.warningBaselineMs)
            episodes += ep
            return ep.takeUnless { it.baseline }
        }
        if (idx >= 0) episodes[idx] = episodes[idx].copy(offMs = tMs)
        return null
    }

    /** [sinceMs] 이후에 새로 켜진 경고. 그 전부터 켜져 있던 경고는 포함하지 않는다. */
    fun newWarningsSince(sinceMs: Long): List<WarningEpisode> = episodes.filter { !it.baseline && it.onMs >= sinceMs }

    fun tireWheels(episodes: List<WarningEpisode>): List<WheelArea> =
        episodes.filter { it.kind == WarningKind.TIRE }.map { WheelArea.valueOf(it.detail) }
}
