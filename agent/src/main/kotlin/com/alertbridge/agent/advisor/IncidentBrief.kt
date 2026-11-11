package com.alertbridge.agent.advisor

import com.alertbridge.engine.model.EngineResult
import com.alertbridge.engine.model.ReasonCode
import com.alertbridge.engine.model.RiskGrade
import com.alertbridge.engine.model.VehicleProfile
import kotlinx.serialization.Serializable

/**
 * AI에게 넘기는 사고 요약. 규칙 엔진이 이미 판단한 값만 담는다.
 * 원시 센서값이 아니라 판단 결과를 넘겨, AI가 센서 해석을 다시 하지 않게 한다.
 */
@Serializable
data class IncidentBrief(
    val trigger: String,
    val kind: String,
    val severity: String,
    val drivable: String,
    val impactZones: List<String>,
    val airbag: String,
    val evVehicle: Boolean,
    val evRisk: Boolean,
    val riskGrades: List<String>,
    val reasons: List<String>,
    val injuryReported: Boolean,
    val otherPartyFled: Boolean,
    val pedestrianOrBicycle: Boolean,
    val unbeltedOccupants: Int,
    val location: BriefLocation?,
    val rulePlanTop: List<String>,
    /** R0(인명 위험)면 AI 조치보다 119가 먼저다 */
    val lifeThreatening: Boolean,
)

@Serializable
data class BriefLocation(val lat: Double, val lng: Double, val address: String?, val roadName: String?, val roadType: String, val roadTypeConfidence: Float)

object IncidentBriefs {
    fun from(r: EngineResult, vehicle: VehicleProfile?): IncidentBrief {
        val reasons = r.risk.grades.flatMap { it.because } + r.damage.because
        val loc = r.session.caseCard?.location
        return IncidentBrief(
            trigger = r.candidate.trigger.name,
            kind = r.candidate.kind.name,
            severity = r.damage.severity.name,
            drivable = r.damage.drivable.name,
            impactZones = r.damage.impactZones.map { "${it.zone}(${it.confidence})" },
            airbag = r.damage.airbagDeployed.name,
            evVehicle = vehicle?.powertrain?.name == "EV",
            evRisk = r.damage.evRisk,
            riskGrades = r.risk.grades.map { it.grade.name },
            reasons = reasons.map { it.name }.distinct(),
            injuryReported = ReasonCode.INJURY_REPORTED in reasons,
            otherPartyFled = ReasonCode.OTHER_PARTY_FLED in reasons,
            pedestrianOrBicycle = ReasonCode.PEDESTRIAN_OR_BICYCLE in reasons,
            unbeltedOccupants = r.damage.occupants.count { it.occupied == true && it.buckled == false },
            location = loc?.let { BriefLocation(it.lat, it.lng, it.address, it.roadName, it.roadType.name, it.roadTypeConfidence) },
            rulePlanTop = r.plan.actions.take(4).map { it.id.name },
            lifeThreatening = RiskGrade.R0 in r.risk.has,
        )
    }
}
