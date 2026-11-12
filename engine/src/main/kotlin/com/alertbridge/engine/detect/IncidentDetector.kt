package com.alertbridge.engine.detect

import com.alertbridge.engine.model.CapabilityKey
import com.alertbridge.engine.model.CapabilityMap
import com.alertbridge.engine.model.RoadType
import com.alertbridge.engine.model.Signal
import com.alertbridge.engine.model.TriggerConfidence
import com.alertbridge.engine.model.TriggerId
import com.alertbridge.engine.model.VehicleEvent
import com.alertbridge.engine.policy.PolicyTable

data class DetectionHit(
    val trigger: TriggerId,
    val tMs: Long,
    val suspicionMs: Long,
    /** 정책 기본값 대신 쓸 신뢰도. 근거 신호 일부를 읽지 못했을 때 낮춘다. */
    val confidenceOverride: TriggerConfidence? = null,
)

/** 트리거 규칙. 수동 신고(T_MANUAL)는 UI 이벤트이므로 엔진이 직접 처리한다. */
class IncidentDetector(private val policy: PolicyTable, private val state: VehicleState) {
    private val t = policy.thresholds
    var capabilities: CapabilityMap = CapabilityMap()

    private data class Sample(val tMs: Long, val value: Float)
    private val speeds = ArrayDeque<Sample>()
    private val brakes = ArrayDeque<Pair<Long, Boolean>>()

    private var armedAtMs: Long? = null
    private var armedSuspicionMs: Long = 0
    private var hardStopFired = false
    private var stopHazardFired = false
    private var expresswayFired = false
    private var adasStopFired = false
    private var stopDoorFired = false
    private var lastImpactHitMs: Long? = null
    /** 감속 정차의 시작 시각 (softStopDropKmh 이상 감속이 시작된 때) */
    private var softDropMs: Long? = null

    /** 최근 급감속이 시작된 시각. 수동 신고의 충돌 의심 시점으로 쓴다. */
    var lastDropSuspicionMs: Long? = null
        private set

    fun onEvent(e: VehicleEvent, newWarning: WarningEpisode?): List<DetectionHit> {
        val hits = mutableListOf<DetectionHit>()
        val s = e.signal
        if (s is Signal.SeatAirbagDeployed && s.deployed && state.firstAirbagMs == e.tMs) {
            hits += DetectionHit(TriggerId.T_AIRBAG, e.tMs, e.tMs)
        }
        if (s is Signal.BrakeState) {
            brakes.addLast(e.tMs to s.pedalPressed)
            prune(e.tMs)
        }
        if (s is Signal.VehicleSpeed) hardStop(e.tMs, s.kmh)?.let { hits += it }
        if (s is Signal.ImpactAccel) impact(e.tMs, s)?.let { hits += it }
        stopHazard(e.tMs)?.let { hits += it }
        expressway(e.tMs)?.let { hits += it }
        adasStop(e.tMs)?.let { hits += it }
        stopDoor(e.tMs)?.let { hits += it }
        if (newWarning != null) hits += DetectionHit(TriggerId.T_VEHICLE_WARNING, e.tMs, e.tMs)
        return hits
    }

    /** 사건이 "사고 아님"으로 끝난 뒤, 같은 정차로 다시 묻지 않는다. 다시 달리면 풀린다. */
    fun suppressUntilMoving() {
        armedAtMs = null
        hardStopFired = true
        stopHazardFired = true
        expresswayFired = true
        adasStopFired = true
        stopDoorFired = true
        lastImpactHitMs = Long.MAX_VALUE / 2
    }

    private fun prune(now: Long) {
        val keepFrom = now - t.hardStopWindowMs - t.hardStopToZeroMs
        while (speeds.size > 1 && speeds[1].tMs < keepFrom) speeds.removeFirst()
        while (brakes.size > 1 && brakes[1].first < keepFrom) brakes.removeFirst()
    }

