package com.alertbridge.agent.insurance

import com.alertbridge.agent.stats.ClaimStats
import com.alertbridge.agent.stats.LossStat
import com.alertbridge.agent.stats.StatCoverage
import com.alertbridge.agent.stats.StatTable
import com.alertbridge.engine.model.Coverage
import com.alertbridge.engine.model.InsurancePolicy
import kotlinx.serialization.Serializable
import java.text.NumberFormat
import java.util.Locale

@Serializable
data class CostRequest(
    val incidentType: IncidentType,
    val ownCarDamaged: Boolean,
    /** 상대 차량·시설물 등 남의 재물 손해 */
    val otherPropertyDamaged: Boolean,
    val anyoneInjured: Boolean,
)

/** 담보 하나의 예상 비용 */
@Serializable
data class CostLine(
    /** [MockInsurerApi]의 담보 이름과 같다 (예: "자기차량손해") */
    val coverageName: String,
    val held: Boolean,
    /** 같은 종목·차종의 건당 평균 손해액 (공공 통계) */
    val typicalLossKrw: Long?,
    /** 평균 손해액 기준 예상 본인 부담. 계산할 수 없으면 null */
    val outOfPocketKrw: Long?,
    /** 약관상 본인 부담 상한. 없으면 null */
    val outOfPocketMaxKrw: Long?,
    val note: String,
)

/** 물적사고 할증기준금액과 비교 */
@Serializable
data class SurchargeCheck(val thresholdKrw: Long, val expectedPropertyLossKrw: Long, val likelyExceeds: Boolean, val note: String)

@Serializable
data class CostEstimate(
    val lines: List<CostLine>,
    val surcharge: SurchargeCheck?,
    /** 예: "개인용 중형 · 2026년 1~8월 누계" */
    val basis: String?,
    val source: String?,
    val liveStats: Boolean,
    val disclaimer: String = DISCLAIMER,
) {
    companion object {
        const val DISCLAIMER = "같은 차종 평균으로 계산한 추정이에요. 실제 금액은 파손 정도, 과실 비율, 약관에 따라 달라요."
    }
}

/**
 * 가입할 때 등록한 보험 정보와 공공 손해 통계를 합쳐 예상 비용을 계산한다.
 * 숫자는 모두 이 코드가 계산한다. AI는 이 결과를 설명만 한다.
 */
class CostService(private val policy: InsurancePolicy, private val stats: ClaimStats) {
    suspend fun estimate(req: CostRequest): CostEstimate = CostEstimator.estimate(policy, req, stats.table())
}

