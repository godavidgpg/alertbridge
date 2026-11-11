package com.alertbridge.engine.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable enum class Role { DRIVER, PASSENGER }

/** 안내 모드. NOVICE·SENIOR는 신고 문장을 짧게 끊는다. */
@Serializable enum class Experience { NOVICE, NORMAL, SENIOR }

@Serializable
enum class Coverage {
    DAEIN_1, DAEIN_2, DAEMUL, JASIN, JASANG, JACHA, MUBOHEOM, EMERGENCY_TOW,
    EV_BATTERY_NEW_VALUE, EV_TOW_SOS, REMOTE_RETURN, TRANSPORT_COST, LEGAL_COST, OTHER_CAR,
}

@Serializable
data class EvBatteryInfo(val valueKrw: Long, val deliveryEpochMs: Long, val usefulLifeYears: Int)

@Serializable
data class InsurancePolicy(
    val insurer: String,
    val policyNo: String,
    val emergencyPhone: String,
    val claimPhone: String,
    val coverages: Set<Coverage> = emptySet(),
    val freeTowKm: Int? = null,
    val evBattery: EvBatteryInfo? = null,
    // 아래는 가입할 때 사용자가 등록한 상품 정보다. 위험 판단에는 쓰지 않고 비용 추정에만 쓴다.
    val productName: String? = null,
    /** 보험 종목. 공공 통계(개인용/업무용/영업용)와 맞춘다 */
    val vehicleUse: VehicleUse = VehicleUse.PERSONAL,
    /** 보험 차종 구분. 공공 통계(소형/중형/대형/다인승)와 맞춘다 */
    val carClass: InsuredCarClass = InsuredCarClass.UNKNOWN,
    val daemulLimitKrw: Long? = null,
    /** 자기차량손해 자기부담금 조건 */
    val jachaDeductible: DeductibleRule? = null,
    /** 물적사고 할증기준금액. 보험금이 이 금액을 넘으면 사고 점수가 높아진다 */
    val propertySurchargeThresholdKrw: Long? = null,
)

@Serializable enum class VehicleUse(val statName: String) { PERSONAL("개인용"), BUSINESS("업무용"), COMMERCIAL("영업용") }

@Serializable enum class InsuredCarClass(val statName: String?) { SMALL("소형"), MID("중형"), LARGE("대형"), MULTI("다인승"), UNKNOWN(null) }

/** 자기부담금 = 손해액 × [ratePct]%, [minKrw]~[maxKrw] 사이 */
@Serializable
data class DeductibleRule(val ratePct: Int, val minKrw: Long, val maxKrw: Long) {
    fun apply(lossKrw: Long): Long = (lossKrw * ratePct / 100).coerceIn(minKrw, maxOf(minKrw, maxKrw))
}

@Serializable
data class OccupantProfile(
    val id: String,
    val name: String,
    val role: Role = Role.DRIVER,
    val experience: Experience = Experience.NORMAL,
    val language: String = "ko",
    val guardianPhone: String? = null,
    /** 무응답 시 보호자 자동 알림에 대한 사전 동의. 없으면 알림도 확인을 거친다. */
    val guardianAutoNotifyConsent: Boolean = false,
    /** 선택 항목. 기기 내 저장만 한다. */
    val medicalNote: String? = null,
    /** 119 신고 문장에 의료 정보를 넣는 데 대한 별도 동의 */
    val medicalShareConsent: Boolean = false,
    val insurance: InsurancePolicy? = null,
    val homeLat: Double? = null,
    val homeLng: Double? = null,
)

@Serializable enum class Powertrain { EV, NON_EV, UNKNOWN }
@Serializable enum class VehicleSizeClass { SMALL, LARGE, UNKNOWN }

/** Pleos CarInfo는 VIN·CRN만 주므로 차종 정보는 사용자 등록값이다. */
@Serializable
data class VehicleProfile(
    val powertrain: Powertrain = Powertrain.UNKNOWN,
    val sizeClass: VehicleSizeClass = VehicleSizeClass.UNKNOWN,
)

/** IS_EXPRESSWAY는 도로 판정이 불확실할 때 엔진이 추가하는 질문이다. */
@Serializable
enum class TriageQ { IS_ACCIDENT, IS_EXPRESSWAY, INJURY, FIRE_SMOKE, DRIVABLE, OTHER_PARTY_FLED, OTHER_PARTY_SUSPICIOUS }

@Serializable enum class Answer { YES, NO, UNSURE, NO_RESPONSE }

@Serializable
data class TriageAnswer(val question: TriageQ, val answer: Answer, val tMs: Long)

/** UI가 엔진에 알리는 사용자 행동. 어떤 항목도 실제 발신을 뜻하지 않는다. */
@Serializable
sealed interface UserActionEvent {
    val tMs: Long

    /** 수동 신고 또는 음성 "사고 났어" */
    @Serializable @SerialName("ManualReport")
    data class ManualReport(override val tMs: Long) : UserActionEvent

    /** ④ 최종 확인 다이얼로그를 열었다 */
    @Serializable @SerialName("SupportSelected")
    data class SupportSelected(val actionId: ActionId, override val tMs: Long) : UserActionEvent

    /** [신고 연결]을 눌러 전화 화면을 열었다. 통화 성사나 접수를 뜻하지 않는다. */
    @Serializable @SerialName("ContactAttempted")
    data class ContactAttempted(val actionId: ActionId, override val tMs: Long) : UserActionEvent

    @Serializable @SerialName("ReceiptNumberEntered")
    data class ReceiptNumberEntered(val actionId: ActionId, val number: String, override val tMs: Long) : UserActionEvent

    @Serializable @SerialName("MarkedDone")
    data class MarkedDone(val actionId: ActionId, override val tMs: Long) : UserActionEvent

    @Serializable @SerialName("ActionDismissed")
    data class ActionDismissed(val actionId: ActionId, override val tMs: Long) : UserActionEvent

    /** 앱의 GuardianNotifier가 보호자 알림을 보낸 뒤 알린다 */
    @Serializable @SerialName("GuardianNotified")
    data class GuardianNotified(override val tMs: Long) : UserActionEvent

    /** 수동 입력: 사고 차량 수, 보행자·자전거 관련 여부 */
    @Serializable @SerialName("IncidentDetail")
    data class IncidentDetail(
        val vehicleCount: Int? = null,
        val pedestrianOrBicycle: Boolean? = null,
        override val tMs: Long,
    ) : UserActionEvent

    @Serializable @SerialName("TowDestinationSet")
    data class TowDestinationSet(val distanceKm: Float, override val tMs: Long) : UserActionEvent

    @Serializable @SerialName("PhotoTaken")
    data class PhotoTaken(val zone: Zone? = null, val sha256: String? = null, override val tMs: Long) : UserActionEvent

    @Serializable @SerialName("EnterRecovery")
    data class EnterRecovery(override val tMs: Long) : UserActionEvent

    @Serializable @SerialName("CloseSession")
    data class CloseSession(override val tMs: Long) : UserActionEvent

    /** 사용자가 사건을 취소한다. 접수 진행 전에만 유효하다. */
    @Serializable @SerialName("CancelSession")
    data class CancelSession(override val tMs: Long) : UserActionEvent
}
