package com.alertbridge.agent.advisor

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.core.jsonMapper
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolChoiceAuto
import com.anthropic.models.messages.ToolResultBlockParam
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Claude Messages API로 도구 루프를 돈다 (수동 루프: 실행 도구마다 승인 단계를 넣어야 해서).
 *
 * - 모델: claude-opus-5-5. 생각(thinking)은 항상 켜져 있고 effort로 깊이를 조절한다.
 * - 응답 전체(생각 블록 포함)를 그대로 대화에 다시 넣는다.
 * - 거절(refusal) 시 서버 측 대체 모델(fallbacks: "default")로 넘어가도록 켜 둔다.
 * - tool_choice는 auto만 쓴다 (이 모델은 강제 도구 선택을 받지 않는다).
 */
class ClaudeAgentModel(
    private val client: AnthropicClient,
    private val model: String = MODEL,
) : AgentModel {
    private var params: MessageCreateParams.Builder? = null

    override fun start(system: String, userMessage: String, tools: List<ToolSpec>): ModelTurn {
        val b = MessageCreateParams.builder()
            .model(model)
            .maxTokens(16_000L)
            .system(system)
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.MEDIUM).build())
            .toolChoice(ToolChoiceAuto.builder().build())
            .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
            .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        tools.forEach { b.addTool(toSdk(it)) }
        b.addUserMessage(userMessage)
        params = b
        return send()
    }

    override fun continueWith(results: List<ToolOutcome>): ModelTurn {
        val b = params ?: error("start를 먼저 불러야 한다")
        b.addUserMessageOfContentBlockParams(results.map { r ->
            ContentBlockParam.ofToolResult(
                ToolResultBlockParam.builder().toolUseId(r.callId).content(r.content).isError(r.isError).build()
            )
        })
        return send()
    }

    private fun send(): ModelTurn {
        val b = params!!
        val response: Message = client.messages().create(b.build())
        b.addMessage(response) // 생각 블록까지 그대로 다시 넣는다
        return toTurn(response)
    }

    private fun toTurn(m: Message): ModelTurn {
        val mapper = jsonMapper()
        val calls = m.content().mapNotNull { block ->
            block.toolUse().orElse(null)?.let { ToolCall(it.id(), it.name(), mapper.writeValueAsString(it._input())) }
        }
        val text = m.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("\n")
        val stop = when (m.stopReason().orElse(null)) {
            StopReason.END_TURN -> StopKind.END_TURN
            StopReason.TOOL_USE -> StopKind.TOOL_USE
            StopReason.MAX_TOKENS -> StopKind.MAX_TOKENS
            StopReason.REFUSAL -> StopKind.REFUSAL
            else -> StopKind.OTHER
        }
        return ModelTurn(calls, text, stop)
    }

    internal fun toSdk(spec: ToolSpec): Tool {
        val props = spec.schema["properties"] as JsonObject
        val required = (spec.schema["required"] as JsonArray).map { it.toString().trim('"') }
        val schema = Tool.InputSchema.builder()
            .properties(Tool.InputSchema.Properties.builder().apply {
                props.forEach { (k, v) -> putAdditionalProperty(k, toJsonValue(v)) }
            }.build())
            .required(required)
            .putAdditionalProperty("additionalProperties", JsonValue.from(false))
            .build()
        return Tool.builder().name(spec.name).description(spec.description).inputSchema(schema).strict(true).build()
    }

    private fun toJsonValue(e: kotlinx.serialization.json.JsonElement): JsonValue =
        JsonValue.fromJsonNode(jsonMapper().readTree(Json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), e)))

    companion object {
        const val MODEL = "claude-opus-5-5"

        /** 키가 비어 있으면 null. 휴대폰 앱에 키를 넣는 것은 시연용이다. 실제 배포는 서버 프록시를 둔다. */
        fun fromApiKey(apiKey: String?): ClaudeAgentModel? {
            if (apiKey.isNullOrBlank()) return null
            return ClaudeAgentModel(AnthropicOkHttpClient.builder().apiKey(apiKey).build())
        }
    }
}
