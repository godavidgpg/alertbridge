package com.alertbridge.engine.action

import com.alertbridge.engine.detect.WarningKind
import com.alertbridge.engine.model.Action
import com.alertbridge.engine.model.ActionId
import com.alertbridge.engine.model.ActionKind
import com.alertbridge.engine.model.ActionPlan
import com.alertbridge.engine.model.ActionStatus
import com.alertbridge.engine.model.Answer
import com.alertbridge.engine.model.Contact
import com.alertbridge.engine.model.ContactChannel
import com.alertbridge.engine.model.DamageProfile
import com.alertbridge.engine.model.Experience
import com.alertbridge.engine.model.Message
import com.alertbridge.engine.model.RiskAssessment
import com.alertbridge.engine.model.RiskGrade
import com.alertbridge.engine.model.Tri
import com.alertbridge.engine.model.TriageQ
import com.alertbridge.engine.policy.ActionDef
import com.alertbridge.engine.policy.Messages
import com.alertbridge.engine.risk.Fact
import com.alertbridge.engine.risk.IncidentContext

class ActionPlanner(private val messages: Messages) {

    fun plan(
        c: IncidentContext,
        damage: DamageProfile,
        risk: RiskAssessment,
        facts: Set<Fact>,
        statuses: Map<ActionId, ActionStatus>,
    ): ActionPlan {
        val picked = linkedMapOf<ActionId, RiskGrade>()
        for (grade in RiskGrade.entries) {
            if (grade !in risk.has) continue
            for (step in c.policy.actionOrder[grade].orEmpty()) {
                val def = c.policy.actionDefs.getValue(step.id)
                val ok = facts.containsAll(step.`when`) && step.notAny.none { it in facts } && facts.containsAll(def.requires)
                if (ok && step.id !in picked) picked[step.id] = grade
            }
        }

        // 불변 조건: R0가 있으면 119가 1순위다. 정책 로더도 검사하지만 여기서 한 번 더 보장한다.
        val ordered = if (RiskGrade.R0 in risk.has) {
            listOf(ActionId.CALL_119 to RiskGrade.R0) + picked.filterKeys { it != ActionId.CALL_119 }.toList()
        } else picked.toList()

        val scripts = ScriptBuilder(messages, c, damage)
        return ActionPlan(ordered.mapIndexed { i, (id, grade) ->
            val def = c.policy.actionDefs.getValue(id)
            build(c, id, def, grade, i + 1, facts, scripts, statuses[id] ?: ActionStatus.SUGGESTED)
        })
    }

    private fun build(
        c: IncidentContext, id: ActionId, def: ActionDef, grade: RiskGrade, priority: Int,
        facts: Set<Fact>, scripts: ScriptBuilder, status: ActionStatus,
    ): Action {
        // 보호자 알림만 무응답 + 사전 동의일 때 확인 없이 실행될 수 있다. 기관 연결은 항상 사람이 누른다.
        val auto = def.kind == ActionKind.NOTIFY && Fact.GUARDIAN_AUTO in facts
        val reasonKey = def.reasons.first { facts.containsAll(it.`when`) }.key
        return Action(
            id = id,
            priority = priority,
            grade = grade,
            kind = def.kind,
            needsConfirm = def.kind != ActionKind.GUIDE && !auto,
            autoExecutable = auto,
            title = messages.msg("action.title.$id"),
            reason = messages.msg(reasonKey, *reasonArgs(c)),
            confirmTitle = confirmTitle(c, def),
            script = def.script?.let { scripts.build(it) },
            contact = contact(c, def),
            checklist = def.checklist.map { messages.msg(it) },
            status = status,
        )
    }

    private fun reasonArgs(c: IncidentContext): Array<Pair<String, Any?>> = arrayOf(
        "warnings" to c.newWarnings.joinToString(", ") { warningLabel(messages, it.kind, it.detail) },
        "destKm" to c.towDestinationKm?.let { "%.0f".format(it) },
        "freeKm" to c.driver?.insurance?.freeTowKm,
    )

