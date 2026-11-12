package com.alertbridge.engine.risk

import com.alertbridge.engine.detect.VehicleState
import com.alertbridge.engine.detect.WarningEpisode
import com.alertbridge.engine.detect.WarningKind
import com.alertbridge.engine.model.Answer
import com.alertbridge.engine.model.CapabilityKey
import com.alertbridge.engine.model.CapabilityMap
import com.alertbridge.engine.model.DamageProfile
import com.alertbridge.engine.model.GradeResult
import com.alertbridge.engine.model.IncidentCandidate
import com.alertbridge.engine.model.IncidentKind
import com.alertbridge.engine.model.OccupantProfile
import com.alertbridge.engine.model.Powertrain
import com.alertbridge.engine.model.ReasonCode
import com.alertbridge.engine.model.RiskAssessment
import com.alertbridge.engine.model.RiskGrade
import com.alertbridge.engine.model.RoadType
import com.alertbridge.engine.model.Severity
import com.alertbridge.engine.model.Tri
import com.alertbridge.engine.model.TriageQ
import com.alertbridge.engine.model.VehicleEvent
import com.alertbridge.engine.model.VehicleProfile
import com.alertbridge.engine.model.VehicleSizeClass
import com.alertbridge.engine.model.Zone
import com.alertbridge.engine.policy.PolicyTable
import kotlinx.serialization.Serializable
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** 정책 규칙의 조건으로 쓸 수 있는 사실. 계산은 [FactBuilder], 조합은 policy_v1.json이 정한다. */
@Serializable
enum class Fact(val description: String) {
    ACCIDENT("사고 확인됨"),
    FAULT("사고가 아닌 고장 흐름"),
    INJURY_YES("부상 있음 응답"),
    FIRE_SMOKE_YES("화재·연기 있음 응답"),
    CONFIRM_NO_RESPONSE("사고 확인 질문에 무응답"),
    SEVERITY_S3("심각도 S3"),
    SEVERITY_S2_PLUS("심각도 S2 이상"),
    OCCUPIED_UNBELTED("탑승했는데 벨트 미착용인 좌석 있음"),
    EV("전기차"),
    POWER_DOWN_NEW("출력 제한 경고 신규"),
    EXPRESSWAY("고속도로로 판단됨 (센서 또는 사용자 응답)"),
    EXPRESSWAY_STOPPED("센서상 고속도로·터널에 정차 (도로 신뢰도 충족)"),
    EXPRESSWAY_REPORTED_BY_USER("도로 판정이 불확실하나 사용자가 고속도로라고 응답"),
    NIGHT_OR_RAIN_ON_ROADWAY("야간·우천 추정 + 도로 위 정차"),
    OTHER_PARTY_FLED_YES("상대 도주 응답"),
    OTHER_PARTY_SUSPICIOUS_YES("상대 음주·다툼·위협 의심 응답"),
    PEDESTRIAN_OR_BICYCLE("보행자·자전거 관련 (수동 선택)"),
    VEHICLE_WARNING_NEW("차량 경고 신규"),
    DRIVABLE_NO("주행 불가"),
    TOW_NEEDED("견인 필요"),
    TOW_OVER_FREE_KM("목적지가 무료 견인 거리 초과"),
    ELIGIBLE_2504("2504 긴급견인 안내 조건 충족"),
    GUARDIAN_REGISTERED("보호자 등록됨"),
    GUARDIAN_AUTO("무응답 + 보호자 사전 동의"),
    HAS_OTHER_VEHICLE("상대 차량 있음"),
    MY_CAR_DAMAGED("내 차 손상"),
    EV_BATTERY_AT_RISK("전기차 배터리 손상 가능성 (하부 충격 또는 출력 제한 경고)"),
    EV_TOW_OR_LOW_SOC("전기차 견인 필요 또는 배터리 잔량 부족"),
    FAR_FROM_HOME("집에서 먼 곳"),
    GRADE_R0("R0 판정됨"),
    GRADE_R1("R1 판정됨"),
    GRADE_R2("R2 판정됨"),
    GRADE_R3("R3 판정됨"),
    GRADE_R4("R4 판정됨");

