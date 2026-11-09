package com.alertbridge.engine.fixture

import com.alertbridge.engine.model.ActionId
import com.alertbridge.engine.model.ActionStatus
import com.alertbridge.engine.model.CapabilityKey
import com.alertbridge.engine.model.CapabilityMap
import com.alertbridge.engine.model.OccupantProfile
import com.alertbridge.engine.model.RiskGrade
import com.alertbridge.engine.model.SessionState
import com.alertbridge.engine.model.Severity
import com.alertbridge.engine.model.Tri
import com.alertbridge.engine.model.TriageAnswer
import com.alertbridge.engine.model.TriageQ
import com.alertbridge.engine.model.TriggerId
import com.alertbridge.engine.model.UserActionEvent
import com.alertbridge.engine.model.VehicleEvent
import com.alertbridge.engine.model.VehicleProfile
import com.alertbridge.engine.model.Zone
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 시나리오 JSON 형식. 골든 테스트와 앱의 FixtureSignalSource가 함께 쓴다. */
@Serializable
data class Scenario(
    val id: String,
    val description: String,
    val capabilities: Map<CapabilityKey, Boolean?>,
    val profile: String? = null,
    val vehicle: VehicleProfile? = null,
    val events: List<VehicleEvent>,
    val triage: List<TriageAnswer> = emptyList(),
    val userActions: List<UserActionEvent> = emptyList(),
    /** 시간 경과로 생기는 결과(무응답 등)를 볼 시각. 없으면 마지막 입력 시각 */
    val runUntilMs: Long? = null,
    val expected: Expected,
) {
    val capabilityMap get() = CapabilityMap(capabilities)
}

/** 값이 null인 항목은 검사하지 않는다. */
@Serializable
data class Expected(
    val trigger: TriggerId? = null,
    val severity: Severity? = null,
    val grades: List<RiskGrade>? = null,
    val firstAction: ActionId? = null,
    val notFirstAction: List<ActionId> = emptyList(),
    val mustContainActions: List<ActionId> = emptyList(),
    val mustNotContainActions: List<ActionId> = emptyList(),
    val impactZonesContain: List<Zone> = emptyList(),
    val impactZonesEmpty: Boolean? = null,
    val drivable: Tri? = null,
    val evRisk: Boolean? = null,
    val airbag: Tri? = null,
    val occupantsFlagged: List<String> = emptyList(),
    val occupantsUnknown: List<String> = emptyList(),
    val scriptContains: List<String> = emptyList(),
    val scriptNotContains: List<String> = emptyList(),
    val session: SessionState? = null,
    val actionStatus: Map<ActionId, ActionStatus> = emptyMap(),
    val autoExecutable: List<ActionId> = emptyList(),
    val gapContains: List<String> = emptyList(),
    val pendingQuestion: TriageQ? = null,
    val timelineContains: List<String> = emptyList(),
    val hasCaseCard: Boolean? = null,
    val photoCheck: Boolean? = null,
)

object ScenarioJson {
    val json = Json { ignoreUnknownKeys = false; explicitNulls = false }

    fun scenario(text: String): Scenario = json.decodeFromString(Scenario.serializer(), text)
    fun profile(text: String): OccupantProfile = json.decodeFromString(OccupantProfile.serializer(), text)
}
