package com.alertbridge.engine.damage

import com.alertbridge.engine.detect.WarningKind
import com.alertbridge.engine.model.AirbagKind
import com.alertbridge.engine.model.Answer
import com.alertbridge.engine.model.CapabilityKey
import com.alertbridge.engine.model.Confidence
import com.alertbridge.engine.model.DamageProfile
import com.alertbridge.engine.model.EvidenceRef
import com.alertbridge.engine.model.OccupantState
import com.alertbridge.engine.model.ReasonCode
import com.alertbridge.engine.model.SeatArea
import com.alertbridge.engine.model.Severity
import com.alertbridge.engine.model.Side
import com.alertbridge.engine.model.Signal
import com.alertbridge.engine.model.Tri
import com.alertbridge.engine.model.TriageQ
import com.alertbridge.engine.model.TurnSignal
import com.alertbridge.engine.model.WheelArea
import com.alertbridge.engine.model.Zone
import com.alertbridge.engine.model.ZoneEstimate
import com.alertbridge.engine.risk.IncidentContext

object DamageEstimator {
    fun estimate(c: IncidentContext): DamageProfile {
        val reasons = mutableListOf<ReasonCode>()
        val evidence = mutableListOf<EvidenceRef>()
        val zones = if (c.isFault) emptyList() else zones(c, evidence)

        val airbagDeployed = c.state.airbag.values.any { it }
        val functional = c.newWarnings.filter { it.kind != WarningKind.TELLTALE }
        val deltaV = if (c.isFault) null else deltaV(c)
        val deltaVHigh = deltaV != null && deltaV >= c.policy.thresholds.deltaVS2Kmh

        if (airbagDeployed) reasons += ReasonCode.AIRBAG_DEPLOYED
        functional.forEach {
            reasons += when (it.kind) {
                WarningKind.TIRE -> ReasonCode.TIRE_LOW_NEW
                WarningKind.POWER_DOWN -> ReasonCode.POWER_DOWN_NEW
                WarningKind.FLUID -> ReasonCode.FLUID_LOW_NEW
                WarningKind.TELLTALE -> ReasonCode.VEHICLE_WARNING_NEW
            }
            evidence += EvidenceRef(it.onMs, it.kind.name, it.detail)
        }
        if (deltaVHigh) reasons += ReasonCode.DELTA_V_HIGH
        val impactStrong = !c.isFault && (c.state.lastImpact?.peakG ?: 0f) >= c.policy.thresholds.impactS2G &&
            c.capabilities.isSupported(CapabilityKey.IMPACT_SENSOR)
        if (impactStrong) reasons += ReasonCode.IMPACT_STRONG

        val severity = when {
            c.isFault -> Severity.S0
            airbagDeployed -> Severity.S3
            functional.isNotEmpty() || deltaVHigh || impactStrong -> Severity.S2
            c.isAccident -> Severity.S1
            else -> Severity.S0
        }

        val occupants = occupants(c)
        val isEv = c.isEv
        val powerDownNew = functional.any { it.kind == WarningKind.POWER_DOWN }

        return DamageProfile(
            impactZones = zones,
            severity = severity,
            drivable = drivable(c, severity, functional.isNotEmpty()),
            occupants = occupants,
            evRisk = isEv && !c.isFault && (powerDownNew || zones.any { it.zone == Zone.UNDERBODY }),
            deltaVKmh = deltaV,
            evidence = evidence.distinct().sortedBy { it.tMs },
            airbagDeployed = airbagTri(c),
            needsPhotoCheck = !c.isFault && zones.isEmpty(),
            because = (reasons + zones.flatMap { it.because }).distinct(),
        )
    }

