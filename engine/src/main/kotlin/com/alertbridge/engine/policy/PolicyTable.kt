package com.alertbridge.engine.policy

import com.alertbridge.engine.model.ActionId
import com.alertbridge.engine.model.ActionKind
import com.alertbridge.engine.model.ContactChannel
import com.alertbridge.engine.model.Coverage
import com.alertbridge.engine.model.Message
import com.alertbridge.engine.model.ReasonCode
import com.alertbridge.engine.model.RiskGrade
import com.alertbridge.engine.model.RoadType
import com.alertbridge.engine.model.TriageQ
import com.alertbridge.engine.model.TriggerConfidence
import com.alertbridge.engine.model.TriggerId
import com.alertbridge.engine.model.VehicleSizeClass
import com.alertbridge.engine.risk.Fact
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** 임계값·규칙·조치 순서의 유일한 출처. 코드에는 이 표를 해석하는 부분만 있다. */
@Serializable
data class PolicyTable(
    val version: String,
    val effectiveDate: String,
    val thresholds: Thresholds,
    val triggers: List<TriggerDef>,
    val expresswayRoadTypes: Set<RoadType>,
    val tow2504RoadTypes: Set<RoadType>,
    val tow2504SizeClasses: Set<VehicleSizeClass>,
    val telltaleWarnings: Set<String>,
    val triageOrder: List<TriageQ>,
    val riskRules: List<RiskRule>,
    val actionOrder: Map<RiskGrade, List<ActionStep>>,
    val actionDefs: Map<ActionId, ActionDef>,
    val coverageRules: List<CoverageRule>,
    val contacts: Map<String, String>,
    val towFareTable: TowFareTable,
) {
    fun confidenceOf(id: TriggerId): TriggerConfidence = triggers.first { it.id == id }.confidence

    /** 값이 작을수록 우선한다. */
    fun triggerRank(id: TriggerId): Int = triggers.indexOfFirst { it.id == id }
}

@Serializable
data class Thresholds(
    val ringBufferMs: Long,
    val noResponseTimeoutMs: Long,
    val hardStopWindowMs: Long,
    val hardStopDropKmh: Float,
    val hardStopToZeroMs: Long,
    val stopSpeedKmh: Float,
    val movingSpeedKmh: Float,
    val stopHazardWindowMs: Long,
    val preImpactWindowMs: Long,
    val steeringMinDeg: Float,
    val steeringRightPositive: Boolean,
    val deltaVWindowMs: Long,
    val deltaVS2Kmh: Float,
    val roadTypeMinConfidence: Float,
    val warningBaselineMs: Long,
    val farFromHomeKm: Double,
    val evLowSocPercent: Float,
    val batteryEstimateRangePct: Int,
    val allow2504WhenSizeUnknown: Boolean,
    val coordinateMaxAccuracyM: Float,
    /** 신고 문장에 넣을 충격 위치의 최소 신뢰도 */
    val scriptZoneMinConfidence: com.alertbridge.engine.model.Confidence,
)

@Serializable data class TriggerDef(val id: TriggerId, val confidence: TriggerConfidence)

@Serializable
data class RiskRule(
    val grade: RiskGrade,
    val `when`: List<Fact>,
    val notAny: List<Fact> = emptyList(),
    val reason: ReasonCode,
)

@Serializable
data class ActionStep(val id: ActionId, val `when`: List<Fact> = emptyList(), val notAny: List<Fact> = emptyList())

@Serializable
data class ReasonVariant(val `when`: List<Fact> = emptyList(), val key: String)

@Serializable
data class ActionDef(
    val kind: ActionKind,
    val channel: ContactChannel,
    /** 119, 112, 2504, INSURER_EMERGENCY, INSURER_CLAIM, GUARDIAN */
    val contact: String? = null,
    val script: String? = null,
    /** 이 사실이 모두 참일 때만 계획에 들어간다. */
    val requires: List<Fact> = emptyList(),
    val reasons: List<ReasonVariant>,
    val checklist: List<String> = emptyList(),
)

