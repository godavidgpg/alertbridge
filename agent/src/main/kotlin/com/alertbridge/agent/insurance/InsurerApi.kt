package com.alertbridge.agent.insurance

import com.alertbridge.engine.model.Coverage
import com.alertbridge.engine.model.InsurancePolicy
import kotlinx.serialization.Serializable
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 보험사 API. 실제 연동은 보험사 제휴 API 또는 보험 마이데이터로 바꾼다 (미구현, TODO).
 * 실행 계열(견인 요청, 사고 접수)은 반드시 사용자 승인 뒤에만 부른다. 이 규칙은 [com.alertbridge.agent.advisor.InsuranceAdvisor]가 지킨다.
 */
interface InsurerApi {
    suspend fun getPolicy(): PolicyInfo
    suspend fun checkCoverage(req: CoverageRequest): CoverageCheck
    suspend fun quoteTow(lat: Double?, lng: Double?, destinationKm: Double?): TowQuote
    suspend fun requestEmergencyTow(req: TowRequest): DispatchResult
    suspend fun fileClaim(req: ClaimRequest): ClaimResult
}

@Serializable
data class CoverageTerm(
    val coverage: Coverage,
    val name: String,
    /** 보상 한도. null이면 무한 또는 약관 확인 */
    val limitKrw: Long? = null,
    /** 자기부담금 규칙을 사람이 읽는 문장으로 */
    val deductible: String? = null,
)

@Serializable
data class PolicyInfo(
    val insurer: String,
    val policyNo: String,
    val terms: List<CoverageTerm>,
    val freeTowKm: Int?,
    val emergencyServicesLeftThisYear: Int,
    val note: String,
)

@Serializable
enum class IncidentType { COLLISION_WITH_VEHICLE, SINGLE_VEHICLE, HIT_AND_RUN, PEDESTRIAN, BREAKDOWN, EV_BATTERY_DAMAGE }

@Serializable
data class CoverageRequest(val incidentType: IncidentType, val anyoneInjured: Boolean, val ownCarDamaged: Boolean)

@Serializable
data class CoverageItem(val coverage: Coverage, val name: String, val covered: Boolean, val note: String)

@Serializable
data class CoverageCheck(val items: List<CoverageItem>, val notHeld: List<String>, val disclaimer: String)

@Serializable
data class TowQuote(
    val nearestPartnerShop: String?,
    val distanceKm: Double?,
    val freeTowKm: Int?,
    /** 무료 거리 초과분 예상 요금. 예시 요율이다 */
    val estimatedExtraKrw: Long?,
    val note: String,
)

@Serializable
data class TowRequest(val lat: Double?, val lng: Double?, val address: String?, val reason: String, val destination: String?)

@Serializable
data class DispatchResult(val dispatchId: String, val etaMinutes: Int, val message: String)

@Serializable
data class ClaimRequest(val incidentType: IncidentType, val summary: String, val address: String?)

@Serializable
data class ClaimResult(val claimNo: String, val message: String)

/**
 * 시연용 보험사. 등록된 보험 프로필에서 약관을 만든다.
 * 요율·자기부담금은 흔한 국내 자동차보험 구조를 흉내 낸 **예시 값**이며 실제 상품이 아니다.
 */