    private fun zones(c: IncidentContext, evidence: MutableList<EvidenceRef>): List<ZoneEstimate> {
        val found = mutableListOf<ZoneEstimate>()

        c.state.airbag.filterValues { it }.keys.forEach { seat ->
            val kind = c.state.airbagKinds[seat]
            evidence += EvidenceRef(c.state.firstAirbagMs ?: c.nowMs, "SeatAirbagDeployed", seat.name)
            airbagZone(seat, kind)?.let { found += it }
        }

        c.newWarnings.filter { it.kind == WarningKind.TIRE }.forEach { w ->
            found += ZoneEstimate(wheelZone(WheelArea.valueOf(w.detail)), Confidence.MEDIUM, listOf(ReasonCode.TIRE_LOW_NEW))
        }

        impactZone(c)?.let { z ->
            found += z
            evidence += EvidenceRef(c.state.lastImpactMs ?: c.nowMs, "ImpactAccel", z.zone.name)
        }

        preImpactSteer(c)?.let { (zone, reason) ->
            found += ZoneEstimate(zone, Confidence.LOW, listOf(reason))
            evidence += EvidenceRef(c.candidate.suspicionMs, "TurnSignalState+SteeringAngle", zone.name)
        }

        if (c.isEv && c.newWarnings.any { it.kind == WarningKind.POWER_DOWN }) {
            found += ZoneEstimate(Zone.UNDERBODY, Confidence.LOW, listOf(ReasonCode.POWER_DOWN_NEW))
        }

        // 같은 위치는 가장 높은 신뢰도 하나로 합친다.
        return found.groupBy { it.zone }.map { (zone, list) ->
            ZoneEstimate(zone, list.minOf { it.confidence }, list.flatMap { it.because }.distinct())
        }
    }

    private fun airbagZone(seat: SeatArea, kind: AirbagKind?): ZoneEstimate? = when (kind) {
        AirbagKind.FRONT -> ZoneEstimate(Zone.FRONT, Confidence.HIGH, listOf(ReasonCode.AIRBAG_FRONT))
        AirbagKind.SIDE, AirbagKind.CURTAIN -> when (seat.side) {
            Side.LEFT -> ZoneEstimate(Zone.LEFT, Confidence.HIGH, listOf(ReasonCode.AIRBAG_SIDE))
            Side.RIGHT -> ZoneEstimate(Zone.RIGHT, Confidence.HIGH, listOf(ReasonCode.AIRBAG_SIDE))
            Side.CENTER -> null
        }
        null -> if (seat == SeatArea.ROW1_LEFT || seat == SeatArea.ROW1_RIGHT) {
            ZoneEstimate(Zone.FRONT, Confidence.MEDIUM, listOf(ReasonCode.AIRBAG_UNKNOWN_KIND))
        } else null
    }

    /**
     * 충격 방향으로 부딪힌 곳을 추정한다. 차가 밀린 반대쪽이 부딪힌 곳이다.
     * 한 축이 우세하면 앞·뒤·왼쪽·오른쪽, 비슷하면 모서리. 강한 충격은 HIGH, 아니면 MEDIUM.
     */
    private fun impactZone(c: IncidentContext): ZoneEstimate? {
        if (!c.capabilities.isSupported(CapabilityKey.IMPACT_SENSOR)) return null
        val t = c.policy.thresholds
        val imp = c.state.lastImpact ?: return null
        val at = c.state.lastImpactMs ?: return null
        if (imp.peakG < t.impactMinG || at < c.candidate.suspicionMs - t.preImpactWindowMs) return null
        val lon = imp.longitudinalG ?: return null
        val lat = imp.lateralG ?: return null
        val absLon = kotlin.math.abs(lon)
        val absLat = kotlin.math.abs(lat)
        // 앞으로 밀렸으면(lon>0) 뒤에서 받힘. 오른쪽으로 밀렸으면(lat>0) 왼쪽에서 받힘.
        val longZone = if (lon > 0) Zone.REAR else Zone.FRONT
        val latZone = if (lat > 0) Zone.LEFT else Zone.RIGHT
        val zone = when {
            absLon >= absLat * t.impactAxisRatio -> longZone
            absLat >= absLon * t.impactAxisRatio -> latZone
            longZone == Zone.FRONT && latZone == Zone.LEFT -> Zone.FRONT_LEFT
            longZone == Zone.FRONT -> Zone.FRONT_RIGHT
            latZone == Zone.LEFT -> Zone.REAR_LEFT
            else -> Zone.REAR_RIGHT
        }
        val conf = if (imp.peakG >= t.impactHighG) Confidence.HIGH else Confidence.MEDIUM
        return ZoneEstimate(zone, conf, listOf(ReasonCode.IMPACT_DIRECTION))
    }

    private fun wheelZone(w: WheelArea) = when (w) {
        WheelArea.LEFT_FRONT -> Zone.FRONT_LEFT
        WheelArea.RIGHT_FRONT -> Zone.FRONT_RIGHT
        WheelArea.LEFT_REAR -> Zone.REAR_LEFT
        WheelArea.RIGHT_REAR -> Zone.REAR_RIGHT
    }