    private fun confirmTitle(c: IncidentContext, def: ActionDef): Message? {
        val insurer = c.driver?.insurance?.insurer ?: messages.text("contact.label.insurerUnknown")
        return when (def.contact) {
            "119" -> messages.msg("confirm.title.119")
            "112" -> messages.msg("confirm.title.112")
            "2504" -> messages.msg("confirm.title.2504")
            "INSURER_EMERGENCY" -> messages.msg("confirm.title.insurerEmergency", "insurer" to insurer)
            "INSURER_CLAIM" -> messages.msg("confirm.title.insurerClaim", "insurer" to insurer)
            "GUARDIAN" -> messages.msg("confirm.title.guardian")
            else -> null
        }
    }

    private fun contact(c: IncidentContext, def: ActionDef): Contact? {
        val insurance = c.driver?.insurance
        val insurerLabel = insurance?.insurer ?: messages.text("contact.label.insurerUnknown")
        return when (def.contact) {
            "119" -> Contact(messages.text("contact.label.119"), c.policy.contacts["119"], ContactChannel.DIAL)
            "112" -> Contact(messages.text("contact.label.112"), c.policy.contacts["112"], ContactChannel.DIAL)
            "2504" -> Contact(messages.text("contact.label.2504"), c.policy.contacts["2504"], ContactChannel.DIAL)
            "INSURER_EMERGENCY" -> Contact(insurerLabel, insurance?.emergencyPhone, ContactChannel.DIAL)
            "INSURER_CLAIM" -> Contact(insurerLabel, insurance?.claimPhone, ContactChannel.DIAL)
            "GUARDIAN" -> Contact(messages.text("contact.label.guardian"), c.driver?.guardianPhone, ContactChannel.SMS_GUIDE)
            else -> null
        }
    }
}

internal fun warningLabel(m: Messages, kind: WarningKind, detail: String): String = when (kind) {
    WarningKind.FLUID -> m.text("warning.FLUID.$detail")
    WarningKind.TIRE -> m.text("warning.TIRE.$detail")
    WarningKind.POWER_DOWN -> m.text("warning.POWER_DOWN")
    WarningKind.TELLTALE -> m.text("warning.TELLTALE", "type" to detail)
}

/** 신고할 때 읽을 문장을 만든다. 알 수 없는 값은 "알 수 없음"으로 말하고 추정해 단정하지 않는다. */
class ScriptBuilder(private val m: Messages, private val c: IncidentContext, private val d: DamageProfile) {

    fun build(kind: String): Message {
        val parts: List<String> = when (kind) {
            "119" -> listOf("script.119.opening".t()) + location() + impact() + vehicleCount() + injury() + occupants() + fire() + notDrivable() + medical()
            "112" -> listOf("script.112.opening".t()) + location() + fled() + suspicious() + pedestrian() + vehicleCount() + injury()
            "2504" -> listOf("script.2504.opening".t()) + location() + notDrivable()
            "INSURER_EMERGENCY" -> listOf("script.insurerEmergency.opening".t()) + policyNo() + location() + notDrivable()
            "INSURER_CLAIM" -> listOf("script.insurerClaim.opening".t()) + policyNo() + location() + impact() + vehicleCount()
            else -> error("알 수 없는 신고 문장 종류: $kind")
        }
        return Message("script.$kind", parts.joinToString(" "), parts)
    }

    private fun String.t(vararg args: Pair<String, Any?>) = m.text(this, *args)

    private fun location(): List<String> {
        val loc = c.state.location
        val road = loc?.roadName?.let { name ->
            listOfNotNull(name, loc.direction, loc.kmPost?.let { "${it}km 지점" }).joinToString(" ")
        } ?: loc?.address
        if (road != null) return listOf("script.location.road".t("road" to road))
        // GPS만 있고 지도·주소를 못 얻었을 때. 오차가 크면 좌표를 불러 주지 않는다.
        val accuracy = loc?.accuracyM
        if (loc != null && accuracy != null && accuracy <= c.policy.thresholds.coordinateMaxAccuracyM) {
            return listOf("script.location.coords".t("lat" to "%.5f".format(java.util.Locale.ROOT, loc.lat), "lng" to "%.5f".format(java.util.Locale.ROOT, loc.lng)))
        }
        return listOf("script.location.unknown".t())
    }

