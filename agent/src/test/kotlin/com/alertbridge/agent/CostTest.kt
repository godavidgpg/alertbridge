package com.alertbridge.agent

import com.alertbridge.agent.advisor.AgentModel
import com.alertbridge.agent.advisor.InsuranceAdvisor
import com.alertbridge.agent.advisor.ModelTurn
import com.alertbridge.agent.advisor.StopKind
import com.alertbridge.agent.advisor.ToolCall
import com.alertbridge.agent.advisor.ToolOutcome
import com.alertbridge.agent.advisor.ToolSpec
import com.alertbridge.agent.advisor.Tools
import com.alertbridge.agent.insurance.CostEstimator
import com.alertbridge.agent.insurance.CostRequest
import com.alertbridge.agent.insurance.CostService
import com.alertbridge.agent.insurance.IncidentType
import com.alertbridge.agent.insurance.MockInsurerApi
import com.alertbridge.agent.stats.ClaimStats
import com.alertbridge.agent.stats.DataGoKrClaimStats
import com.alertbridge.agent.stats.SnapshotClaimStats
import com.alertbridge.agent.stats.StatCoverage
import com.alertbridge.agent.stats.StatRow
import com.alertbridge.agent.stats.StatTable
import com.alertbridge.engine.AlertBridgeEngine
import com.alertbridge.engine.model.*
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Test
import java.time.YearMonth
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val registered = InsurancePolicy(
    "한빛손해보험", "HB-2", "1588-0001", "1588-0002",
    setOf(Coverage.DAEIN_1, Coverage.DAEIN_2, Coverage.DAEMUL, Coverage.JACHA, Coverage.EMERGENCY_TOW), freeTowKm = 40,
    productName = "개인용 자동차보험", vehicleUse = VehicleUse.PERSONAL, carClass = InsuredCarClass.MID,
    daemulLimitKrw = 1_000_000_000, jachaDeductible = DeductibleRule(20, 200_000, 500_000),
    propertySurchargeThresholdKrw = 2_000_000,
)

/** 중형 자차 평균 250만 원, 소형 150만 원, 중형 대물 200만 원 */
private val table = StatTable(
    listOf(
        StatRow("202608", "개인용", "자기차량손해", "중형", 2_500_000_000, 1_000),
        StatRow("202608", "개인용", "자기차량손해", "소형", 1_500_000_000, 1_000),
        StatRow("202608", "개인용", "대물배상", "중형", 2_000_000_000, 1_000),
        StatRow("202607", "개인용", "자기차량손해", "중형", 9_999_000_000, 1_000), // 지난달은 쓰지 않는다
        StatRow("202608", "영업용", "자기차량손해", "중형", 9_000_000_000, 1_000),
    ),
    live = true,
)

private object FixedStats : ClaimStats { override suspend fun table() = table }

class CostTest {
    @Test fun `평균 손해액에 등록한 자기부담금 조건을 적용한다`() {
        val e = CostEstimator.estimate(registered, CostRequest(IncidentType.SINGLE_VEHICLE, true, false, false), table)
        val jacha = e.lines.single()
        assertEquals("자기차량손해", jacha.coverageName)
        assertEquals(2_500_000, jacha.typicalLossKrw, "최근 달, 개인용 중형만")
        assertEquals(500_000, jacha.outOfPocketKrw, "20%는 50만 원 → 최대 50만 원")
        assertEquals(500_000, jacha.outOfPocketMaxKrw)
        assertEquals("개인용 중형 · 2026년 1~8월 누계", e.basis)
        assertTrue(e.surcharge!!.likelyExceeds, "250만 원 > 할증 기준 200만 원")
    }

