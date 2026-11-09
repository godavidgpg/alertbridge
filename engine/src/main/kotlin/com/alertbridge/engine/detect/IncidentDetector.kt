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
        stopHazard(e.tMs)?.let { hits += it }
        expressway(e.tMs)?.let { hits += it }
        if (newWarning != null) hits += DetectionHit(TriggerId.T_VEHICLE_WARNING, e.tMs, e.tMs)
        return hits
    }

    /** 사건이 "사고 아님"으로 끝난 뒤, 같은 정차로 다시 묻지 않는다. 다시 달리면 풀린다. */
    fun suppressUntilMoving() {
        armedAtMs = null
        hardStopFired = true
        stopHazardFired = true
        expresswayFired = true
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
        }
        val windowStart = now - t.hardStopWindowMs
        // 구간 시작 직전 샘플도 값이 유지된 것으로 본다 (sample-and-hold).
        val inWindow = speeds.filterIndexed { i, sample ->
            sample.tMs >= windowStart || speeds.getOrNull(i + 1)?.let { it.tMs > windowStart } != false
        }
        speeds.addLast(Sample(now, kmh))
        prune(now)

        val peak = inWindow.maxByOrNull { it.value }
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
}
