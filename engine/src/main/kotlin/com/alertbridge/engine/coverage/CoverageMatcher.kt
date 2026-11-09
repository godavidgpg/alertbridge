package com.alertbridge.engine.coverage

import com.alertbridge.engine.model.Coverage
import com.alertbridge.engine.model.CoverageGap
import com.alertbridge.engine.model.CoverageHint
import com.alertbridge.engine.model.CoverageMatch
import com.alertbridge.engine.model.EvBatteryInfo
import com.alertbridge.engine.model.InsurancePolicy
import com.alertbridge.engine.model.Message
import com.alertbridge.engine.policy.CoverageRule
import com.alertbridge.engine.policy.Messages
import com.alertbridge.engine.risk.Fact
import com.alertbridge.engine.risk.IncidentContext

/**
 * 이미 든 담보로 무엇을 청구할지 보여 주는 참고 정보다. 들지 않은 담보는 "확인 필요"로만 알리고
 * 상품을 권하지 않는다 (보험업법상 모집 행위로 오해될 수 있다).
 */
class CoverageMatcher(private val m: Messages) {

    fun match(c: IncidentContext, facts: Set<Fact>): CoverageMatch? {
        val policy = c.driver?.insurance ?: return null
        val hints = linkedMapOf<Coverage, CoverageHint>()
        val gaps = linkedMapOf<Coverage, CoverageGap>()

        for (rule in c.policy.coverageRules) {
            if (!facts.containsAll(rule.`when`)) continue
            rule.claim.filter { it in policy.coverages && it !in hints }.forEach {
                hints[it] = CoverageHint(it, m.msg(rule.reasonKey))
            }
            val missing = rule.gapIfMissing
            if (missing != null && missing !in policy.coverages && missing !in gaps) {
                gaps[missing] = gap(c, rule, missing, policy)
            }
        }

        return CoverageMatch(
            claim = hints.values.toList(),
            gaps = gaps.values.toList(),
            towNote = towNote(c, facts, policy),
            disclaimer = m.msg("coverage.disclaimer"),
        )
    }

    private fun gap(c: IncidentContext, rule: CoverageRule, coverage: Coverage, policy: InsurancePolicy): CoverageGap {
        val key = rule.gapKey ?: error("gapIfMissing에는 gapKey가 필요하다")
        if (rule.estimate != "BATTERY_DEPRECIATION") return CoverageGap(coverage, m.msg(key))

        val range = policy.evBattery?.let { estimateRange(it, c.nowMs, c.policy.thresholds.batteryEstimateRangePct) }
            ?: return CoverageGap(coverage, m.msg("coverage.gap.evBatteryNoEstimate"))
        return CoverageGap(
            coverage,
            m.msg(key, "low" to formatKrw(range.first), "high" to formatKrw(range.second)),
            range.first,
            range.second,
        )
    }

    private fun towNote(c: IncidentContext, facts: Set<Fact>, policy: InsurancePolicy): Message? {
        if (Fact.TOW_NEEDED !in facts) return null
        val free = policy.freeTowKm ?: return m.msg("coverage.tow.unknown")
        return if (Fact.TOW_OVER_FREE_KM in facts) {
            m.msg("coverage.tow.over", "destKm" to "%.0f".format(c.towDestinationKm), "freeKm" to free)
        } else m.msg("coverage.tow.free", "freeKm" to free)
    }

    companion object {
        private const val MS_PER_YEAR = 365.25 * 24 * 60 * 60 * 1000

        /** 배터리 본인부담 추정: 배터리가액 × (경과연수 ÷ 내구연한). 참고값이므로 범위로 낸다. */
        fun estimateMidKrw(b: EvBatteryInfo, nowMs: Long): Long {
            val years = ((nowMs - b.deliveryEpochMs) / MS_PER_YEAR).coerceAtLeast(0.0)
            val ratio = (years / b.usefulLifeYears).coerceAtMost(1.0)
            return (b.valueKrw * ratio).toLong()
        }

        fun estimateRange(b: EvBatteryInfo, nowMs: Long, pct: Int): Pair<Long, Long> {
            val mid = estimateMidKrw(b, nowMs)
            return (mid * (100 - pct) / 100) to minOf(mid * (100 + pct) / 100, b.valueKrw)
        }

        fun formatKrw(v: Long): String = "${(v + 5_000) / 10_000}만 원"
    }
}
