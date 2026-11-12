package com.alertbridge.engine

import com.alertbridge.engine.action.ActionPlanner
import com.alertbridge.engine.coverage.CoverageMatcher
import com.alertbridge.engine.damage.DamageEstimator
import com.alertbridge.engine.detect.DetectionHit
import com.alertbridge.engine.detect.IncidentDetector
import com.alertbridge.engine.detect.RingBuffer
import com.alertbridge.engine.detect.VehicleState
import com.alertbridge.engine.model.*
import com.alertbridge.engine.policy.Messages
import com.alertbridge.engine.policy.PolicyLoader
import com.alertbridge.engine.policy.PolicyTable
import com.alertbridge.engine.risk.FactBuilder
import com.alertbridge.engine.risk.IncidentContext
import com.alertbridge.engine.risk.RiskEvaluator
import com.alertbridge.engine.session.SessionMachine
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/**
 * 판단 엔진의 공개 진입점. 규칙 기반이며 시각은 항상 인자로 받는다.
 *
 * 발신 기능이 없다. 조치 계획과 문장만 만들고, 실제 연결은 UI가 사용자 확인을 받은 뒤 한다.
 * 스레드 안전하지 않으므로 한 스레드에서 시각 순서대로 호출한다.
 */
class AlertBridgeEngine(
    private val policy: PolicyTable,
    private val messages: Messages = Messages.loadDefault(),
) {
    private val t = policy.thresholds
    private val planner = ActionPlanner(messages)
    private val coverage = CoverageMatcher(messages)

    private var capabilities = CapabilityMap()
    private var driver: OccupantProfile? = null
    private var vehicle: VehicleProfile? = null

    private val buffer = RingBuffer(t.ringBufferMs)
    private val state = VehicleState(t, policy.telltaleWarnings)
    private val detector = IncidentDetector(policy, state)
    private var active: ActiveSession? = null

    /** 어댑터가 check...Capabilities 결과와 선택된 프로필을 넣는다. */
    fun configure(capabilities: CapabilityMap, driver: OccupantProfile? = null, vehicle: VehicleProfile? = null) {
        this.capabilities = capabilities
        this.driver = driver
        this.vehicle = vehicle
        detector.capabilities = capabilities
    }

    fun onEvent(e: VehicleEvent) {
        tick(e.tMs)
        // 지원된다고 확인되지 않은 신호는 근거로 쓰지 않는다.
        if (!capabilities.isSupported(e.signal.requiredCapability())) return
        buffer.add(e)
        val newWarning = state.apply(e)
        active?.takeUnless { it.machine.isTerminal }?.retain(e, t.ringBufferMs)
        detector.onEvent(e, newWarning).forEach(::handleHit)
    }

    fun onTriage(a: TriageAnswer) {
        tick(a.tMs)
        val s = active?.takeUnless { it.machine.isTerminal } ?: return
        s.answers[a.question] = a.answer
        if (a.question == TriageQ.IS_ACCIDENT) applyAccidentAnswer(s, a)
        if (!s.machine.isTerminal) refreshQuestion(s, a.tMs)
    }

    fun onUserAction(u: UserActionEvent) {
        tick(u.tMs)
        if (u is UserActionEvent.ManualReport) {
            val since = detector.lastDropSuspicionMs?.takeIf { it >= u.tMs - t.ringBufferMs } ?: u.tMs
            handleHit(DetectionHit(TriggerId.T_MANUAL, u.tMs, since))
            return
        }
        val s = active?.takeUnless { it.machine.isTerminal } ?: return
        // 화면을 조작하는 사람은 응답할 수 있는 사람이다. 119 통화 중에 무응답으로 넘어가면 안 된다.
        if (u !is UserActionEvent.GuardianNotified) s.userEngaged = true
        when (u) {
            is UserActionEvent.ManualReport -> Unit
            is UserActionEvent.SupportSelected -> if (isContact(u.actionId)) s.machine.advanceTo(SessionState.SUPPORT_SELECTED, u.tMs)
            is UserActionEvent.ContactAttempted -> {
                s.setStatus(u.actionId, ActionStatus.CONTACT_ATTEMPTED, u.tMs)
                if (isContact(u.actionId)) s.machine.advanceTo(SessionState.CONTACT_ATTEMPTED, u.tMs)
            }
            is UserActionEvent.ReceiptNumberEntered -> {
                val number = u.number.trim()
                if (number.isEmpty()) return
                s.receipts[u.actionId] = number
                confirm(s, u.actionId, u.tMs, number)
            }
            is UserActionEvent.MarkedDone -> confirm(s, u.actionId, u.tMs, null)
            is UserActionEvent.ActionDismissed -> s.setStatus(u.actionId, ActionStatus.DISMISSED, u.tMs)
            is UserActionEvent.GuardianNotified -> s.setStatus(ActionId.NOTIFY_GUARDIAN, ActionStatus.CONTACT_ATTEMPTED, u.tMs)
            is UserActionEvent.IncidentDetail -> {
                u.vehicleCount?.let { s.vehicleCount = it }
                u.pedestrianOrBicycle?.let { s.pedestrianOrBicycle = it }
            }
            is UserActionEvent.TowDestinationSet -> s.towDestinationKm = u.distanceKm
            is UserActionEvent.PhotoTaken -> s.photos += PhotoMeta(u.tMs, u.zone, u.sha256)
            is UserActionEvent.EnterRecovery -> s.machine.transition(SessionState.RECOVERY, u.tMs)
            is UserActionEvent.CloseSession -> s.machine.transition(SessionState.CLOSED, u.tMs)
            is UserActionEvent.CancelSession -> if (s.machine.state in CANCELLABLE) dismiss(s, u.tMs)
        }
        if (!s.machine.isTerminal) refreshQuestion(s, u.tMs)
    }

    /** UI는 이 결과만 보고 그린다. 사건이 없으면 null이다. */
    fun currentResult(nowMs: Long): EngineResult? {
        tick(nowMs)
        val s = active ?: return null
        val ctx = context(s, nowMs)
        val damage = DamageEstimator.estimate(ctx)

        if (s.machine.state == SessionState.DISMISSED) {
            return EngineResult(
                s.candidate, damage, RiskAssessment(emptyList()), ActionPlan(emptyList()), null,
                IncidentSession(s.machine.state, s.machine.history, s.history.toList(), null),
                null, null, state.isMoving, policy.version,
            )
        }

        val facts = FactBuilder.build(ctx, damage)
        val risk = RiskEvaluator.evaluate(policy, facts)
        val plan = planner.plan(ctx, damage, risk, facts, s.statuses)
        return EngineResult(
            candidate = s.candidate,
            damage = damage,
            risk = risk,
            plan = plan,
            coverage = coverage.match(ctx, facts),
            session = IncidentSession(s.machine.state, s.machine.history, s.history.toList(), caseCard(s, ctx, damage)),
            pendingQuestion = s.currentQuestion,
            questionDeadlineMs = s.currentQuestion?.takeUnless { s.waitsWithoutDeadline(it) }?.let { s.askedAtMs + t.noResponseTimeoutMs },
            vehicleMoving = state.isMoving,
            policyVersion = policy.version,
        )
    }

    /** 테스트용. 사건에 묶어 메모리에 잡아 둔 이벤트 수 */
    internal fun retainedEventCount(): Int = active?.retained?.size ?: 0

    private fun isContact(id: ActionId) = policy.actionDefs[id]?.kind == ActionKind.CONTACT

    private fun confirm(s: ActiveSession, id: ActionId, tMs: Long, note: String?) {
        s.setStatus(id, ActionStatus.CONFIRMED, tMs, note)
        if (isContact(id)) s.machine.advanceTo(SessionState.CONFIRMED, tMs)
    }

    private fun handleHit(hit: DetectionHit) {
        val confidence = hit.confidenceOverride ?: policy.confidenceOf(hit.trigger)
        val s = active?.takeUnless { it.machine.isTerminal }
        if (s == null) {
            val created = ActiveSession(
                candidate = IncidentCandidate(hit.trigger, confidence, kindFor(hit.trigger), hit.tMs, hit.suspicionMs, listOf(hit.trigger)),
                machine = SessionMachine(hit.tMs),
                retained = buffer.snapshot().toMutableList(),
            )
            active = created
            created.machine.transition(SessionState.SAFETY_CHECK, hit.tMs)
            if (created.candidate.kind == IncidentKind.ACCIDENT) freeze(created, hit.tMs)
            refreshQuestion(created, hit.tMs)
            return
        }

        val c = s.candidate
        val seen = (c.triggersSeen + hit.trigger).distinct()
        val upgradesFault = c.kind == IncidentKind.FAULT && hit.trigger in ACCIDENT_TRIGGERS
        val confirms = hit.trigger == TriggerId.T_AIRBAG || hit.trigger == TriggerId.T_MANUAL
        val better = policy.triggerRank(hit.trigger) < policy.triggerRank(c.trigger)
        val takeOver = upgradesFault || (better && c.kind != IncidentKind.FAULT)

        s.candidate = c.copy(
            trigger = if (takeOver) hit.trigger else c.trigger,
            confidence = if (takeOver) confidence else c.confidence,
            kind = when {
                confirms -> IncidentKind.ACCIDENT
                upgradesFault -> IncidentKind.ACCIDENT_SUSPECTED
                else -> c.kind
            },
            // 고장 흐름에서 넘어오면 이제부터가 충돌 시점이다. 그 전 경고는 사고 전부터 있던 경고다.
            suspicionMs = if (upgradesFault) hit.suspicionMs else c.suspicionMs,
            triggersSeen = seen,
        )
        if (s.candidate.kind == IncidentKind.ACCIDENT) freeze(s, hit.tMs)
        refreshQuestion(s, hit.tMs)
    }

    private fun kindFor(id: TriggerId) = when (id) {
        TriggerId.T_AIRBAG, TriggerId.T_MANUAL -> IncidentKind.ACCIDENT
        TriggerId.T_VEHICLE_WARNING -> IncidentKind.FAULT
        else -> IncidentKind.ACCIDENT_SUSPECTED
    }

    private fun applyAccidentAnswer(s: ActiveSession, a: TriageAnswer) {
        when (a.answer) {
            Answer.YES, Answer.UNSURE -> {
                // "모르겠어요"도 사고 흐름으로 안내한다. 사고가 아닌데 안내받는 쪽의 위험이 더 작다.
                s.candidate = s.candidate.copy(kind = IncidentKind.ACCIDENT)
                freeze(s, a.tMs)
            }
            Answer.NO -> when {
                // 에어백이 터졌으면 "아니오"여도 닫지 않는다.
                state.firstAirbagMs != null -> Unit
                state.newWarningsSince(s.candidate.suspicionMs).isNotEmpty() -> {
                    s.candidate = s.candidate.copy(
                        trigger = TriggerId.T_VEHICLE_WARNING,
                        confidence = policy.confidenceOf(TriggerId.T_VEHICLE_WARNING),
                        kind = IncidentKind.FAULT,
                    )
                    s.discardRecord()
                }
                else -> dismiss(s, a.tMs)
            }
            Answer.NO_RESPONSE -> freeze(s, a.tMs)
        }
    }

    private fun dismiss(s: ActiveSession, tMs: Long) {
        s.machine.transition(SessionState.DISMISSED, tMs)
        s.currentQuestion = null
        s.discardRecord()
        detector.suppressUntilMoving()
    }

    /** 사고 확정 시점에 기록을 고정한다. 앱은 이 뒤에야 저장소에 쓸 수 있다. */
    private fun freeze(s: ActiveSession, tMs: Long) {
        if (s.frozenSha256 != null) return
        val from = s.candidate.suspicionMs - t.ringBufferMs
        val frozen = s.retained.filter { it.tMs in from..tMs }
        val text = HASH_JSON.encodeToString(ListSerializer(VehicleEvent.serializer()), frozen)
        s.frozenSha256 = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }
        s.frozenAtMs = tMs
        s.frozenCount = frozen.size
    }

    private fun tick(now: Long) {
        val s = active?.takeUnless { it.machine.isTerminal } ?: return
        while (true) {
            val q = s.currentQuestion ?: return
            if (s.waitsWithoutDeadline(q)) return
            val deadline = s.askedAtMs + t.noResponseTimeoutMs
            if (now < deadline) return
            if (q == TriageQ.IS_ACCIDENT && s.candidate.kind == IncidentKind.ACCIDENT_SUSPECTED && state.isMoving) {
                // 답이 없어도 계속 운전 중이면 의식이 있다는 뜻이다. R0로 올리지 않고 닫는다.
                dismiss(s, deadline)
                return
            }
            s.answers[q] = Answer.NO_RESPONSE
            if (q == TriageQ.IS_ACCIDENT) freeze(s, deadline)
            refreshQuestion(s, deadline)
        }
    }

    private fun refreshQuestion(s: ActiveSession, tMs: Long) {
        val next = nextQuestion(s, tMs)
        if (next != s.currentQuestion) {
            s.currentQuestion = next
            s.askedAtMs = tMs
        }
    }

    private fun nextQuestion(s: ActiveSession, tMs: Long): TriageQ? {
        if (s.answers[TriageQ.IS_ACCIDENT] == Answer.NO_RESPONSE) return null
        val c = s.candidate
        val isAccident = c.kind == IncidentKind.ACCIDENT
        val roadUncertain = context(s, tMs).trustedRoad == null
        return policy.triageOrder.firstOrNull { q ->
            q !in s.answers && when (q) {
                TriageQ.IS_ACCIDENT -> c.kind == IncidentKind.ACCIDENT_SUSPECTED ||
                    (c.trigger == TriggerId.T_AIRBAG && TriggerId.T_MANUAL !in c.triggersSeen)
                TriageQ.IS_EXPRESSWAY -> c.kind != IncidentKind.ACCIDENT_SUSPECTED && roadUncertain
                TriageQ.INJURY, TriageQ.FIRE_SMOKE, TriageQ.OTHER_PARTY_FLED -> isAccident
                TriageQ.DRIVABLE -> isAccident && DamageEstimator.estimate(context(s, tMs)).severity < Severity.S2
                TriageQ.OTHER_PARTY_SUSPICIOUS -> isAccident && s.answers[TriageQ.OTHER_PARTY_FLED] != Answer.YES
            }
        }
    }

    private fun context(s: ActiveSession, nowMs: Long) = IncidentContext(
        policy, s.candidate, s.retained, state, s.answers, capabilities, driver, vehicle,
        s.vehicleCount, s.pedestrianOrBicycle, s.towDestinationKm, nowMs,
    )

    private fun caseCard(s: ActiveSession, c: IncidentContext, damage: DamageProfile): CaseCard? {
        val hash = s.frozenSha256 ?: return null
        return CaseCard(
            frozenAtMs = s.frozenAtMs,
            suspicionMs = s.candidate.suspicionMs,
            policyVersion = policy.version,
            location = state.location,
            damage = damage,
            timeline = timeline(s.retained),
            frozenEventCount = s.frozenCount,
            frozenSha256 = hash,
            actionHistory = s.history.toList(),
            receiptNumbers = s.receipts.toMap(),
            photos = s.photos.toList(),
            photoChecklist = photoChecklist(c, damage),
        )
    }

    private fun timeline(events: List<VehicleEvent>): List<TimelineEntry> = events.mapNotNull { e ->
        when (val v = e.signal) {
            is Signal.TurnSignalState -> TimelineEntry(e.tMs, "TURN_SIGNAL", v.state.name)
            is Signal.BrakeState -> TimelineEntry(e.tMs, "BRAKE", if (v.pedalPressed) "ON" else "OFF")
            is Signal.SteeringAngle -> TimelineEntry(e.tMs, "STEERING", v.degrees.toString())
            is Signal.VehicleSpeed -> TimelineEntry(e.tMs, "SPEED", v.kmh.toString())
            else -> null
        }
    }

    private fun photoChecklist(c: IncidentContext, damage: DamageProfile): List<Message> = buildList {
        add(messages.msg("photo.overview"))
        if (damage.impactZones.isEmpty()) add(messages.msg("photo.unknownZone"))
        damage.impactZones.forEach { add(messages.msg("photo.zone", "zone" to messages.text("zone.${it.zone}"))) }
        if ((c.vehicleCount ?: 0) >= 2) add(messages.msg("photo.plate"))
    }

    private class ActiveSession(
        var candidate: IncidentCandidate,
        val machine: SessionMachine,
        val retained: MutableList<VehicleEvent>,
    ) {
        val answers = mutableMapOf<TriageQ, Answer>()
        var currentQuestion: TriageQ? = null
        var askedAtMs = 0L
        var vehicleCount: Int? = null
        var pedestrianOrBicycle: Boolean? = null
        var towDestinationKm: Float? = null
        val statuses = mutableMapOf<ActionId, ActionStatus>()
        val history = mutableListOf<ActionHistoryEntry>()
        val receipts = mutableMapOf<ActionId, String>()
        val photos = mutableListOf<PhotoMeta>()
        var frozenSha256: String? = null
        var frozenAtMs = 0L
        var frozenCount = 0
        var userEngaged = false

        /** 사용자가 조작 중이면 사고 확인 질문은 시간 초과 없이 답을 기다린다. */
        fun waitsWithoutDeadline(q: TriageQ) = q == TriageQ.IS_ACCIDENT && userEngaged

        /** 충돌 의심 뒤 한 구간까지만 잡아 둔다. 오래 열린 사건이 메모리를 계속 쓰지 않도록 한다. */
        fun retain(e: VehicleEvent, windowMs: Long) {
            if (e.tMs <= candidate.suspicionMs + windowMs) retained += e
        }

        fun setStatus(id: ActionId, to: ActionStatus, tMs: Long, note: String? = null) {
            // 이미 완료한 조치는 되돌리지 않는다.
            if (statuses[id] == ActionStatus.CONFIRMED) return
            statuses[id] = to
            history += ActionHistoryEntry(tMs, id, to, note)
        }

        fun discardRecord() {
            retained.clear()
            frozenSha256 = null
            frozenCount = 0
        }
    }

    companion object {
        private val HASH_JSON = Json { encodeDefaults = true }
        private val ACCIDENT_TRIGGERS = setOf(TriggerId.T_AIRBAG, TriggerId.T_MANUAL, TriggerId.T_HARD_STOP, TriggerId.T_IMPACT, TriggerId.T_ADAS_STOP)
        private val CANCELLABLE = setOf(SessionState.CREATED, SessionState.SAFETY_CHECK, SessionState.SUPPORT_SELECTED)

        fun withDefaultPolicy(): AlertBridgeEngine {
            val m = Messages.loadDefault()
            return AlertBridgeEngine(PolicyLoader.loadDefault(m), m)
        }
    }
}
