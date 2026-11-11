package com.alertbridge.agent.advisor

import com.alertbridge.agent.insurance.ClaimRequest
import com.alertbridge.agent.insurance.CoverageRequest
import com.alertbridge.agent.insurance.IncidentType
import com.alertbridge.agent.insurance.InsurerApi
import com.alertbridge.agent.insurance.TowRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * 도구 정의. [action]이 참이면 실제 조치(견인 출동, 사고 접수)라서 사용자 승인 후에만 실행된다.
 * 112·119 연결 도구는 일부러 두지 않는다. 인명 관련 연결은 규칙 엔진과 사람의 확인이 맡는다.
 */
data class ToolSpec(
    val name: String,
    val description: String,
    /** JSON Schema (object). strict 도구 사용을 위해 additionalProperties=false, 모든 속성 required */
    val schema: JsonObject,
    val action: Boolean,
    /** 진행 표시·승인 화면에 보일 한국어 이름 */
    val label: String,
)

object Tools {
    private fun obj(vararg props: Pair<String, JsonObject>) = buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(props.toMap()))
        put("required", JsonArray(props.map { JsonPrimitive(it.first) }))
        put("additionalProperties", false)
    }
    private fun str(desc: String) = buildJsonObject { put("type", "string"); put("description", desc) }
    private fun bool(desc: String) = buildJsonObject { put("type", "boolean"); put("description", desc) }
    private fun num(desc: String) = buildJsonObject { put("type", "number"); put("description", desc) }
    private fun enumOf(desc: String, values: List<String>) = buildJsonObject {
        put("type", "string"); put("description", desc); put("enum", JsonArray(values.map { JsonPrimitive(it) }))
    }

    val GET_INCIDENT = ToolSpec(
        "get_incident_summary",
        "규칙 엔진이 차량 센서로 판단한 사고 요약을 돌려준다. 다른 도구보다 먼저 호출한다.",
        obj(), action = false, label = "사고 정보 확인",
    )
    val GET_POLICY = ToolSpec(
        "get_insurance_policy",
        "운전자가 가입한 자동차보험의 담보 목록, 한도, 자기부담금, 무료 견인 거리를 보험사 API에서 가져온다.",
        obj(), action = false, label = "가입 보험 조회",
    )
    val CHECK_COVERAGE = ToolSpec(
        "check_coverage",
        "이번 사고 유형에서 어떤 담보로 보상받을 수 있는지 보험사 API에 확인한다.",
        obj(
            "incident_type" to enumOf("사고 유형", IncidentType.entries.map { it.name }),
            "anyone_injured" to bool("다친 사람이 있는지"),
            "own_car_damaged" to bool("내 차가 손상됐는지"),
        ),
        action = false, label = "보장 여부 확인",
    )
    val QUOTE_TOW = ToolSpec(
        "quote_tow",
        "가장 가까운 협력 정비소까지의 거리와 무료 견인 거리 초과 요금을 계산한다. 목적지 거리를 모르면 -1을 넣는다.",
        obj("destination_km" to num("원하는 목적지까지 거리(km). 모르면 -1")),
        action = false, label = "견인 거리·요금 계산",
    )
    val REQUEST_TOW = ToolSpec(
        "request_emergency_tow",
        "보험사 긴급출동 견인을 실제로 접수한다. 사용자 승인 후에만 실행된다. 주행 불가이거나 2차 사고 위험으로 차를 옮겨야 할 때만 쓴다.",
        obj(
            "reason" to str("견인이 필요한 이유 (사용자에게 그대로 보여 준다)"),
            "destination" to str("목적지. 모르면 빈 문자열"),
        ),
        action = true, label = "긴급출동 견인 요청",
    )
    val FILE_CLAIM = ToolSpec(
        "file_accident_claim",
        "보험사에 사고를 접수한다. 사용자 승인 후에만 실행된다. 보상받을 담보가 있을 때만 쓴다.",
        obj(
            "incident_type" to enumOf("사고 유형", IncidentType.entries.map { it.name }),
            "summary" to str("보험사에 전달할 사고 요약 (한국어, 2~3문장)"),
        ),
        action = true, label = "보험 사고 접수",
    )
    val SUBMIT = ToolSpec(
        "submit_decision",
        "최종 판단을 제출한다. 반드시 마지막에 한 번 호출한다.",
        obj(
            "call_tow" to enumOf("보험사 견인을 부를지", listOf("YES", "NO", "UNSURE")),
            "tow_reason" to str("견인 판단 이유 (한국어 한두 문장)"),
            "coverage" to buildJsonObject {
                put("type", "array")
                put("description", "담보별 보장 판단")
                put("items", obj(
                    "item" to str("담보 이름 (한국어)"),
                    "verdict" to enumOf("판단", listOf("COVERED", "NOT_COVERED", "CHECK_NEEDED")),
                    "note" to str("근거 (한국어 한 문장)"),
                    "estimated_out_of_pocket_krw" to num("예상 본인 부담(원). 모르면 -1"),
                ))
            },
            "summary" to str("운전자에게 보여 줄 결론 (한국어 2문장 이내)"),
            "next_steps" to buildJsonObject {
                put("type", "array"); put("description", "운전자가 할 일, 순서대로 (한국어 짧은 문장)"); put("items", str("할 일"))
            },
        ),
        action = false, label = "판단 정리",
    )

    val all = listOf(GET_INCIDENT, GET_POLICY, CHECK_COVERAGE, QUOTE_TOW, REQUEST_TOW, FILE_CLAIM, SUBMIT)
    fun byName(name: String) = all.firstOrNull { it.name == name }
}