    /**
     * 기관에 말하는 문장에는 확실한 위치만 넣는다. 추정이 틀리면 구조대가 잘못된 정보를 받기 때문이다.
     * 예: SDK가 에어백 종류를 주지 않으면 측면 충돌도 "앞(MEDIUM)"으로 추정된다.
     */
    private fun impact(): List<String> {
        val min = c.policy.thresholds.scriptZoneMinConfidence
        val sure = d.impactZones.filter { it.confidence <= min }
        if (sure.isEmpty()) return emptyList()
        return listOf("script.impact".t("zones" to sure.joinToString(", ") { m.text("zone.${it.zone}") }))
    }

    private fun vehicleCount() = c.vehicleCount?.let { listOf("script.vehicleCount".t("count" to it)) }.orEmpty()

    private fun injury(): List<String> = when {
        c.said(TriageQ.IS_ACCIDENT, Answer.NO_RESPONSE) -> listOf("script.injury.noResponse".t())
        c.said(TriageQ.INJURY, Answer.YES) -> listOf("script.injury.yes".t())
        c.said(TriageQ.INJURY, Answer.UNSURE) -> listOf("script.injury.unsure".t())
        else -> emptyList()
    }

    /** 일반 모드는 한 문장으로, 초보·고령 모드는 항목마다 끊어 한 문장씩 읽는다. */
    private fun occupants(): List<String> {
        val items = mutableListOf<String>()
        val occupied = d.occupants.filter { it.occupied == true }
        val occupancyKnown = d.occupants.any { it.occupied != null }
        items += if (occupancyKnown) "script.occupants.count".t("count" to occupied.size) else "script.occupants.countUnknown".t()

        when (d.airbagDeployed) {
            Tri.YES -> items += "script.occupants.airbag".t("seats" to seatNames(d.occupants.filter { it.airbagDeployed == true }.map { it.seat }))
            Tri.UNKNOWN -> items += "script.occupants.airbagUnknown".t()
            Tri.NO -> Unit
        }

        val unbelted = d.occupants.filter { it.occupied == true && it.buckled == false }.map { it.seat }
        if (unbelted.isNotEmpty()) items += "script.occupants.unbelted".t("seats" to seatNames(unbelted), "count" to unbelted.size)

        val short = c.driver?.experience.let { it == Experience.NOVICE || it == Experience.SENIOR }
        return if (short) items.map { "$it." } else listOf(items.joinToString(", ") + ".")
    }

    private fun seatNames(seats: List<com.alertbridge.engine.model.SeatArea>) = seats.joinToString(", ") { m.text("seat.$it") }

    private fun fire() = if (c.said(TriageQ.FIRE_SMOKE, Answer.YES)) listOf("script.fire".t()) else emptyList()
    private fun fled() = if (c.said(TriageQ.OTHER_PARTY_FLED, Answer.YES)) listOf("script.fled".t()) else emptyList()
    private fun suspicious() = if (c.said(TriageQ.OTHER_PARTY_SUSPICIOUS, Answer.YES)) listOf("script.suspicious".t()) else emptyList()
    private fun pedestrian() = if (c.pedestrianOrBicycle == true) listOf("script.pedestrian".t()) else emptyList()
    private fun notDrivable() = if (d.drivable == Tri.NO) listOf("script.notDrivable".t()) else emptyList()
    private fun policyNo() = c.driver?.insurance?.let { listOf("script.policyNo".t("policyNo" to it.policyNo)) }.orEmpty()

    private fun medical(): List<String> {
        val driver = c.driver ?: return emptyList()
        val note = driver.medicalNote ?: return emptyList()
        return if (driver.medicalShareConsent) listOf("script.medical".t("note" to note)) else emptyList()
    }
}