    /** 충돌 전 5초 안에 같은 방향 깜빡이와 조향이 함께 있었는가. */
    private fun preImpactSteer(c: IncidentContext): Pair<Zone, ReasonCode>? {
        val t = c.policy.thresholds
        val end = c.candidate.suspicionMs
        val start = end - t.preImpactWindowMs

        val turns = valuesInWindow(c, start, end) { (it as? Signal.TurnSignalState)?.state }
        val angles = valuesInWindow(c, start, end) { (it as? Signal.SteeringAngle)?.degrees }
        val sign = if (t.steeringRightPositive) 1f else -1f
        val steerRight = angles.any { it * sign >= t.steeringMinDeg }
        val steerLeft = angles.any { it * sign <= -t.steeringMinDeg }

        return when {
            TurnSignal.RIGHT in turns && steerRight -> Zone.RIGHT to ReasonCode.TURN_AND_STEER_RIGHT
            TurnSignal.LEFT in turns && steerLeft -> Zone.LEFT to ReasonCode.TURN_AND_STEER_LEFT
            else -> null
        }
    }

    /** 구간 안의 값과, 구간 시작 시점에 유지되던 직전 값을 돌려준다. */
    private fun <T : Any> valuesInWindow(c: IncidentContext, start: Long, end: Long, pick: (Signal) -> T?): List<T> {
        val series = c.events.mapNotNull { e -> pick(e.signal)?.let { e.tMs to it } }
        val held = series.lastOrNull { it.first < start }?.second
        return listOfNotNull(held) + series.filter { it.first in start..end }.map { it.second }
    }

    /** 충돌 의심 시점 전후 평균 속도 차. 값이 유지된다고 보고 시간 가중 평균을 낸다. */
    private fun deltaV(c: IncidentContext): Float? {
        val w = c.policy.thresholds.deltaVWindowMs
        val s = c.candidate.suspicionMs
        val samples = c.events.mapNotNull { e -> (e.signal as? Signal.VehicleSpeed)?.let { e.tMs to it.kmh } }
        if (samples.isEmpty() || c.nowMs <= s) return null
        val before = heldAverage(samples, s - w, s) ?: return null
        val after = heldAverage(samples, s, minOf(s + w, c.nowMs)) ?: return null
        return before - after
    }

    private fun heldAverage(samples: List<Pair<Long, Float>>, from: Long, to: Long): Float? {
        if (to <= from) return null
        var cursor = from
        var value = samples.lastOrNull { it.first <= from }?.second
        var weighted = 0.0
        var covered = 0L
        for ((t, v) in samples.filter { it.first > from && it.first <= to }) {
            value?.let { weighted += it * (t - cursor); covered += t - cursor }
            cursor = t
            value = v
        }
        value?.let { weighted += it * (to - cursor); covered += to - cursor }
        return if (covered == 0L) null else (weighted / covered).toFloat()
    }

    /** 신규 기능 경고는 고장 흐름(S0)에서도 주행 불가로 본다. 사고였다면 S2에 해당하는 경고이기 때문이다. */
    private fun drivable(c: IncidentContext, s: Severity, hasFunctionalWarning: Boolean): Tri {
        if (c.said(TriageQ.DRIVABLE, Answer.NO)) return Tri.NO
        if (s >= Severity.S2 || hasFunctionalWarning) return Tri.NO
        if (s == Severity.S1 && c.said(TriageQ.DRIVABLE, Answer.YES)) return Tri.YES
        return Tri.UNKNOWN
    }

    private fun occupants(c: IncidentContext): List<OccupantState> {
        val st = c.state
        val seats = (st.occupied.keys + st.buckled.keys + st.airbag.keys).toMutableSet()
        // 신호가 전혀 없어도 운전석은 "알 수 없음"으로 보여 준다.
        seats += SeatArea.ROW1_LEFT
        return seats.sortedBy { it.ordinal }.map { OccupantState(it, st.occupied[it], st.buckled[it], st.airbag[it]) }
    }

    private fun airbagTri(c: IncidentContext): Tri {
        if (!c.capabilities.isSupported(CapabilityKey.SEAT_AIRBAG_DEPLOYED)) return Tri.UNKNOWN
        val values = c.state.airbag
        if (values.values.any { it }) return Tri.YES
        // 앞좌석 값을 읽었을 때만 "전개 안 됨"이라고 말할 수 있다.
        val frontRead = values.containsKey(SeatArea.ROW1_LEFT) && values.containsKey(SeatArea.ROW1_RIGHT)
        return if (frontRead) Tri.NO else Tri.UNKNOWN
    }
}