    companion object {
        fun ofGrade(g: RiskGrade): Fact = valueOf("GRADE_${g.name}")
    }
}

/** 파이프라인 각 단계가 공유하는 읽기 전용 입력. */
class IncidentContext(
    val policy: PolicyTable,
    val candidate: IncidentCandidate,
    /** 사건 생성 시점 링 버퍼 + 이후 이벤트 */
    val events: List<VehicleEvent>,
    val state: VehicleState,
    val answers: Map<TriageQ, Answer>,
    val capabilities: CapabilityMap,
    val driver: OccupantProfile?,
    val vehicle: VehicleProfile?,
    val vehicleCount: Int?,
    val pedestrianOrBicycle: Boolean?,
    val towDestinationKm: Float?,
    val nowMs: Long,
) {
    val isAccident get() = candidate.kind == IncidentKind.ACCIDENT
    val isFault get() = candidate.kind == IncidentKind.FAULT
    val newWarnings: List<WarningEpisode> get() = state.newWarningsSince(candidate.suspicionMs)
    val isEv get() = vehicle?.powertrain == Powertrain.EV || state.evSoc != null
    fun said(q: TriageQ, a: Answer) = answers[q] == a

    /** 도로 판정 신뢰도가 정책값 이상인 위치. 아니면 도로 종류를 판단에 쓰지 않는다. */
    val trustedRoad: RoadType?
        get() = state.location?.takeIf { it.roadTypeConfidence >= policy.thresholds.roadTypeMinConfidence && it.roadType != RoadType.UNKNOWN }?.roadType
}

