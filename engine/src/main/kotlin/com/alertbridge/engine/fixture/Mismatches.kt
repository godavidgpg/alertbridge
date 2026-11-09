package com.alertbridge.engine.fixture

import com.alertbridge.engine.model.EngineResult

/** 시나리오 기대값과 엔진 결과의 차이. 비어 있으면 일치한다. 골든 테스트와 어댑터 테스트가 함께 쓴다. */
fun Expected.mismatches(r: EngineResult): List<String> = buildList {
    fun <T> eq(name: String, want: T?, got: T) { if (want != null && want != got) add("$name: 기대 $want, 실제 $got") }
    fun must(ok: Boolean, text: String) { if (!ok) add(text) }

    val actions = r.plan.actions.map { it.id }
    val scripts = r.plan.actions.mapNotNull { it.script?.text }.joinToString(" | ")
    val zones = r.damage.impactZones.map { it.zone }

    eq("trigger", trigger, r.candidate.trigger)
    eq("severity", severity, r.damage.severity)
    eq("grades", grades, r.risk.grades.map { it.grade })
    eq("firstAction", firstAction, actions.firstOrNull())
    notFirstAction.forEach { must(actions.firstOrNull() != it, "$it 가 1순위") }
    mustContainActions.forEach { must(it in actions, "$it 없음: $actions") }
    mustNotContainActions.forEach { must(it !in actions, "$it 있으면 안 됨: $actions") }
    impactZonesContain.forEach { must(it in zones, "zone $it 없음: $zones") }
    eq("impactZonesEmpty", impactZonesEmpty, zones.isEmpty())
    eq("drivable", drivable, r.damage.drivable)
    eq("evRisk", evRisk, r.damage.evRisk)
    eq("airbag", airbag, r.damage.airbagDeployed)
    eq("photoCheck", photoCheck, r.damage.needsPhotoCheck)

    occupantsFlagged.forEach { flag ->
        val (seat, kind) = flag.split(":")
        val o = r.damage.occupants.firstOrNull { it.seat.name == seat }
        must(kind == "UNBELTED" && o?.occupied == true && o.buckled == false, "$flag 아님: $o")
    }
    occupantsUnknown.forEach { seat ->
        val o = r.damage.occupants.firstOrNull { it.seat.name == seat }
        must(o != null && o.occupied == null && o.buckled == null && o.airbagDeployed == null, "$seat 값이 알 수 없음이 아님: $o")
    }

    scriptContains.forEach { must(it in scripts, "문장에 '$it' 없음: $scripts") }
    scriptNotContains.forEach { must(it !in scripts, "문장에 '$it' 있음: $scripts") }

    eq("session", session, r.session.state)
    actionStatus.forEach { (id, st) -> eq("$id 상태", st, r.plan.find(id)?.status) }
    autoExecutable.forEach { must(r.plan.find(it)?.autoExecutable == true, "$it 자동 실행 대상 아님") }
    eq("pendingQuestion", pendingQuestion, r.pendingQuestion)

    val gaps = r.coverage?.gaps.orEmpty().joinToString(" | ") { it.message.text }
    gapContains.forEach { must(it in gaps, "gap에 '$it' 없음: $gaps") }

    val timeline = r.session.caseCard?.timeline.orEmpty().map { "${it.channel}:${it.value}" }
    timelineContains.forEach { must(it in timeline, "타임라인에 $it 없음") }
    eq("hasCaseCard", hasCaseCard, r.session.caseCard != null)
}
