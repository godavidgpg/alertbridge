package com.alertbridge.agent.advisor

import com.alertbridge.agent.insurance.InsurerApi
import com.alertbridge.engine.model.EngineResult
import com.alertbridge.engine.model.Tri
import com.alertbridge.engine.model.VehicleProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 사고 뒤 보험 조치를 판단하고, 사용자가 승인하면 실행까지 한다.
 *
 * - 읽기 도구는 바로 실행한다. 실행 도구(견인 요청, 사고 접수)는 [approve]가 true를 돌려줄 때만 실행한다.
 * - 같은 실행 도구는 한 번만 실행한다 (중복 출동 방지).
 * - 모델 호출이 실패하거나 거절되면 [RuleBasedAdvisor]의 판단을 돌려준다. 화면은 항상 결과를 받는다.
 */
class InsuranceAdvisor(
    private val model: AgentModel?,
    private val maxTurns: Int = 10,
) {
    suspend fun decide(
        result: EngineResult,
        vehicle: VehicleProfile?,
        insurer: InsurerApi,
        approve: suspend (ActionRequest) -> Boolean,
        onProgress: (AdvisorProgress) -> Unit = {},
    ): AdvisorDecision {
        val brief = IncidentBriefs.from(result, vehicle)
        val tools = ToolExecutor(brief, insurer)
        val rules = suspend { RuleBasedAdvisor.decide(brief, insurer) }
        val m = model ?: return rules().copy(fallbackReason = "AI 키가 설정되지 않아 규칙으로 판단했어요.")

        val taken = mutableListOf<TakenAction>()
        val executedActions = mutableSetOf<String>()
        return try {
            onProgress(AdvisorProgress.Thinking(1))
            var turn = m.start(SYSTEM_PROMPT, userMessage(brief), Tools.all)
            repeat(maxTurns) { i ->
                when (turn.stop) {
                    StopKind.REFUSAL -> return rules().copy(actionsTaken = taken, fallbackReason = "AI가 이 요청에 답하지 않아 규칙으로 판단했어요.")
                    StopKind.MAX_TOKENS -> return rules().copy(actionsTaken = taken, fallbackReason = "AI 응답이 끊겨 규칙으로 판단했어요.")
                    else -> Unit
                }
                val submit = turn.toolCalls.firstOrNull { it.name == Tools.SUBMIT.name }
                if (submit != null) {
                    val d = ToolExecutor.parseDecision(submit.inputJson)
                        ?: return rules().copy(actionsTaken = taken, fallbackReason = "AI 판단 형식이 맞지 않아 규칙으로 판단했어요.")
                    return d.copy(actionsTaken = taken)
                }
                if (turn.toolCalls.isEmpty()) {
                    return rules().copy(actionsTaken = taken, fallbackReason = "AI가 판단을 제출하지 않아 규칙으로 판단했어요.")
                }

                // 한 응답의 도구 호출 결과는 모두 한 메시지로 돌려준다.
                val outcomes = turn.toolCalls.map { call ->
                    val spec = Tools.byName(call.name)
                        ?: return@map ToolOutcome(call.id, "알 수 없는 도구예요: ${call.name}", isError = true)
                    onProgress(AdvisorProgress.Calling(spec.name, spec.label))
                    if (spec.action) {
                        if (spec.name in executedActions) return@map ToolOutcome(call.id, "이미 실행한 조치예요. 다시 실행하지 않았어요.", isError = true)
                        val request = ActionRequest(spec.name, spec.label, ToolExecutor.describe(spec, call.inputJson), turn.text.trim())
                        if (!approve(request)) return@map ToolOutcome(call.id, "사용자가 이 조치를 거절했어요. 실행하지 않았어요.")
                        executedActions += spec.name
                    }
                    runCatching { tools.run(spec.name, call.inputJson) }
                        .map { out ->
                            if (spec.action) taken += TakenAction(spec.name, spec.label, referenceNo(out), message(out))
                            ToolOutcome(call.id, out)
                        }
                        .getOrElse { e -> ToolOutcome(call.id, "실패: ${e.message}", isError = true) }
                }
                onProgress(AdvisorProgress.Thinking(i + 2))
                turn = m.continueWith(outcomes)
            }
            rules().copy(actionsTaken = taken, fallbackReason = "AI 판단이 너무 길어져 규칙으로 판단했어요.")
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            rules().copy(actionsTaken = taken, fallbackReason = "AI에 연결하지 못해 규칙으로 판단했어요. (${e.javaClass.simpleName})")
        }
    }

    private fun userMessage(b: IncidentBrief) = buildString {
        append("방금 차량 사고(또는 고장) 판단이 나왔습니다. 도구로 사고 요약과 가입 보험을 확인한 뒤, ")
        append("보험사 견인을 불러야 하는지와 이번 사고가 어떤 담보로 보장되는지 판단해 submit_decision으로 제출하세요.")
        if (b.lifeThreatening) append(" 인명 위험(R0)이 있는 사고입니다. 운전자는 119 연결을 먼저 안내받고 있습니다.")
    }

    private fun field(out: String, key: String) =
        runCatching { Json.parseToJsonElement(out).jsonObject[key]?.jsonPrimitive?.content }.getOrNull()

    private fun referenceNo(out: String) = field(out, "dispatchId") ?: field(out, "claimNo") ?: ""
    private fun message(out: String) = field(out, "message") ?: ""

    companion object {
        /** 판단 기준. 안전 원칙은 도구 구성(112·119 도구 없음, 실행은 승인 후)으로도 강제된다. */
        val SYSTEM_PROMPT = """
            You help a driver in Korea decide what to do with their car insurance right after a crash or breakdown.
            A rule engine has already analysed the vehicle sensors; trust its summary and do not re-interpret raw sensors.

            Your job:
            1. Call get_incident_summary and get_insurance_policy, then check_coverage for the right incident type. Use quote_tow when towing is in question.
            2. Decide whether to call the insurer's emergency tow (YES / NO / UNSURE) and which coverages apply, with the expected out-of-pocket cost when the policy data allows it.
            3. Only when it is clearly warranted, request the action itself: request_emergency_tow when the car cannot be driven or must be moved off a dangerous road, file_accident_claim when a held coverage applies. These run only after the driver approves; if the driver declines, respect it and carry on.
            4. Finish by calling submit_decision exactly once.

            Ground rules:
            - Emergency services (112/119) are handled outside your tools by the rule engine with the driver's confirmation. When the incident is life-threatening, tell the driver in next_steps that the 119 step comes first; never suggest delaying it for insurance.
            - Base coverage judgments only on the policy and coverage data the tools return. If something is not in the data, mark it CHECK_NEEDED rather than guessing.
            - Do not recommend buying insurance or adding riders. For coverages the driver does not hold, say only that it needs checking.
            - Amounts from the insurer tool are examples from a demo insurer; present them as estimates.
            - Write everything the driver will read in short, plain Korean (해요체). No markdown.
        """.trimIndent()
    }
}