    @Test fun `최소 자기부담금과 차종 미등록`() {
        val small = registered.copy(carClass = InsuredCarClass.SMALL, jachaDeductible = DeductibleRule(20, 200_000, 500_000))
        val e = CostEstimator.estimate(small, CostRequest(IncidentType.SINGLE_VEHICLE, true, false, false), table)
        assertEquals(300_000, e.lines.single().outOfPocketKrw, "150만 원의 20%")

        val unknown = registered.copy(carClass = InsuredCarClass.UNKNOWN)
        val u = CostEstimator.estimate(unknown, CostRequest(IncidentType.SINGLE_VEHICLE, true, false, false), table)
        assertEquals(2_000_000, u.lines.single().typicalLossKrw, "소형+중형 합산 평균")
        assertTrue(u.basis!!.contains("전체 차종"))
    }

    @Test fun `차대차 사고는 자차와 대물을 합쳐 할증 기준과 비교한다`() {
        val e = CostEstimator.estimate(registered, CostRequest(IncidentType.COLLISION_WITH_VEHICLE, true, true, false), table)
        assertEquals(listOf("자기차량손해", "대물배상"), e.lines.map { it.coverageName })
        assertEquals(0, e.lines[1].outOfPocketKrw, "대물은 한도 안")
        val s = assertNotNull(e.surcharge)
        assertEquals(4_500_000, s.expectedPropertyLossKrw)
        assertTrue(s.likelyExceeds)
    }

    @Test fun `자차 미가입이면 평균 수리비를 본인 부담으로 보고, 고장은 계산하지 않는다`() {
        val noJacha = registered.copy(coverages = registered.coverages - Coverage.JACHA)
        val e = CostEstimator.estimate(noJacha, CostRequest(IncidentType.SINGLE_VEHICLE, true, false, false), table)
        assertFalse(e.lines.single().held)
        assertEquals(2_500_000, e.lines.single().outOfPocketKrw)
        assertNull(e.surcharge, "보험으로 처리하지 않으면 할증 비교도 없다")

        val fault = CostEstimator.estimate(registered, CostRequest(IncidentType.BREAKDOWN, true, false, false), table)
        assertTrue(fault.lines.isEmpty())
        assertNull(fault.basis)
    }

    @Test fun `내장 통계를 읽을 수 있다`() = runTest {
        val t = SnapshotClaimStats.table()
        assertFalse(t.live)
        val s = assertNotNull(t.average(StatCoverage.JACHA, VehicleUse.PERSONAL, InsuredCarClass.MID))
        assertTrue(s.avgLossKrw in 1_000_000..5_000_000, "개인용 중형 자차 건당 평균이 상식 범위: ${s.avgLossKrw}")
    }

