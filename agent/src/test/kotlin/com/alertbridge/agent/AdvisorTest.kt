package com.alertbridge.agent

import com.alertbridge.agent.advisor.ActionRequest
import com.alertbridge.agent.advisor.AgentModel
import com.alertbridge.agent.advisor.ClaudeAgentModel
import com.alertbridge.agent.advisor.DecisionSource
import com.alertbridge.agent.advisor.InsuranceAdvisor
import com.alertbridge.agent.advisor.ModelTurn
import com.alertbridge.agent.advisor.StopKind
import com.alertbridge.agent.advisor.ToolCall
import com.alertbridge.agent.advisor.ToolOutcome
import com.alertbridge.agent.advisor.ToolSpec
import com.alertbridge.agent.advisor.Tools
import com.alertbridge.agent.advisor.TowCall
import com.alertbridge.agent.advisor.Verdict
import com.alertbridge.agent.insurance.MockInsurerApi
import com.alertbridge.engine.AlertBridgeEngine
import com.alertbridge.engine.model.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val insurance = InsurancePolicy(
    "한빛손해보험", "HB-1", "1588-0001", "1588-0002",
    setOf(Coverage.DAEMUL, Coverage.DAEIN_1, Coverage.JASIN, Coverage.EMERGENCY_TOW), freeTowKm = 40,
)

/** 주행 불가 사고: 수동 신고 + 타이어 저압 신규 */
private fun notDrivableAccident(): EngineResult {
    val e = AlertBridgeEngine.withDefaultPolicy()
    e.configure(CapabilityMap.ALL_SUPPORTED)
    e.onEvent(VehicleEvent(0, Signal.Location(37.50, 127.03, 5f, RoadType.URBAN, 0.9f, "테헤란로")))
    e.onUserAction(UserActionEvent.ManualReport(10_000))
    e.onEvent(VehicleEvent(10_500, Signal.LowTirePressure(WheelArea.LEFT_FRONT, true)))
    return e.currentResult(11_000)!!
}

/** 대본대로 답하는 가짜 모델. 받은 도구 결과를 기록한다. */
private class ScriptedModel(private val turns: List<ModelTurn>) : AgentModel {
    var index = 0
    val received = mutableListOf<List<ToolOutcome>>()
    var tools: List<ToolSpec> = emptyList()
    override fun start(system: String, userMessage: String, tools: List<ToolSpec>): ModelTurn { this.tools = tools; return turns[index++] }
    override fun continueWith(results: List<ToolOutcome>): ModelTurn { received += results; return turns[index++] }
}

private fun call(name: String, input: String = "{}", id: String = name) = ToolCall(id, name, input)
private fun turn(vararg calls: ToolCall, text: String = "") = ModelTurn(calls.toList(), text, if (calls.isEmpty()) StopKind.END_TURN else StopKind.TOOL_USE)

private const val DECISION = """{"call_tow":"YES","tow_reason":"주행 불가","coverage":[{"item":"긴급출동 견인","verdict":"COVERED","note":"무료 40km","estimated_out_of_pocket_krw":0},{"item":"자기차량손해","verdict":"CHECK_NEEDED","note":"가입 확인 필요","estimated_out_of_pocket_krw":-1}],"summary":"견인을 부르세요.","next_steps":["견인 요청","사진 찍기"]}"""

class AdvisorTest {
    private val r = notDrivableAccident()

    @Test fun `키가 없으면 규칙으로 판단하고 이유를 남긴다`() = runTest {
        val d = InsuranceAdvisor(model = null).decide(r, null, MockInsurerApi(insurance), approve = { error("승인을 묻지 않아야 한다") })
        assertEquals(DecisionSource.RULES, d.source)
        assertEquals(TowCall.YES, d.callTow, "주행 불가면 견인")
        assertNotNull(d.fallbackReason)
        assertTrue(d.disclaimer.isNotBlank())
        assertTrue(d.actionsTaken.isEmpty(), "규칙 판단은 아무것도 실행하지 않는다")
    }

    @Test fun `AI가 도구로 조회하고 승인받아 견인을 요청한 뒤 판단을 제출한다`() = runTest {
        val model = ScriptedModel(listOf(
            turn(call(Tools.GET_INCIDENT.name), call(Tools.GET_POLICY.name)),
            turn(call(Tools.CHECK_COVERAGE.name, """{"incident_type":"SINGLE_VEHICLE","anyone_injured":false,"own_car_damaged":true}""")),
            turn(call(Tools.REQUEST_TOW.name, """{"reason":"앞 타이어가 터져 운전할 수 없어요","destination":""}"""), text = "차를 옮겨야 해요."),
            turn(call(Tools.SUBMIT.name, DECISION)),
        ))
        val asked = mutableListOf<ActionRequest>()
        val d = InsuranceAdvisor(model).decide(r, null, MockInsurerApi(insurance, idSeed = 7), approve = { asked += it; true })

        assertEquals(DecisionSource.AI, d.source)
        assertEquals(TowCall.YES, d.callTow)
        assertEquals(1, asked.size)
        assertEquals("앞 타이어가 터져 운전할 수 없어요", asked.single().detail)
        assertEquals("차를 옮겨야 해요.", asked.single().rationale)
        assertEquals("TOW-7-1", d.actionsTaken.single().referenceNo)
        assertEquals(null, d.coverage[1].outOfPocketKrw, "-1은 모름(null)")
        assertEquals(Verdict.CHECK_NEEDED, d.coverage[1].verdict)
        // 같은 응답의 도구 결과는 한 번에 돌려준다.
        assertEquals(2, model.received[0].size)
        assertTrue("TERMS" !in model.received[0][1].content && "HB-1" in model.received[0][1].content)
    }