    private fun hardStop(now: Long, kmh: Float): DetectionHit? {
        if (kmh >= t.movingSpeedKmh && armedAtMs == null) {
            hardStopFired = false
            stopHazardFired = false
            expresswayFired = false
            adasStopFired = false
            stopDoorFired = false
            if (lastImpactHitMs == Long.MAX_VALUE / 2) lastImpactHitMs = null
        }
        val windowStart = now - t.hardStopWindowMs
        // 구간 시작 직전 샘플도 값이 유지된 것으로 본다 (sample-and-hold).
        val inWindow = speeds.filterIndexed { i, sample ->
            sample.tMs >= windowStart || speeds.getOrNull(i + 1)?.let { it.tMs > windowStart } != false
        }
        speeds.addLast(Sample(now, kmh))
        prune(now)

        val peak = inWindow.maxByOrNull { it.value }
        // 약한 감속(T_STOP_DOOR용)은 별도 창으로 본다.
        val softStart = now - t.softStopWindowMs
        val softPeak = speeds.filter { it.tMs >= softStart }.maxOfOrNull { it.value }
        if (softPeak != null && softPeak - kmh >= t.softStopDropKmh && softDropMs == null) softDropMs = now
        if (kmh >= t.movingSpeedKmh && (softPeak == null || softPeak - kmh < t.softStopDropKmh)) softDropMs = null
        if (peak != null && peak.value - kmh >= t.hardStopDropKmh) {
            val last = inWindow.last { it.value == peak.value }
            val suspicion = maxOf(last.tMs, windowStart)
            lastDropSuspicionMs = suspicion
            val brakeKnown = capabilities.isSupported(CapabilityKey.BRAKE_PEDAL)
            // 브레이크를 읽을 수 없으면 "밟지 않음"이 아니라 "모름"이다. 조건을 건너뛰되 신뢰도를 낮춘다.
            if (armedAtMs == null && !hardStopFired && (!brakeKnown || brakeOnSince(windowStart))) {
                armedAtMs = now
                armedSuspicionMs = suspicion
            }
        }

        val armed = armedAtMs ?: return null
        if (now - armed > t.hardStopToZeroMs) {
            armedAtMs = null
            return null
        }
        if (kmh <= t.stopSpeedKmh) {
            armedAtMs = null
            hardStopFired = true
            val confidence = if (capabilities.isSupported(CapabilityKey.BRAKE_PEDAL)) null else TriggerConfidence.MEDIUM
            return DetectionHit(TriggerId.T_HARD_STOP, now, armedSuspicionMs, confidence)
        }
        return null
    }

    private fun brakeOnSince(fromMs: Long): Boolean {
        val heldAtStart = brakes.lastOrNull { it.first < fromMs }?.second == true
        return heldAtStart || brakes.any { it.first >= fromMs && it.second }
    }

    private fun stopHazard(now: Long): DetectionHit? {
        if (stopHazardFired) return null
        val stoppedAt = state.stoppedSinceMs ?: return null
        state.hazardOnMs ?: return null
        if (now - stoppedAt > t.stopHazardWindowMs) return null
        stopHazardFired = true
        return DetectionHit(TriggerId.T_STOP_HAZARD, now, stoppedAt)
    }

    private fun expressway(now: Long): DetectionHit? {
        if (expresswayFired) return null
        val stoppedAt = state.stoppedSinceMs ?: return null
        val loc = state.location ?: return null
        if (loc.roadType !in policy.expresswayRoadTypes || loc.roadType == RoadType.UNKNOWN) return null
        if (loc.roadTypeConfidence < t.roadTypeMinConfidence) return null
        if (state.openDoors.isEmpty() && state.hazardOnMs == null) return null
        expresswayFired = true
        return DetectionHit(TriggerId.T_EXPRESSWAY_STOP, now, stoppedAt)
    }

    /**
     * 가속도 센서 충격. 정차 중 추돌이나 저속 접촉처럼 브레이크와 무관한 사고를 잡는다.
     * 약한 충격은 신뢰도를 낮춰 "사고가 났나요?"로 묻기만 한다.
     */
    private fun impact(now: Long, s: Signal.ImpactAccel): DetectionHit? {
        if (!capabilities.isSupported(CapabilityKey.IMPACT_SENSOR)) return null
        if (s.peakG < t.impactMinG) return null
        val last = lastImpactHitMs
        if (last != null && now - last < t.impactCooldownMs) return null
        lastImpactHitMs = now
        lastDropSuspicionMs = now
        val confidence = if (s.peakG >= t.impactHighG) null else TriggerConfidence.MEDIUM
        return DetectionHit(TriggerId.T_IMPACT, now, now, confidence)
    }

    /** 전방 충돌 경고·긴급제동·후측방 경고가 켜진 뒤 [Thresholds.adasStopWindowMs] 안에 정차. 긴급제동이면 HIGH */
    private fun adasStop(now: Long): DetectionHit? {
        if (adasStopFired) return null
        if (!capabilities.isSupported(CapabilityKey.ADAS)) return null
        val stoppedAt = state.stoppedSinceMs ?: return null
        val relevant = state.adasOnMs.filterKeys { it != com.alertbridge.engine.model.AdasKind.LANE_DEPARTURE }
            .filterValues { it <= stoppedAt && stoppedAt - it <= t.adasStopWindowMs }
        if (relevant.isEmpty()) return null
        adasStopFired = true
        val aeb = com.alertbridge.engine.model.AdasKind.EMERGENCY_BRAKING in relevant
        return DetectionHit(TriggerId.T_ADAS_STOP, now, relevant.values.min(), if (aeb) null else TriggerConfidence.MEDIUM)
    }

    /** 감속 정차 직후 문이 열림. 주차장에서는 쓰지 않는다. 가장 약한 근거라 LOW로 묻기만 한다. */
    private fun stopDoor(now: Long): DetectionHit? {
        if (stopDoorFired) return null
        val stoppedAt = state.stoppedSinceMs ?: return null
        val drop = softDropMs ?: return null
        val doorAt = state.lastDoorOpenMs ?: return null
        if (doorAt < stoppedAt || doorAt - stoppedAt > t.stopDoorWindowMs) return null
        if (state.location?.roadType == RoadType.PARKING) return null
        stopDoorFired = true
        return DetectionHit(TriggerId.T_STOP_DOOR, now, drop)
    }
}