    @Test fun `공공데이터 API 응답을 읽고, 실패하면 내장 통계로 대신한다`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(
            """{"response":{"header":{"resultCode":"00","resultMsg":"NORMAL SERVICE."},"body":{"numOfRows":1000,"pageNo":1,"totalCount":2,"items":{"item":[
            {"isuCmpyOfrYm":"202609","isuItmsNm":"개인용","mogClsfNm":"자기차량손해","kncrNm":"중형","losAmt":"3000000","injPtlCnt":"1","dthTotlCnt":"0"},
            {"isuCmpyOfrYm":"202609","isuItmsNm":"개인용","mogClsfNm":"대물배상","kncrNm":"중형","losAmt":"2000000","injPtlCnt":"1","dthTotlCnt":"0"}]}}}}"""
        ))
        server.start()
        val api = DataGoKrClaimStats("KEY+/=", baseUrl = server.url("/svc").toString().trimEnd('/'), now = { YearMonth.of(2026, 11) })
        val t = api.table()
        assertTrue(t.live)
        assertEquals(3_000_000, t.average(StatCoverage.JACHA, VehicleUse.PERSONAL, InsuredCarClass.MID)!!.avgLossKrw)
        val req = server.takeRequest()
        assertEquals("/svc/getLosCircumstance", req.requestUrl!!.encodedPath)
        assertEquals("KEY+/=", req.requestUrl!!.queryParameter("serviceKey"), "키는 URL 인코딩해서 보낸다")
        assertEquals("2026", req.requestUrl!!.queryParameter("likeIsuCmpyOfrYm"))
        api.table()
        assertEquals(1, server.requestCount, "한 번 받으면 다시 받지 않는다")

        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"response":{"header":{"resultCode":"30","resultMsg":"SERVICE_KEY_IS_NOT_REGISTERED_ERROR"}}}"""))
        val bad = DataGoKrClaimStats("BAD", baseUrl = server.url("/svc").toString().trimEnd('/'))
        val fb = bad.table()
        assertFalse(fb.live, "실패하면 내장 통계")
        assertNotNull(bad.lastError)
        server.shutdown()
    }

    @Test fun `AI 판단의 담보별 금액은 코드가 계산한 값으로 맞춘다`() = runTest {
        val e = AlertBridgeEngine.withDefaultPolicy()
        e.configure(CapabilityMap.ALL_SUPPORTED)
        e.onEvent(VehicleEvent(0, Signal.Location(37.50, 127.03, 5f, RoadType.URBAN, 0.9f, "테헤란로")))
        e.onUserAction(UserActionEvent.ManualReport(10_000))
        val r = e.currentResult(11_000)!!

        val est = """{"incident_type":"COLLISION_WITH_VEHICLE","own_car_damaged":true,"other_property_damaged":true,"anyone_injured":false}"""
        val submit = """{"call_tow":"NO","tow_reason":"운전 가능","coverage":[
            {"item":"자기차량손해","verdict":"COVERED","note":"자기부담금","estimated_out_of_pocket_krw":123},
            {"item":"긴급출동 견인","verdict":"COVERED","note":"무료","estimated_out_of_pocket_krw":777}],
            "summary":"자차로 처리돼요.","next_steps":["사고 접수"]}"""
        val received = mutableListOf<List<ToolOutcome>>()
        val turns = ArrayDeque(listOf(
            ModelTurn(listOf(ToolCall("1", Tools.ESTIMATE_COSTS.name, est)), "", StopKind.TOOL_USE),
            ModelTurn(listOf(ToolCall("2", Tools.SUBMIT.name, submit)), "", StopKind.TOOL_USE),
        ))
        var captured: List<ToolSpec> = emptyList()
        val model = object : AgentModel {
            override fun start(system: String, userMessage: String, tools: List<ToolSpec>): ModelTurn { captured = tools; return turns.removeFirst() }
            override fun continueWith(results: List<ToolOutcome>): ModelTurn { received += results; return turns.removeFirst() }
        }
        val d = InsuranceAdvisor(model).decide(r, null, MockInsurerApi(registered), approve = { false }, costs = CostService(registered, FixedStats))
        assertTrue(Tools.ESTIMATE_COSTS in captured, "비용 계산이 가능하면 도구를 준다")
        assertFalse(received.single().single().isError, received.toString())
        assertEquals(500_000, d.coverage.first { it.item == "자기차량손해" }.outOfPocketKrw, "AI가 적은 123원 대신 계산값")
        assertNull(d.coverage.first { it.item == "긴급출동 견인" }.outOfPocketKrw, "계산하지 않은 금액은 보이지 않는다")
        assertEquals(true, d.costEstimate?.surcharge?.likelyExceeds)

        // 등록 정보가 없으면 도구를 주지 않는다.
        assertFalse(Tools.ESTIMATE_COSTS in Tools.forSession(costs = false))
    }

    @Test fun `규칙 판단에도 예상 비용이 붙는다`() = runTest {
        val e = AlertBridgeEngine.withDefaultPolicy()
        e.configure(CapabilityMap.ALL_SUPPORTED)
        e.onEvent(VehicleEvent(0, Signal.Location(37.50, 127.03, 5f, RoadType.URBAN, 0.9f, "테헤란로")))
        e.onUserAction(UserActionEvent.ManualReport(10_000))
        val d = InsuranceAdvisor(model = null).decide(e.currentResult(11_000)!!, null, MockInsurerApi(registered), approve = { false }, costs = CostService(registered, FixedStats))
        val jacha = d.coverage.first { it.item == "자기차량손해" }
        assertEquals(500_000, jacha.outOfPocketKrw)
        assertNotNull(d.costEstimate)
    }

}
