package com.alertbridge.engine

import com.alertbridge.engine.fixture.Scenario
import com.alertbridge.engine.fixture.ScenarioJson
import com.alertbridge.engine.model.*
import com.alertbridge.engine.policy.Messages
import com.alertbridge.engine.policy.PolicyLoader
import java.io.File

object Fixtures {
    val messages: Messages = Messages.loadDefault()
    val policy = PolicyLoader.loadDefault(messages)

    private fun text(path: String) =
        checkNotNull(Fixtures::class.java.classLoader.getResourceAsStream(path)) { "없음: $path" }
            .use { it.readBytes().toString(Charsets.UTF_8) }

    val scenarios: List<Scenario> by lazy {
        val dir = File(checkNotNull(Fixtures::class.java.classLoader.getResource("scenarios")).toURI())
        dir.listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }
            .map { ScenarioJson.scenario(it.readText(Charsets.UTF_8)) }
    }

    fun profile(s: Scenario): OccupantProfile? = s.profile?.let { ScenarioJson.profile(text(it)) }
    fun scenario(idPrefix: String) = scenarios.first { it.id.startsWith(idPrefix) }
}

data class Run(val engine: AlertBridgeEngine, val result: EngineResult?, val endMs: Long)

/** 시나리오를 시각 순서대로 넣는다. 같은 시각이면 차량 신호 → 사용자 행동 → 응답 순이다. */
fun run(s: Scenario, policy: com.alertbridge.engine.policy.PolicyTable = Fixtures.policy): Run {
    val engine = AlertBridgeEngine(policy, Fixtures.messages)
    engine.configure(s.capabilityMap, Fixtures.profile(s), s.vehicle)

    data class Step(val tMs: Long, val order: Int, val apply: () -> Unit)
    val steps = s.events.map { e -> Step(e.tMs, 0) { engine.onEvent(e) } } +
        s.userActions.map { u -> Step(u.tMs, 1) { engine.onUserAction(u) } } +
        s.triage.map { a -> Step(a.tMs, 2) { engine.onTriage(a) } }
    steps.sortedWith(compareBy({ it.tMs }, { it.order })).forEach { it.apply() }

    val end = s.runUntilMs ?: steps.maxOf { it.tMs }
    return Run(engine, engine.currentResult(end), end)
}

/** 불변 조건. 시나리오 재생과 무작위 입력 양쪽에서 검사한다. 위반 내용을 돌려준다. */
fun invariantViolations(r: EngineResult, caps: CapabilityMap, confirmEventSeen: Boolean, policy: com.alertbridge.engine.policy.PolicyTable = Fixtures.policy): List<String> = buildList {
    if (RiskGrade.R0 in r.risk.has && r.plan.actions.first().id != ActionId.CALL_119) add("R0인데 첫 조치가 ${r.plan.actions.first().id}")

    r.plan.actions.filter { it.kind == ActionKind.CONTACT }.forEach {
        if (!it.needsConfirm) add("${it.id}: 연결 조치가 확인 없이 진행됨")
        if (it.autoExecutable) add("${it.id}: 연결 조치가 자동 실행 대상")
        if (it.contact?.number != null && it.contact.channel != ContactChannel.DIAL) add("${it.id}: DIAL이 아닌 연결")
    }
    r.plan.actions.filter { it.autoExecutable }.forEach { if (it.id != ActionId.NOTIFY_GUARDIAN) add("${it.id}: 자동 실행 불가 조치") }

    if (!confirmEventSeen) {
        if (r.session.history.any { it.state == SessionState.CONFIRMED }) add("완료 이벤트 없이 세션 CONFIRMED")
        if (r.plan.actions.any { it.status == ActionStatus.CONFIRMED }) add("완료 이벤트 없이 조치 CONFIRMED")
    }

    val reasons = r.risk.grades.flatMap { it.because } + r.damage.because + r.damage.impactZones.flatMap { it.because }
    reasons.distinct().forEach { code ->
        code.capabilities.filterNot(caps::isSupported).forEach { add("$code: 미지원 신호 $it 근거 사용") }
    }

    val roadConfident = r.session.caseCard?.location?.roadTypeConfidence
    if (r.plan.find(ActionId.CALL_2504) != null) {
        if (!caps.isSupported(CapabilityKey.LOCATION)) add("위치 미지원인데 2504 제안")
        if (roadConfident != null && roadConfident < policy.thresholds.roadTypeMinConfidence) add("도로 신뢰도 부족인데 2504 제안")
    }

    r.coverage?.let { if (it.disclaimer.text.isBlank()) add("면책 문구 없음") }
    r.plan.actions.map { it.id }.let { if (it.size != it.distinct().size) add("조치 중복: $it") }
    r.plan.actions.forEachIndexed { i, a -> if (a.priority != i + 1) add("우선순위 번호 어긋남: ${a.id}") }
}