object CostEstimator {
    fun estimate(policy: InsurancePolicy, req: CostRequest, table: StatTable): CostEstimate {
        fun stat(c: StatCoverage): LossStat? = table.average(c, policy.vehicleUse, policy.carClass)
        val held = policy.coverages
        val lines = mutableListOf<CostLine>()
        var propertyLoss = 0L
        var propertyClaimed = false

        if (req.ownCarDamaged && req.incidentType != IncidentType.BREAKDOWN) {
            val s = stat(StatCoverage.JACHA)
            val rule = policy.jachaDeductible
            lines += when {
                Coverage.JACHA !in held -> CostLine(
                    "자기차량손해", held = false, typicalLossKrw = s?.avgLossKrw, outOfPocketKrw = s?.avgLossKrw, outOfPocketMaxKrw = null,
                    note = "자기차량손해가 없어 내 차 수리비는 직접 내야 해요." +
                        (if (req.incidentType == IncidentType.COLLISION_WITH_VEHICLE) " 상대 과실만큼은 상대 보험에서 받을 수 있어요." else ""),
                )
                rule == null -> CostLine(
                    "자기차량손해", held = true, typicalLossKrw = s?.avgLossKrw, outOfPocketKrw = null, outOfPocketMaxKrw = null,
                    note = "자기부담금 조건이 등록되지 않아 본인 부담을 계산하지 못했어요.",
                ).also { if (s != null) { propertyLoss += s.avgLossKrw; propertyClaimed = true } }
                else -> {
                    val oop = s?.let { rule.apply(it.avgLossKrw) }
                    if (s != null) { propertyLoss += s.avgLossKrw; propertyClaimed = true }
                    CostLine(
                        "자기차량손해", held = true, typicalLossKrw = s?.avgLossKrw, outOfPocketKrw = oop, outOfPocketMaxKrw = rule.maxKrw,
                        note = "자기부담금: 수리비의 ${rule.ratePct}%, 최소 ${approx(rule.minKrw)} · 최대 ${approx(rule.maxKrw)}" +
                            (if (s == null) ". 통계가 없어 상한만 알 수 있어요." else ""),
                    )
                }
            }
        }

        if (req.otherPropertyDamaged || req.incidentType == IncidentType.COLLISION_WITH_VEHICLE) {
            val s = stat(StatCoverage.DAEMUL)
            val limit = policy.daemulLimitKrw
            lines += if (Coverage.DAEMUL in held) {
                if (s != null) { propertyLoss += s.avgLossKrw; propertyClaimed = true }
                val over = limit != null && s != null && s.avgLossKrw > limit
                CostLine(
                    "대물배상", held = true, typicalLossKrw = s?.avgLossKrw,
                    outOfPocketKrw = if (over) s!!.avgLossKrw - limit!! else 0, outOfPocketMaxKrw = null,
                    note = "상대 차량·시설물 손해는 대물배상${limit?.let { " 한도 ${approx(it)}" } ?: ""} 안에서 보험사가 내요." +
                        "",
                )
            } else {
                CostLine("대물배상", held = false, typicalLossKrw = s?.avgLossKrw, outOfPocketKrw = null, outOfPocketMaxKrw = null, note = "대물배상 가입 내용을 보험사에 확인해야 해요.")
            }
        }

        if (req.incidentType == IncidentType.PEDESTRIAN || (req.anyoneInjured && req.incidentType == IncidentType.COLLISION_WITH_VEHICLE)) {
            val s = stat(StatCoverage.DAEIN_1)
            lines += CostLine(
                "대인배상 I", held = Coverage.DAEIN_1 in held, typicalLossKrw = s?.avgLossKrw,
                outOfPocketKrw = if (Coverage.DAEIN_1 in held) 0 else null, outOfPocketMaxKrw = null,
                note = if (Coverage.DAEIN_1 in held) "상대방 치료비는 대인배상으로 보험사가 내요." else "대인배상 가입 내용을 보험사에 확인해야 해요.",
            )
        }

        val threshold = policy.propertySurchargeThresholdKrw
        val surcharge = if (threshold != null && propertyClaimed) {
            val exceeds = propertyLoss > threshold
            SurchargeCheck(
                threshold, propertyLoss, exceeds,
                if (exceeds) "평균으로 보면 이번 물적 손해(약 ${approx(propertyLoss)})가 할증 기준 ${approx(threshold)}을 넘을 수 있어요. 넘으면 사고 점수가 높아져 보험료가 더 오를 수 있어요."
                else "평균으로 보면 이번 물적 손해(약 ${approx(propertyLoss)})는 할증 기준 ${approx(threshold)} 안이에요.",
            )
        } else null

        val any = lines.any { it.typicalLossKrw != null }
        val carLabel = policy.carClass.statName ?: "전체 차종"
        return CostEstimate(
            lines = lines,
            surcharge = surcharge,
            basis = table.ym?.takeIf { any }?.let { "${policy.vehicleUse.statName} $carLabel · ${StatTable.periodLabel(it)}" },
            source = table.source.takeIf { any },
            liveStats = table.live,
        )
    }

    fun won(v: Long): String = NumberFormat.getInstance(Locale.KOREA).format(v) + "원"

    /** 사람이 읽기 쉬운 금액: 1,504,835 → "150만 원", 1억 → "1억 원" */
    fun approx(v: Long): String {
        if (v < 10_000) return won(v)
        val man = Math.round(v / 10_000.0)
        val f = NumberFormat.getInstance(Locale.KOREA)
        return when {
            man >= 10_000 && man % 10_000 == 0L -> "${f.format(man / 10_000)}억 원"
            man >= 10_000 -> "${f.format(man / 10_000)}억 ${f.format(man % 10_000)}만 원"
            else -> "${f.format(man)}만 원"
        }
    }
}