@Serializable
data class CoverageRule(
    val `when`: List<Fact>,
    val claim: List<Coverage> = emptyList(),
    val reasonKey: String,
    val gapIfMissing: Coverage? = null,
    val gapKey: String? = null,
    val estimate: String? = null,
)

@Serializable data class TowFareTable(val source: String, val asOf: String, val rows: List<Map<String, String>> = emptyList())

class PolicyError(message: String) : IllegalStateException(message)

object PolicyLoader {
    private val json = Json { ignoreUnknownKeys = false }

    fun parse(text: String): PolicyTable = json.decodeFromString(PolicyTable.serializer(), text)

    fun loadDefault(messages: Messages = Messages.loadDefault()): PolicyTable =
        validated(parse(resource("policy/policy_v1.json")), messages)

    /** 안전 원칙을 깨는 정책은 불러오지 않는다. 불러온 뒤 실행 중에 깨지는 일이 없도록 여기서 막는다. */
    fun validated(p: PolicyTable, messages: Messages): PolicyTable {
        fun fail(m: String): Nothing = throw PolicyError("policy ${p.version}: $m")

        if (p.actionOrder[RiskGrade.R0]?.firstOrNull()?.id != ActionId.CALL_119) fail("R0의 첫 조치는 CALL_119여야 한다")
        if (ActionId.CALL_119 !in p.actionDefs) fail("CALL_119 정의가 없다")
        val tow = p.actionDefs[ActionId.CALL_2504] ?: fail("CALL_2504 정의가 없다")
        if (Fact.ELIGIBLE_2504 !in tow.requires) fail("CALL_2504는 ELIGIBLE_2504를 요구해야 한다")
        if (p.thresholds.roadTypeMinConfidence <= 0f) fail("roadTypeMinConfidence는 0보다 커야 한다")
        TriggerId.entries.forEach { id -> if (p.triggers.none { it.id == id }) fail("트리거 $id 정의가 없다") }

        p.actionOrder.values.flatten().forEach { step ->
            if (step.id !in p.actionDefs) fail("${step.id} 정의가 없다")
        }
        p.actionDefs.forEach { (id, def) ->
            if (def.reasons.isEmpty() || def.reasons.last().`when`.isNotEmpty()) fail("$id: 마지막 이유 문구는 조건이 없어야 한다")
            (def.reasons.map { it.key } + def.checklist + "action.title.$id").forEach { key ->
                if (key !in messages) fail("$id: 메시지 키 $key 가 없다")
            }
            if (def.kind == ActionKind.CONTACT && def.channel != ContactChannel.DIAL) fail("$id: 연결 조치는 DIAL만 쓴다")
        }
        p.coverageRules.forEach { r ->
            (listOfNotNull(r.reasonKey, r.gapKey)).forEach { if (it !in messages) fail("메시지 키 $it 가 없다") }
        }
        return p
    }

    internal fun resource(path: String): String =
        (PolicyLoader::class.java.classLoader.getResourceAsStream(path) ?: error("리소스 없음: $path"))
            .use { it.readBytes().toString(Charsets.UTF_8) }
}

/** 메시지 키 → 기본 한국어 문장 테이블. `{name}` 자리에 값을 넣는다. */
class Messages(private val table: Map<String, String>) {
    operator fun contains(key: String) = key in table

    val keys: Set<String> get() = table.keys

    fun text(key: String, vararg args: Pair<String, Any?>): String {
        var s = table[key] ?: throw IllegalArgumentException("메시지 키 없음: $key")
        args.forEach { (k, v) -> s = s.replace("{$k}", v?.toString() ?: "") }
        return s
    }

    fun msg(key: String, vararg args: Pair<String, Any?>): Message = Message(key, text(key, *args))

    companion object {
        fun loadDefault(): Messages {
            val serializer = MapSerializer(String.serializer(), String.serializer())
            return Messages(Json.decodeFromString(serializer, PolicyLoader.resource("messages_ko.json")))
        }
    }
}