object FactBuilder {
    fun build(c: IncidentContext, damage: DamageProfile): MutableSet<Fact> {
        val f = mutableSetOf<Fact>()
        val t = c.policy.thresholds
        fun set(fact: Fact, value: Boolean) { if (value) f += fact }

        set(Fact.ACCIDENT, c.isAccident)
        set(Fact.FAULT, c.isFault)
        set(Fact.INJURY_YES, c.said(TriageQ.INJURY, Answer.YES))
        set(Fact.FIRE_SMOKE_YES, c.said(TriageQ.FIRE_SMOKE, Answer.YES))
        set(Fact.CONFIRM_NO_RESPONSE, c.said(TriageQ.IS_ACCIDENT, Answer.NO_RESPONSE))
        set(Fact.SEVERITY_S3, damage.severity == Severity.S3)
        set(Fact.SEVERITY_S2_PLUS, damage.severity >= Severity.S2)
        set(Fact.OCCUPIED_UNBELTED, damage.occupants.any { it.occupied == true && it.buckled == false })
        set(Fact.EV, c.isEv)
        val powerDownNew = c.newWarnings.any { it.kind == WarningKind.POWER_DOWN }
        set(Fact.POWER_DOWN_NEW, powerDownNew)

        val road = c.trustedRoad
        val sensorExpressway = road != null && road in c.policy.expresswayRoadTypes
        val roadUncertain = road == null
        val userExpressway = roadUncertain && c.said(TriageQ.IS_EXPRESSWAY, Answer.YES)
        set(Fact.EXPRESSWAY, sensorExpressway || userExpressway)
        set(Fact.EXPRESSWAY_STOPPED, sensorExpressway && c.state.isStopped == true)
        // 사용자가 답하는 중이면 차속을 몰라도 정차로 본다. 움직인다고 확인될 때만 뺀다.
        set(Fact.EXPRESSWAY_REPORTED_BY_USER, userExpressway && c.state.isStopped != false)
        val nightOrRain = c.state.lowBeamOn == true || c.state.wiperActive == true
        val onRoadway = road != null && road != RoadType.PARKING
        set(Fact.NIGHT_OR_RAIN_ON_ROADWAY, nightOrRain && onRoadway && c.state.isStopped == true)

        set(Fact.OTHER_PARTY_FLED_YES, c.said(TriageQ.OTHER_PARTY_FLED, Answer.YES))
        set(Fact.OTHER_PARTY_SUSPICIOUS_YES, c.said(TriageQ.OTHER_PARTY_SUSPICIOUS, Answer.YES))
        set(Fact.PEDESTRIAN_OR_BICYCLE, c.pedestrianOrBicycle == true)
        set(Fact.VEHICLE_WARNING_NEW, c.newWarnings.isNotEmpty())

        val notDrivable = damage.drivable == Tri.NO
        set(Fact.DRIVABLE_NO, notDrivable)
        set(Fact.TOW_NEEDED, notDrivable)
        val freeKm = c.driver?.insurance?.freeTowKm
        set(Fact.TOW_OVER_FREE_KM, freeKm != null && c.towDestinationKm != null && c.towDestinationKm > freeKm)

        val size = c.vehicle?.sizeClass ?: VehicleSizeClass.UNKNOWN
        val sizeOk = size in c.policy.tow2504SizeClasses || (size == VehicleSizeClass.UNKNOWN && t.allow2504WhenSizeUnknown)
        val towRoad = road != null && road in c.policy.tow2504RoadTypes
        // 운전자가 고속도로라고 답했고 차를 움직일 수 없으면 1588-2504를 안내한다.
        // 전화로 대상 여부를 묻는 것이라 도로 판정이 불확실해도 안내하는 쪽이 낫다.
        set(Fact.ELIGIBLE_2504, sizeOk && ((towRoad && (notDrivable || Fact.EXPRESSWAY_STOPPED in f)) || (Fact.EXPRESSWAY_REPORTED_BY_USER in f && notDrivable)))

        val guardian = c.driver?.guardianPhone != null
        set(Fact.GUARDIAN_REGISTERED, guardian)
        set(Fact.GUARDIAN_AUTO, guardian && c.driver?.guardianAutoNotifyConsent == true && Fact.CONFIRM_NO_RESPONSE in f)

        set(Fact.HAS_OTHER_VEHICLE, (c.vehicleCount ?: 0) >= 2 || c.said(TriageQ.OTHER_PARTY_FLED, Answer.YES))
        set(Fact.MY_CAR_DAMAGED, c.isAccident)
        set(Fact.EV_BATTERY_AT_RISK, powerDownNew || damage.impactZones.any { it.zone == Zone.UNDERBODY })
        val lowSoc = c.state.evSoc?.let { it <= t.evLowSocPercent } == true
        set(Fact.EV_TOW_OR_LOW_SOC, notDrivable || lowSoc)
        set(Fact.FAR_FROM_HOME, farFromHome(c))
        return f
    }

    private fun farFromHome(c: IncidentContext): Boolean {
        val lat = c.driver?.homeLat ?: return false
        val lng = c.driver.homeLng ?: return false
        val loc = c.state.location ?: return false
        return haversineKm(lat, lng, loc.lat, loc.lng) >= c.policy.thresholds.farFromHomeKm
    }

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * 6371.0 * asin(sqrt(a))
    }
}

/** riskRules를 위에서부터 평가한다. 앞 등급의 결과(GRADE_*)를 뒤 규칙이 조건으로 쓸 수 있다. */
object RiskEvaluator {
    fun evaluate(policy: PolicyTable, facts: MutableSet<Fact>): RiskAssessment {
        val reasons = linkedMapOf<RiskGrade, MutableList<ReasonCode>>()
        for (grade in RiskGrade.entries) {
            for (rule in policy.riskRules.filter { it.grade == grade }) {
                val ok = facts.containsAll(rule.`when`) && rule.notAny.none { it in facts }
                if (ok) reasons.getOrPut(grade) { mutableListOf() } += rule.reason
            }
            if (grade in reasons) facts += Fact.ofGrade(grade)
        }
        return RiskAssessment(reasons.map { (g, r) -> GradeResult(g, r.distinct()) })
    }
}