    @Test fun `사용자가 거절하면 실행하지 않고 거절을 모델에 알린다`() = runTest {
        val model = ScriptedModel(listOf(
            turn(call(Tools.FILE_CLAIM.name, """{"incident_type":"SINGLE_VEHICLE","summary":"단독 사고"}""")),
            turn(call(Tools.SUBMIT.name, DECISION)),
        ))
        val d = InsuranceAdvisor(model).decide(r, null, MockInsurerApi(insurance), approve = { false })
        assertTrue(d.actionsTaken.isEmpty())
        assertTrue("거절" in model.received.single().single().content)
    }

    @Test fun `같은 조치는 두 번 실행하지 않는다`() = runTest {
        val tow = """{"reason":"주행 불가","destination":""}"""
        val model = ScriptedModel(listOf(
            turn(call(Tools.REQUEST_TOW.name, tow, id = "a")),
            turn(call(Tools.REQUEST_TOW.name, tow, id = "b")),
            turn(call(Tools.SUBMIT.name, DECISION)),
        ))
        var approvals = 0
        val d = InsuranceAdvisor(model).decide(r, null, MockInsurerApi(insurance), approve = { approvals++; true })
        assertEquals(1, approvals)
        assertEquals(1, d.actionsTaken.size)
        assertTrue(model.received[1].single().isError)
    }

    @Test fun `거절 응답이나 잘못된 형식이면 규칙으로 대신한다`() = runTest {
        val refusal = ScriptedModel(listOf(ModelTurn(emptyList(), "", StopKind.REFUSAL)))
        assertEquals(DecisionSource.RULES, InsuranceAdvisor(refusal).decide(r, null, MockInsurerApi(insurance), { true }).source)
        val broken = ScriptedModel(listOf(turn(call(Tools.SUBMIT.name, """{"call_tow":"MAYBE"}"""))))
        assertEquals(DecisionSource.RULES, InsuranceAdvisor(broken).decide(r, null, MockInsurerApi(insurance), { true }).source)
    }

    @Test fun `모델 호출이 예외를 던지면 규칙으로 대신한다`() = runTest {
        val failing = object : AgentModel {
            override fun start(system: String, userMessage: String, tools: List<ToolSpec>): ModelTurn = throw java.io.IOException("offline")
            override fun continueWith(results: List<ToolOutcome>): ModelTurn = error("unreachable")
        }
        val d = InsuranceAdvisor(failing).decide(r, null, MockInsurerApi(insurance), { true })
        assertEquals(DecisionSource.RULES, d.source)
        assertTrue("IOException" in d.fallbackReason!!)
    }

    @Test fun `AI에게 112·119 도구는 없고 실행 도구는 둘뿐이다`() {
        assertEquals(setOf(Tools.REQUEST_TOW.name, Tools.FILE_CLAIM.name), Tools.all.filter { it.action }.map { it.name }.toSet())
        assertFalse(Tools.all.any { "119" in it.name || "112" in it.name || "call" == it.name })
    }

    @Test fun `모든 도구 스키마가 strict 조건을 만족한다`() {
        Tools.all.forEach { t ->
            assertEquals(false, (t.schema["additionalProperties"] as kotlinx.serialization.json.JsonPrimitive).content.toBoolean(), t.name)
            val props = (t.schema["properties"] as kotlinx.serialization.json.JsonObject).keys
            val req = (t.schema["required"] as kotlinx.serialization.json.JsonArray).map { it.toString().trim('"') }.toSet()
            assertEquals(props, req, t.name)
        }
    }

    @Test fun `키가 비면 모델을 만들지 않는다`() {
        assertNull(ClaudeAgentModel.fromApiKey(""))
        assertNull(ClaudeAgentModel.fromApiKey(null))
    }

    @Test fun `시스템 프롬프트에 안전 원칙이 있다`() {
        val p = InsuranceAdvisor.SYSTEM_PROMPT
        assertTrue("112/119" in p && "Do not recommend buying insurance" in p && "submit_decision" in p)
    }

    @Test fun `견인 견적은 무료 거리 초과분만 요금으로 계산한다`() = runTest {
        val api = MockInsurerApi(insurance)
        assertEquals(0, api.quoteTow(null, null, 30.0).estimatedExtraKrw)
        assertEquals(25_000, api.quoteTow(null, null, 50.0).estimatedExtraKrw)
        assertNull(api.quoteTow(null, null, null).distanceKm)
        assertEquals("협력정비소 강남점", api.quoteTow(37.4979, 127.0276, null).nearestPartnerShop)
    }

    @Test fun `견인 담보가 없으면 보험사 출동을 접수하지 못한다`() = runTest {
        val api = MockInsurerApi(insurance.copy(coverages = setOf(Coverage.DAEMUL)))
        val e = runCatching { api.requestEmergencyTow(com.alertbridge.agent.insurance.TowRequest(null, null, null, "", null)) }.exceptionOrNull()
        assertNotNull(e)
    }
}
