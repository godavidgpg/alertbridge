package com.alertbridge.engine.model

import kotlinx.serialization.Serializable

/** 사용자에게 보일 문장. key로 번역·교체할 수 있고 text는 기본 한국어다. */
@Serializable
data class Message(
    val key: String,
    val text: String,
    /** TTS로 한 문장씩 읽을 때 쓴다. */
    val sentences: List<String> = listOf(text),
)

@Serializable enum class TriggerId { T_MANUAL, T_AIRBAG, T_HARD_STOP, T_STOP_HAZARD, T_EXPRESSWAY_STOP, T_VEHICLE_WARNING }
@Serializable enum class TriggerConfidence { CONFIRMED, HIGH, MEDIUM, LOW }
@Serializable enum class IncidentKind { ACCIDENT_SUSPECTED, ACCIDENT, FAULT }

@Serializable
data class IncidentCandidate(
    val trigger: TriggerId,
    val confidence: TriggerConfidence,
    val kind: IncidentKind,
    /** 트리거가 발생한 시각 */
    val detectedMs: Long,
    /** 충돌 의심 시점. "신규 경고"와 "충돌 전 5초"의 기준이다. */
    val suspicionMs: Long,
    val triggersSeen: List<TriggerId>,
)

@Serializable enum class Zone { FRONT, FRONT_LEFT, FRONT_RIGHT, LEFT, RIGHT, REAR, REAR_LEFT, REAR_RIGHT, UNDERBODY }
@Serializable enum class Confidence { HIGH, MEDIUM, LOW }
@Serializable enum class Severity { S0, S1, S2, S3 }
@Serializable enum class Tri { YES, NO, UNKNOWN }

/** 판단 근거 코드. [capabilities]가 있으면 그 신호가 모두 지원될 때만 나올 수 있다. */
@Serializable
enum class ReasonCode(vararg caps: CapabilityKey) {
    AIRBAG_FRONT(CapabilityKey.SEAT_AIRBAG_DEPLOYED),
    AIRBAG_SIDE(CapabilityKey.SEAT_AIRBAG_DEPLOYED),
    AIRBAG_UNKNOWN_KIND(CapabilityKey.SEAT_AIRBAG_DEPLOYED),
    AIRBAG_DEPLOYED(CapabilityKey.SEAT_AIRBAG_DEPLOYED),
    TIRE_LOW_NEW(CapabilityKey.LOW_TIRE_PRESSURE),
    TURN_AND_STEER_RIGHT(CapabilityKey.TURN_SIGNAL, CapabilityKey.STEERING_ANGLE),
    TURN_AND_STEER_LEFT(CapabilityKey.TURN_SIGNAL, CapabilityKey.STEERING_ANGLE),
    POWER_DOWN_NEW(CapabilityKey.POWER_DOWN_WARNING),
    FLUID_LOW_NEW(CapabilityKey.FLUID_LEVEL),
    DELTA_V_HIGH(CapabilityKey.VEHICLE_SPEED),
    OCCUPIED_UNBELTED(CapabilityKey.SEAT_OCCUPANCY, CapabilityKey.SEAT_BELT_BUCKLED),
    EXPRESSWAY_STOPPED(CapabilityKey.LOCATION, CapabilityKey.VEHICLE_SPEED),
    NIGHT_OR_RAIN_ON_ROADWAY(CapabilityKey.LOCATION, CapabilityKey.VEHICLE_SPEED),
    VEHICLE_WARNING_NEW,
    INJURY_REPORTED,
    FIRE_SMOKE_REPORTED,
    CONFIRM_NO_RESPONSE,
    EV_POWER_DOWN,
    OTHER_PARTY_FLED,
    OTHER_PARTY_SUSPICIOUS,
    PEDESTRIAN_OR_BICYCLE,
    ACCIDENT_MINOR,
    EXPRESSWAY_REPORTED_BY_USER;

    val capabilities: List<CapabilityKey> = caps.toList()
}

@Serializable
data class EvidenceRef(val tMs: Long, val signal: String, val detail: String)

@Serializable
data class ZoneEstimate(val zone: Zone, val confidence: Confidence, val because: List<ReasonCode>)

/** 값이 null이면 미지원 또는 미수신이다. false로 바꾸지 않는다. */
@Serializable
data class OccupantState(
    val seat: SeatArea,
    val occupied: Boolean?,
    val buckled: Boolean?,
    val airbagDeployed: Boolean?,
)

@Serializable
data class DamageProfile(
    val impactZones: List<ZoneEstimate>,
    val severity: Severity,
    val drivable: Tri,
    val occupants: List<OccupantState>,
    val evRisk: Boolean,
    val deltaVKmh: Float?,
    val evidence: List<EvidenceRef>,
    /** 전체 에어백 판단. 미지원·미수신이면 UNKNOWN이며 "미전개"로 단정하지 않는다. */
    val airbagDeployed: Tri,
    /** 충격 위치를 추정하지 못해 사진 확인이 필요하다. */
    val needsPhotoCheck: Boolean,
    val because: List<ReasonCode>,
)

