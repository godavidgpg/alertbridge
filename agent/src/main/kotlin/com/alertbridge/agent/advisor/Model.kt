package com.alertbridge.agent.advisor

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 견인을 부를지에 대한 판단 */
@Serializable enum class TowCall { YES, NO, UNSURE }

@Serializable enum class Verdict { COVERED, NOT_COVERED, CHECK_NEEDED }

@Serializable
data class CoverageVerdict(
    val item: String,
    val verdict: Verdict,
    val note: String,
    /** 예상 본인 부담. 모르면 null */
    @SerialName("estimated_out_of_pocket_krw") val outOfPocketKrw: Long? = null,
)

/** 판단 결과를 낸 쪽. 키가 없거나 AI가 실패하면 규칙 기반으로 대신한다. */
@Serializable enum class DecisionSource { AI, RULES }

/** 실행한 조치. 보험사가 준 접수번호가 있다. */
@Serializable
data class TakenAction(val tool: String, val label: String, val referenceNo: String, val message: String)

@Serializable
data class AdvisorDecision(
    val source: DecisionSource,
    val callTow: TowCall,
    val towReason: String,
    val coverage: List<CoverageVerdict>,
    val summary: String,
    val nextSteps: List<String>,
    val actionsTaken: List<TakenAction> = emptyList(),
    /** AI가 실패해 규칙으로 대신했을 때의 이유 */
    val fallbackReason: String? = null,
    val disclaimer: String = "최종 보상 여부는 약관과 보험사 판단에 따릅니다.",
    /** 등록한 보험 조건 + 공공 손해 통계로 코드가 계산한 예상 비용. 화면의 금액은 여기서만 나온다 */
    val costEstimate: com.alertbridge.agent.insurance.CostEstimate? = null,
)

/** AI가 실행하려는 조치. 사용자가 승인해야 실행된다. */
@Serializable
data class ActionRequest(val tool: String, val label: String, val detail: String, val rationale: String)

/** 진행 상황 (화면에 "보험 정보를 확인하고 있어요" 등) */
sealed interface AdvisorProgress {
    data class Calling(val tool: String, val label: String) : AdvisorProgress
    data class Thinking(val turn: Int) : AdvisorProgress
}

/** 한 번의 모델 호출 결과를 SDK와 무관한 형태로 옮긴 것. 루프를 테스트하기 위해 둔다. */
data class ModelTurn(
    val toolCalls: List<ToolCall>,
    val text: String,
    val stop: StopKind,
)

data class ToolCall(val id: String, val name: String, val inputJson: String)

data class ToolOutcome(val callId: String, val content: String, val isError: Boolean = false)

enum class StopKind { END_TURN, TOOL_USE, MAX_TOKENS, REFUSAL, OTHER }

/** 모델과의 대화. 실제 구현은 [ClaudeAgentModel], 테스트는 가짜를 쓴다. */
interface AgentModel {
    fun start(system: String, userMessage: String, tools: List<ToolSpec>): ModelTurn
    fun continueWith(results: List<ToolOutcome>): ModelTurn
}