/** AI가 없거나 실패했을 때의 판단. 규칙 엔진 결과와 보험사 API만으로 정한다. */
object RuleBasedAdvisor {
    suspend fun decide(b: IncidentBrief, insurer: InsurerApi): AdvisorDecision {
        val type = when {
            b.kind == "FAULT" -> com.alertbridge.agent.insurance.IncidentType.BREAKDOWN
            b.otherPartyFled -> com.alertbridge.agent.insurance.IncidentType.HIT_AND_RUN
            b.pedestrianOrBicycle -> com.alertbridge.agent.insurance.IncidentType.PEDESTRIAN
            b.evRisk -> com.alertbridge.agent.insurance.IncidentType.EV_BATTERY_DAMAGE
            else -> com.alertbridge.agent.insurance.IncidentType.SINGLE_VEHICLE
        }
        val check = insurer.checkCoverage(
            com.alertbridge.agent.insurance.CoverageRequest(type, b.injuryReported || b.lifeThreatening, b.kind != "FAULT")
        )
        val tow = when (b.drivable) {
            Tri.NO.name -> TowCall.YES
            Tri.YES.name -> TowCall.NO
            else -> TowCall.UNSURE
        }
        val quote = if (tow != TowCall.NO) insurer.quoteTow(b.location?.lat, b.location?.lng, null) else null
        val towReason = when (tow) {
            TowCall.YES -> "차가 움직이기 어려운 상태로 판단돼요." + (quote?.nearestPartnerShop?.let { " 가까운 곳은 $it(약 ${quote.distanceKm}km)이에요." } ?: "")
            TowCall.NO -> "스스로 운전할 수 있다고 답하셨어요."
            TowCall.UNSURE -> "운전할 수 있는지 아직 알 수 없어요. 안전한 곳에서 확인해 주세요."
        }
        val verdicts = check.items.map { CoverageVerdict(it.name, Verdict.COVERED, it.note) } +
            check.notHeld.map { CoverageVerdict(it.substringBefore(":"), Verdict.CHECK_NEEDED, it.substringAfter(": ")) }
        val steps = buildList {
            if (b.lifeThreatening) add("119 구조 요청을 먼저 해 주세요.")
            if (tow == TowCall.YES) add("보험사 긴급출동 견인을 부르세요.")
            if (check.items.any { it.coverage.name != "EMERGENCY_TOW" }) add("보험사에 사고를 접수하세요.")
            add("현장 사진을 찍어 두세요.")
        }
        return AdvisorDecision(
            source = DecisionSource.RULES,
            callTow = tow,
            towReason = towReason,
            coverage = verdicts,
            summary = if (verdicts.any { it.verdict == Verdict.COVERED }) "가입한 담보로 보상받을 수 있는 항목이 있어요." else "보상 가능 여부는 보험사 확인이 필요해요.",
            nextSteps = steps,
            disclaimer = check.disclaimer,
        )
    }
}