@Serializable enum class RiskGrade { R0, R1, R2, R3, R4 }

@Serializable
data class GradeResult(val grade: RiskGrade, val because: List<ReasonCode>)

@Serializable
data class RiskAssessment(val grades: List<GradeResult>) {
    val has get() = grades.map { it.grade }.toSet()
}

/** MOVE_TO_SAFETY, EXPLAIN_WARNING은 지시문 4-4 표의 "안전한 곳 이동 안내", "경고 설명"에 해당한다. */
@Serializable
enum class ActionId {
    CALL_119, CALL_112, EVACUATE_GUIDE, CALL_2504, CALL_INSURER_EMERGENCY, CALL_INSURER_CLAIM,
    NOTIFY_GUARDIAN, BLUELINK_SOS_GUIDE, EXCHANGE_INFO_QR, TAKE_PHOTOS, TOW_GUARD, SERVICE_BOOKING,
    MOVE_TO_SAFETY, EXPLAIN_WARNING,
}

@Serializable enum class ActionKind { CONTACT, NOTIFY, GUIDE }
@Serializable enum class ContactChannel { DIAL, SMS_GUIDE, SCREEN_GUIDE }
@Serializable enum class ActionStatus { SUGGESTED, CONTACT_ATTEMPTED, CONFIRMED, DISMISSED }

@Serializable
data class Contact(val label: String, val number: String?, val channel: ContactChannel)

@Serializable
data class Action(
    val id: ActionId,
    val priority: Int,
    val grade: RiskGrade,
    val kind: ActionKind,
    /** 사람 확인이 필요하다. 연결 계열은 항상 true다. */
    val needsConfirm: Boolean,
    /**
     * 확인 없이 앱이 실행해도 되는 조치. 무응답 + 보호자 사전 동의일 때의 NOTIFY_GUARDIAN만 true가 될 수 있다.
     * 기관 연결(CONTACT)은 어떤 경우에도 true가 되지 않는다.
     */
    val autoExecutable: Boolean,
    val title: Message,
    val reason: Message,
    /** ④ 최종 확인 제목. 연결 계열에만 있다. */
    val confirmTitle: Message?,
    val script: Message?,
    val contact: Contact?,
    val checklist: List<Message>,
    val status: ActionStatus,
)

@Serializable
data class ActionPlan(val actions: List<Action>) {
    fun first(): Action? = actions.firstOrNull()
    fun find(id: ActionId): Action? = actions.firstOrNull { it.id == id }
}

@Serializable
data class CoverageHint(val coverage: Coverage, val reason: Message)

@Serializable
data class CoverageGap(
    val coverage: Coverage,
    val message: Message,
    val estimateLowKrw: Long? = null,
    val estimateHighKrw: Long? = null,
)

@Serializable
data class CoverageMatch(
    val claim: List<CoverageHint>,
    val gaps: List<CoverageGap>,
    val towNote: Message?,
    val disclaimer: Message,
)

@Serializable
enum class SessionState { CREATED, SAFETY_CHECK, SUPPORT_SELECTED, CONTACT_ATTEMPTED, CONFIRMED, RECOVERY, CLOSED, DISMISSED }

@Serializable
data class ActionHistoryEntry(val tMs: Long, val actionId: ActionId, val status: ActionStatus, val note: String? = null)

@Serializable
data class StateChange(val tMs: Long, val state: SessionState)

@Serializable
data class TimelineEntry(val tMs: Long, val channel: String, val value: String)

@Serializable
data class PhotoMeta(val tMs: Long, val zone: Zone?, val sha256: String?)

/**
 * 사고 확정 시점에 고정한 기록. [frozenSha256]는 고정한 이벤트 전체의 해시다.
 * 사건이 취소되면 함께 폐기되며, 저장은 앱이 이 값을 받은 뒤에만 한다.
 */
@Serializable
data class CaseCard(
    val frozenAtMs: Long,
    val suspicionMs: Long,
    val policyVersion: String,
    val location: Signal.Location?,
    val damage: DamageProfile,
    val timeline: List<TimelineEntry>,
    val frozenEventCount: Int,
    val frozenSha256: String,
    val actionHistory: List<ActionHistoryEntry>,
    val receiptNumbers: Map<ActionId, String>,
    val photos: List<PhotoMeta>,
    val photoChecklist: List<Message>,
)

@Serializable
data class IncidentSession(
    val state: SessionState,
    val history: List<StateChange>,
    val actionHistory: List<ActionHistoryEntry>,
    val caseCard: CaseCard?,
)

@Serializable
data class EngineResult(
    val candidate: IncidentCandidate,
    val damage: DamageProfile,
    val risk: RiskAssessment,
    val plan: ActionPlan,
    val coverage: CoverageMatch?,
    val session: IncidentSession,
    val pendingQuestion: TriageQ?,
    /** 무응답 처리 시각. UI의 카운트다운에 쓴다. */
    val questionDeadlineMs: Long?,
    /** 차가 움직이는 중이면 UI는 질문 화면·입력을 띄우지 않는다. */
    val vehicleMoving: Boolean,
    val policyVersion: String,
)
