package com.alertbridge.agent

import com.alertbridge.agent.advisor.ClaudeAgentModel
import com.alertbridge.agent.advisor.DecisionSource
import com.alertbridge.agent.advisor.InsuranceAdvisor
import com.alertbridge.agent.advisor.Tools
import com.alertbridge.agent.advisor.TowCall
import com.alertbridge.agent.insurance.MockInsurerApi
import com.alertbridge.engine.AlertBridgeEngine
import com.alertbridge.engine.model.*
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.jsonMapper
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 실제 SDK로 요청을 만들고 가짜 Claude API 서버의 응답을 해석하는지 본다. API 키·비용 없이 SDK 사용법을 검증한다.
 * 응답 형식은 Messages API 문서의 형태를 따른다.
 */
class ClaudeWireTest {
    private val server = MockWebServer().also { it.start() }

    @AfterEach fun stop() = server.shutdown()

    private fun message(id: String, stop: String, content: String) = MockResponse()
        .setHeader("content-type", "application/json")
        .setBody("""{"id":"$id","type":"message","role":"assistant","model":"claude-opus-5-5","content":[$content],"stop_reason":"$stop","stop_sequence":null,"usage":{"input_tokens":10,"output_tokens":5}}""")

    private fun result(): EngineResult {
        val e = AlertBridgeEngine.withDefaultPolicy()
        e.configure(CapabilityMap.ALL_SUPPORTED)
        e.onUserAction(UserActionEvent.ManualReport(1_000))
        e.onEvent(VehicleEvent(1_500, Signal.LowTirePressure(WheelArea.LEFT_FRONT, true)))
        return e.currentResult(2_000)!!
    }

    @Test fun `SDK로 도구 루프를 돌고 생각 블록까지 다시 보낸다`() = runTest {
        server.enqueue(message("msg_1", "tool_use",
            """{"type":"thinking","thinking":"","signature":"sig-1"},{"type":"tool_use","id":"tu_1","name":"get_incident_summary","input":{}}"""))
        server.enqueue(message("msg_2", "tool_use",
            """{"type":"tool_use","id":"tu_2","name":"submit_decision","input":{"call_tow":"YES","tow_reason":"주행 불가","coverage":[],"summary":"견인을 부르세요.","next_steps":["견인 요청"]}}"""))

        val client = AnthropicOkHttpClient.builder().apiKey("test-key").baseUrl(server.url("/").toString()).maxRetries(0).build()
        val insurance = InsurancePolicy("한빛손해보험", "HB-1", "1", "2", setOf(Coverage.EMERGENCY_TOW), freeTowKm = 40)
        val d = InsuranceAdvisor(ClaudeAgentModel(client)).decide(result(), null, MockInsurerApi(insurance), approve = { false })

        assertEquals(DecisionSource.AI, d.source, d.fallbackReason)
        assertEquals(TowCall.YES, d.callTow)

        val first = server.takeRequest()
        val body1 = first.body.readUtf8()
        assertEquals("/v1/messages", first.path)
        assertTrue("server-side-fallback-2026-07-01" in first.getHeader("anthropic-beta").orEmpty())
        assertTrue("\"fallbacks\":\"default\"" in body1, body1.take(300))
        assertTrue("\"model\":\"claude-opus-5-5\"" in body1)
        assertTrue("\"effort\":\"medium\"" in body1)
        assertTrue("\"strict\":true" in body1)
        assertTrue("\"additionalProperties\":false" in body1)
        assertTrue("\"tool_choice\":{\"type\":\"auto\"}" in body1)

        val body2 = server.takeRequest().body.readUtf8()
        assertTrue("\"signature\":\"sig-1\"" in body2, "생각 블록을 그대로 돌려보내야 한다")
        assertTrue("\"tool_use_id\":\"tu_1\"" in body2)
        assertTrue("\"trigger\":\"T_MANUAL\"" in body2.replace("\\\"", "\""), "사고 요약이 도구 결과로 전달된다")
    }

    @Test fun `API 오류면 규칙 판단으로 대신한다`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setHeader("content-type", "application/json")
            .setBody("""{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""))
        val client = AnthropicOkHttpClient.builder().apiKey("bad").baseUrl(server.url("/").toString()).maxRetries(0).build()
        val insurance = InsurancePolicy("한빛손해보험", "HB-1", "1", "2", setOf(Coverage.EMERGENCY_TOW), freeTowKm = 40)
        val d = InsuranceAdvisor(ClaudeAgentModel(client)).decide(result(), null, MockInsurerApi(insurance), approve = { false })
        assertEquals(DecisionSource.RULES, d.source)
        assertTrue(d.fallbackReason!!.contains("Exception"), d.fallbackReason)
    }

    @Test fun `도구 정의가 strict JSON으로 직렬화된다`() {
        val m = ClaudeAgentModel(AnthropicOkHttpClient.builder().apiKey("x").build())
        Tools.all.forEach { spec ->
            val json = jsonMapper().writeValueAsString(m.toSdk(spec))
            assertTrue("\"strict\":true" in json && "\"name\":\"${spec.name}\"" in json, json)
        }
    }
}