/** 도구 실행. 보험사 API와 사고 요약에 연결한다. 결과는 모델에게 돌려줄 JSON 문자열이다. */
class ToolExecutor(
    private val brief: IncidentBrief,
    private val insurer: InsurerApi,
) {
    private val json = Json { encodeDefaults = true }

    suspend fun run(name: String, inputJson: String): String {
        val input = runCatching { Json.parseToJsonElement(inputJson).jsonObject }.getOrElse { JsonObject(emptyMap()) }
        return when (name) {
            Tools.GET_INCIDENT.name -> json.encodeToString(IncidentBrief.serializer(), brief)
            Tools.GET_POLICY.name -> json.encodeToString(com.alertbridge.agent.insurance.PolicyInfo.serializer(), insurer.getPolicy())
            Tools.CHECK_COVERAGE.name -> json.encodeToString(
                com.alertbridge.agent.insurance.CoverageCheck.serializer(),
                insurer.checkCoverage(
                    CoverageRequest(incidentType(input), input.bool("anyone_injured"), input.bool("own_car_damaged"))
                ),
            )
            Tools.QUOTE_TOW.name -> {
                val dest = input["destination_km"]?.jsonPrimitive?.doubleOrNull?.takeIf { it >= 0 }
                json.encodeToString(
                    com.alertbridge.agent.insurance.TowQuote.serializer(),
                    insurer.quoteTow(brief.location?.lat, brief.location?.lng, dest),
                )
            }
            Tools.REQUEST_TOW.name -> json.encodeToString(
                com.alertbridge.agent.insurance.DispatchResult.serializer(),
                insurer.requestEmergencyTow(
                    TowRequest(
                        brief.location?.lat, brief.location?.lng, brief.location?.address ?: brief.location?.roadName,
                        input.str("reason"), input.str("destination").ifBlank { null },
                    )
                ),
            )
            Tools.FILE_CLAIM.name -> json.encodeToString(
                com.alertbridge.agent.insurance.ClaimResult.serializer(),
                insurer.fileClaim(ClaimRequest(incidentType(input), input.str("summary"), brief.location?.address)),
            )
            else -> throw IllegalArgumentException("알 수 없는 도구: $name")
        }
    }

    private fun incidentType(o: JsonObject) =
        o.str("incident_type").let { v -> IncidentType.entries.firstOrNull { it.name == v } } ?: IncidentType.SINGLE_VEHICLE

    companion object {
        /** submit_decision 입력을 결과로 바꾼다. 형식이 틀리면 null */
        fun parseDecision(inputJson: String): AdvisorDecision? = runCatching {
            val o = Json.parseToJsonElement(inputJson).jsonObject
            AdvisorDecision(
                source = DecisionSource.AI,
                callTow = TowCall.valueOf(o.str("call_tow")),
                towReason = o.str("tow_reason"),
                coverage = o["coverage"]!!.jsonArray.map { e ->
                    val c = e.jsonObject
                    CoverageVerdict(
                        item = c.str("item"),
                        verdict = Verdict.valueOf(c.str("verdict")),
                        note = c.str("note"),
                        outOfPocketKrw = c["estimated_out_of_pocket_krw"]?.jsonPrimitive?.doubleOrNull?.toLong()?.takeIf { it >= 0 },
                    )
                },
                summary = o.str("summary"),
                nextSteps = o["next_steps"]!!.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull },
            )
        }.getOrNull()

        /** 승인 화면에 보일 조치 설명 */
        fun describe(spec: ToolSpec, inputJson: String): String {
            val o = runCatching { Json.parseToJsonElement(inputJson).jsonObject }.getOrElse { JsonObject(emptyMap()) }
            return when (spec.name) {
                Tools.REQUEST_TOW.name -> listOf(o.str("reason"), o.str("destination").takeIf { it.isNotBlank() }?.let { "목적지: $it" })
                    .filterNotNull().filter { it.isNotBlank() }.joinToString("\n")
                Tools.FILE_CLAIM.name -> o.str("summary")
                else -> ""
            }
        }

        private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
        private fun JsonObject.bool(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull ?: false
        @Suppress("unused") private fun JsonElement.long() = (this as? JsonPrimitive)?.longOrNull
    }
}