class MockInsurerApi(
    private val policy: InsurancePolicy,
    private val idSeed: Long = System.currentTimeMillis(),
) : InsurerApi {
    private var dispatchCount = 0
    private var claimCount = 0

    /** 시연용 협력 정비소 */
    private data class Shop(val name: String, val lat: Double, val lng: Double)
    private val shops = listOf(
        Shop("협력정비소 강남점", 37.4979, 127.0276),
        Shop("협력정비소 성수점", 37.5446, 127.0557),
        Shop("협력정비소 판교점", 37.3948, 127.1112),
        Shop("협력정비소 강릉점", 37.7519, 128.8761),
        Shop("협력정비소 대전점", 36.3504, 127.3845),
    )

    private fun term(c: Coverage): CoverageTerm = when (c) {
        Coverage.DAEIN_1 -> CoverageTerm(c, "대인배상 I", limitKrw = 150_000_000)
        Coverage.DAEIN_2 -> CoverageTerm(c, "대인배상 II", limitKrw = null)
        Coverage.DAEMUL -> CoverageTerm(c, "대물배상", limitKrw = 1_000_000_000)
        Coverage.JASIN -> CoverageTerm(c, "자기신체사고", limitKrw = 50_000_000)
        Coverage.JASANG -> CoverageTerm(c, "자동차상해", limitKrw = 100_000_000)
        Coverage.JACHA -> CoverageTerm(c, "자기차량손해", deductible = "자기부담금은 손해액의 20%, 최소 20만 원 최대 50만 원")
        Coverage.MUBOHEOM -> CoverageTerm(c, "무보험차상해", limitKrw = 200_000_000)
        Coverage.EMERGENCY_TOW -> CoverageTerm(c, "긴급출동 견인", deductible = "무료 거리를 넘으면 km당 2,500원")
        Coverage.EV_BATTERY_NEW_VALUE -> CoverageTerm(c, "배터리 신가 보상")
        Coverage.EV_TOW_SOS -> CoverageTerm(c, "전기차 긴급출동")
        Coverage.REMOTE_RETURN -> CoverageTerm(c, "원격지 귀가")
        Coverage.TRANSPORT_COST -> CoverageTerm(c, "대체 교통비")
        Coverage.LEGAL_COST -> CoverageTerm(c, "법률 비용")
        Coverage.OTHER_CAR -> CoverageTerm(c, "다른 차 운전")
    }

    override suspend fun getPolicy() = PolicyInfo(
        insurer = policy.insurer,
        policyNo = policy.policyNo,
        terms = policy.coverages.sortedBy { it.ordinal }.map(::term),
        freeTowKm = policy.freeTowKm,
        emergencyServicesLeftThisYear = 5,
        note = "시연용 보험사 응답입니다. 금액은 예시이며 실제 약관과 다를 수 있습니다.",
    )

    override suspend fun checkCoverage(req: CoverageRequest): CoverageCheck {
        val held = policy.coverages
        val items = mutableListOf<CoverageItem>()
        fun add(c: Coverage, note: String) { if (c in held) items += CoverageItem(c, term(c).name, true, note) }
        val notHeld = mutableListOf<String>()
        fun need(c: Coverage, why: String) { if (c !in held) notHeld += "${term(c).name}: $why" }

        when (req.incidentType) {
            IncidentType.COLLISION_WITH_VEHICLE, IncidentType.PEDESTRIAN -> {
                add(Coverage.DAEMUL, "상대 차량·재물 손해. 과실 비율에 따라 처리돼요.")
                add(Coverage.DAEIN_1, "상대방 부상 시")
                add(Coverage.DAEIN_2, "대인 I 한도를 넘는 부분")
            }
            IncidentType.HIT_AND_RUN -> {
                add(Coverage.MUBOHEOM, "가해자를 찾지 못하면 내 부상에 적용될 수 있어요.")
                need(Coverage.MUBOHEOM, "가해자를 찾지 못했을 때 내 부상 보상")
            }
            IncidentType.SINGLE_VEHICLE, IncidentType.BREAKDOWN, IncidentType.EV_BATTERY_DAMAGE -> Unit
        }
        if (req.ownCarDamaged && req.incidentType != IncidentType.BREAKDOWN) {
            add(Coverage.JACHA, "내 차 수리. ${term(Coverage.JACHA).deductible}이에요.")
            need(Coverage.JACHA, "내 차 수리비")
        }
        if (req.anyoneInjured) {
            add(Coverage.JASIN, "내 차 탑승자 부상")
            add(Coverage.JASANG, "내 차 탑승자 부상 (실손 기준)")
        }
        if (req.incidentType == IncidentType.EV_BATTERY_DAMAGE) {
            add(Coverage.EV_BATTERY_NEW_VALUE, "배터리 교체 시 감가 없이 보상")
            need(Coverage.EV_BATTERY_NEW_VALUE, "배터리 교체 시 감가분")
        }
        add(Coverage.EMERGENCY_TOW, "무료 견인 ${policy.freeTowKm ?: "?"}km")
        return CoverageCheck(items, notHeld, "최종 보상 여부는 약관과 보험사 판단에 따릅니다.")
    }

    override suspend fun quoteTow(lat: Double?, lng: Double?, destinationKm: Double?): TowQuote {
        val free = policy.freeTowKm
        val (shop, km) = if (lat != null && lng != null) {
            shops.map { it to haversineKm(lat, lng, it.lat, it.lng) * ROAD_FACTOR }.minBy { it.second }
        } else null to null
        val distance = destinationKm ?: km
        val extra = if (distance != null && free != null) ((distance - free).coerceAtLeast(0.0) * EXTRA_PER_KM).roundToLong() else null
        return TowQuote(
            nearestPartnerShop = shop?.name.takeIf { destinationKm == null },
            distanceKm = distance?.let { (it * 10).roundToLong() / 10.0 },
            freeTowKm = free,
            estimatedExtraKrw = extra,
            note = if (distance == null) "위치를 알 수 없어 거리를 계산하지 못했어요." else "도로 거리는 직선거리의 1.3배로 추정한 예시 값이에요.",
        )
    }

    override suspend fun requestEmergencyTow(req: TowRequest): DispatchResult {
        if (EMERGENCY_TOW !in policy.coverages) error("긴급출동 견인 담보가 없어 보험사 출동을 접수할 수 없어요.")
        dispatchCount++
        return DispatchResult("TOW-${idSeed % 100000}-$dispatchCount", etaMinutes = 25, message = "긴급출동 견인을 접수했어요. 기사님이 출발하면 문자로 알려 드려요. (시연용 응답)")
    }

    override suspend fun fileClaim(req: ClaimRequest): ClaimResult {
        claimCount++
        return ClaimResult("CLM-${idSeed % 100000}-$claimCount", "사고 접수를 받았어요. 담당자가 연락드릴 거예요. (시연용 응답)")
    }

    private companion object {
        val EMERGENCY_TOW = Coverage.EMERGENCY_TOW
        const val EXTRA_PER_KM = 2_500.0
        const val ROAD_FACTOR = 1.3

        fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
            return 2 * 6371.0 * asin(sqrt(a))
        }
    }
}
