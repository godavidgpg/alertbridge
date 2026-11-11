package com.alertbridge.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alertbridge.app.R
import com.alertbridge.engine.fixture.Expected
import com.alertbridge.engine.model.Action
import com.alertbridge.engine.model.ActionId
import com.alertbridge.engine.model.ActionKind
import com.alertbridge.engine.model.ActionStatus
import com.alertbridge.engine.model.Answer
import com.alertbridge.engine.model.EngineResult
import com.alertbridge.engine.model.ReasonCode
import com.alertbridge.engine.model.RiskGrade
import com.alertbridge.engine.model.SessionState
import com.alertbridge.engine.model.Signal
import com.alertbridge.engine.model.UserActionEvent

private val Pass = Color(0xFF1B7F4B)
private val Fail = Color(0xFFC62828)

fun gradeColor(g: RiskGrade) = when (g) {
    RiskGrade.R0 -> Color(0xFFC62828)
    RiskGrade.R1 -> Color(0xFFD9730D)
    RiskGrade.R2 -> Color(0xFF5E4DB2)
    RiskGrade.R3 -> Color(0xFF8A6D00)
    RiskGrade.R4 -> Color(0xFF00796B)
}

/** 판단 파이프라인을 위에서 아래로 그대로 보여 준다. 판단은 엔진이 하고 여기서는 표시만 한다. */
@Composable
fun LogicScreen(vm: AppViewModel, s: UiState, modifier: Modifier = Modifier) {
    val r = s.result
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        s.scenario?.let { Section(stringResource(R.string.sec_expected)) { ExpectedRows(it.expected, r) } }
        if (r == null) {
            Body(stringResource(R.string.no_incident))
        } else {
            Section(stringResource(R.string.sec_detect)) { Detect(r) }
            Section(stringResource(R.string.sec_question)) { Question(r, s, vm) }
            Section(stringResource(R.string.sec_damage)) { Damage(r, vm) }
            Section(stringResource(R.string.sec_risk)) { Risk(r, vm) }
            Section(stringResource(R.string.sec_plan)) { Plan(r, s, vm) }
            r.coverage?.let { Section(stringResource(R.string.sec_coverage)) { Coverage(r) } }
            Section(stringResource(R.string.sec_session)) { Session(r, vm) }
            Section("7 AI 보험 판단") { AiRaw(s) }
        }
        Section(stringResource(R.string.sec_input)) { Inputs(s) }
        if (s.scenario != null) Section(stringResource(R.string.sec_pleos)) { PleosCalls(s) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
/** 입력 선택(실시간/시나리오)과 재생 조작. [compact]면 운전자 화면용으로 줄여 보여 준다. */
fun ScenarioControls(s: UiState, vm: AppViewModel, onLive: () -> Unit, compact: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(s.scenario == null, onLive, { Text(stringResource(R.string.mode_live)) })
            vm.scenarios.forEach { sc ->
                FilterChip(s.scenario?.id == sc.id, { vm.start(sc) }, { Text(sc.id.take(3)) })
            }
        }
        if (compact) return@Column
        Body(
            s.scenario?.description ?: stringResource(if (s.locationDenied) R.string.live_no_permission else R.string.live_description),
        )
        if (s.scenario == null && s.liveInputs.isNotEmpty()) Mono(stringResource(R.string.live_inputs, s.liveInputs.joinToString(", ")))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.Center) {
            if (s.scenario != null) {
                TextButton(vm::togglePlay) { Text(stringResource(if (s.playing) R.string.pause else R.string.play)) }
                listOf(1f, 4f, 8f).forEach { v ->
                    FilterChip(s.speed == v, { vm.setSpeed(v) }, { Text("${v.toInt()}x") })
                }
            }
            TextButton(vm::restart) { Text(stringResource(R.string.restart)) }
            if (s.result == null) {
                TextButton({ vm.userAction { UserActionEvent.ManualReport(it) } }) { Text(stringResource(R.string.manual_report)) }
            }
        }
        if (s.scenario != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Body(stringResource(R.string.auto_answers), Modifier.weight(1f))
                Switch(s.autoAnswers, vm::setAutoAnswers)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Body(stringResource(R.string.use_real_gps), Modifier.weight(1f))
                Switch(s.useRealGps, vm::setUseRealGps)
            }
            Mono(stringResource(R.string.clock, s.nowMs / 1000.0))
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.primary)
        content()
    }
}

@Composable
private fun Body(text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    Text(text, modifier, fontSize = 14.sp, color = color)
}

@Composable
private fun Mono(text: String, color: Color = Color.Unspecified) {
    Text(text, fontSize = 12.sp, fontFamily = FontFamily.Monospace, lineHeight = 15.sp, color = color)
}

@Composable
private fun Kv(k: String, v: String) = Mono("$k = $v")

@Composable
private fun ExpectedRows(x: Expected, r: EngineResult?) {
    fun row(name: String, want: Any?, got: Any?) = if (want == null) null else Triple(name, want.toString(), got.toString())
    listOfNotNull(
        row("trigger", x.trigger, r?.candidate?.trigger),
        row("severity", x.severity, r?.damage?.severity),
        row("grades", x.grades, r?.risk?.grades?.map { it.grade }),
        row("firstAction", x.firstAction, r?.plan?.first()?.id),
        row("drivable", x.drivable, r?.damage?.drivable),
        row("session", x.session, r?.session?.state),
    ).forEach { (name, want, got) ->
        val ok = want == got
        Mono("${if (ok) "✓" else "…"} $name: 기대 $want / 현재 $got", if (ok) Pass else Fail)
    }
}

@Composable
private fun Inputs(s: UiState) {
    if (s.inputs.isEmpty()) Body(stringResource(R.string.none))
    s.inputs.takeLast(14).forEach { row ->
        val t = if (s.scenario != null) "%.1f".format(row.event.tMs / 1000.0) else "%tT".format(row.event.tMs)
        val sig = row.event.signal
        val brief = if (sig is Signal.Location) {
            "Location %.5f,%.5f ±%sm road=%s(%.1f) %s".format(sig.lat, sig.lng, sig.accuracyM ?: "?", sig.roadType, sig.roadTypeConfidence, sig.roadName ?: sig.address ?: "")
        } else sig.toString()
        val text = "$t  $brief"
        if (row.used) Mono(text) else Mono("$text  ← ${stringResource(R.string.input_ignored)}", Color.Gray)
    }
}

@Composable
private fun PleosCalls(s: UiState) {
    if (s.pleosCalls.isEmpty()) Body(stringResource(R.string.none))
    s.pleosCalls.takeLast(14).forEach { Mono(it) }
}

@Composable
private fun Detect(r: EngineResult) {
    val c = r.candidate
    Kv("trigger", "${c.trigger} (${c.confidence})")
    Kv("kind", c.kind.name)
    Kv("suspicionMs", c.suspicionMs.toString())
    Kv("triggersSeen", c.triggersSeen.joinToString())
}

@Composable
private fun Question(r: EngineResult, s: UiState, vm: AppViewModel) {
    val q = r.pendingQuestion
    if (q == null) {
        Body(stringResource(R.string.none))
        return
    }
    Text(stringResource(questionRes(q)), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    val deadline = r.questionDeadlineMs
    Body(
        if (deadline != null) stringResource(R.string.deadline, ((deadline - s.nowMs).coerceAtLeast(0) / 1000).toInt())
        else stringResource(R.string.no_deadline)
    )
    s.lastSpoken?.let { Mono(stringResource(R.string.tts, it)) }
    if (r.vehicleMoving) {
        Body(stringResource(R.string.stop_first))
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ vm.answer(q, Answer.YES) }, Modifier.weight(1f)) { Text(stringResource(R.string.answer_yes)) }
            OutlinedButton({ vm.answer(q, Answer.NO) }, Modifier.weight(1f)) { Text(stringResource(R.string.answer_no)) }
            OutlinedButton({ vm.answer(q, Answer.UNSURE) }, Modifier.weight(1f)) { Text(stringResource(R.string.answer_unsure), fontSize = 12.sp) }
        }
    }
}

@Composable
private fun Reasons(codes: List<ReasonCode>, vm: AppViewModel) {
    codes.forEach { Mono("  $it: ${vm.messages.text("reasoncode.$it")}") }
}

@Composable
private fun Damage(r: EngineResult, vm: AppViewModel) {
    val d = r.damage
    Kv("severity", d.severity.name)
    Kv("drivable", d.drivable.name)
    Kv("airbag", d.airbagDeployed.name)
    Kv("deltaV", d.deltaVKmh?.let { "%.1f km/h".format(it) } ?: "null (알 수 없음)")
    Kv("evRisk", d.evRisk.toString())
    Kv("needsPhotoCheck", d.needsPhotoCheck.toString())
    Reasons(d.because, vm)
    d.impactZones.forEach { z ->
        Mono("zone ${z.zone} ${z.confidence} (${vm.messages.text("zone.${z.zone}")}) ← ${z.because.joinToString()}")
    }
    d.occupants.forEach { o ->
        val alert = o.occupied == true && o.buckled == false
        Mono(
            "${vm.messages.text("seat.${o.seat}")}: occupied=${o.occupied} buckled=${o.buckled} airbag=${o.airbagDeployed}",
            if (alert) Fail else Color.Unspecified,
        )
    }
    d.evidence.forEach { Mono("근거 t=${it.tMs} ${it.signal} ${it.detail}", Color.Gray) }
}

@Composable
private fun Risk(r: EngineResult, vm: AppViewModel) {
    if (r.risk.grades.isEmpty()) Body(stringResource(R.string.none))
    r.risk.grades.forEach { g ->
        Text(g.grade.name, fontWeight = FontWeight.Bold, color = gradeColor(g.grade), fontSize = 15.sp)
        Reasons(g.because, vm)
    }
    Mono("정책 ${r.policyVersion}", Color.Gray)
}

@Composable
private fun Plan(r: EngineResult, s: UiState, vm: AppViewModel) {
    var confirming by remember { mutableStateOf<ActionId?>(null) }
    if (r.plan.actions.isEmpty()) Body(stringResource(R.string.none))
    r.plan.actions.forEach { a ->
        ActionRow(a, r, vm, testMode = s.testModeDialed != null, onConnect = {
            vm.selectSupport(a.id)
            confirming = a.id
        })
    }
    s.dialFailedNumber?.let { Body(stringResource(R.string.dial_failed, it), color = Fail) }
    s.testModeDialed?.let { Body(stringResource(R.string.dial_test_mode, it), color = Fail) }
    confirming?.let { id -> r.plan.find(id) }?.let { a ->
        ConfirmDialog(a, vm, onClose = { confirming = null })
    }
}

@Composable
private fun ActionRow(a: Action, r: EngineResult, vm: AppViewModel, testMode: Boolean, onConnect: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("${a.priority}. ${a.title.text}", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = gradeColor(a.grade))
        Mono("${a.id} / ${a.grade} / ${a.status} / needsConfirm=${a.needsConfirm}")
        Body(a.reason.text)
        a.checklist.forEach { Body("· ${it.text}") }
        a.script?.let { Mono("문장: ${it.text}") }
        val number = a.contact?.number
        when {
            a.autoExecutable -> Mono(stringResource(R.string.auto_guardian), Color.Gray)
            a.kind == ActionKind.GUIDE -> if (a.status != ActionStatus.CONFIRMED) {
                TextButton({ vm.userAction { UserActionEvent.MarkedDone(a.id, it) } }) { Text(stringResource(R.string.done)) }
            }
            number == null -> Mono(stringResource(R.string.no_number), Color.Gray)
            a.status == ActionStatus.CONTACT_ATTEMPTED && a.kind == ActionKind.CONTACT -> {
                Body(stringResource(if (testMode) R.string.dial_opened_test else R.string.dial_opened))
                if (!r.vehicleMoving) ReceiptInput(a, vm)
            }
            a.status == ActionStatus.SUGGESTED -> OutlinedButton(onConnect) {
                Text("${stringResource(if (a.kind == ActionKind.NOTIFY) R.string.notify else R.string.connect)} ($number)")
            }
        }
    }
}

/** 연결 계열 조치는 모두 이 확인을 거친다. 눌러도 "완료"가 되지 않으며 전화 화면만 연다. */
@Composable
private fun ConfirmDialog(a: Action, vm: AppViewModel, onClose: () -> Unit) {
    val notify = a.kind == ActionKind.NOTIFY
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(a.confirmTitle?.text ?: a.title.text) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(a.reason.text)
                a.script?.let { Text(it.text, fontSize = 18.sp) }
            }
        },
        confirmButton = {
            Button({
                if (notify) vm.notifyGuardian(a) else vm.dial(a)
                onClose()
            }) { Text(stringResource(if (notify) R.string.confirm_notify else R.string.confirm_primary)) }
        },
        dismissButton = { TextButton(onClose) { Text(stringResource(R.string.confirm_later)) } },
    )
}

@Composable
private fun ReceiptInput(a: Action, vm: AppViewModel) {
    var number by remember(a.id) { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(number, { number = it }, Modifier.weight(1f), singleLine = true, label = { Text(stringResource(R.string.receipt_hint)) })
        Button({ vm.userAction { UserActionEvent.ReceiptNumberEntered(a.id, number, it) } }, enabled = number.isNotBlank()) {
            Text(stringResource(R.string.receipt_save))
        }
    }
    TextButton({ vm.userAction { UserActionEvent.MarkedDone(a.id, it) } }) { Text(stringResource(R.string.mark_done)) }
}

@Composable
private fun Coverage(r: EngineResult) {
    val m = r.coverage ?: return
    m.claim.forEach { Body("청구 후보 ${it.coverage}: ${it.reason.text}") }
    m.gaps.forEach { Body("확인 필요 ${it.coverage}: ${it.message.text}") }
    m.towNote?.let { Body(it.text) }
    Body(m.disclaimer.text, color = Color.Gray)
}

@Composable
private fun Session(r: EngineResult, vm: AppViewModel) {
    Kv("state", r.session.state.name)
    Kv("history", r.session.history.joinToString(" → ") { it.state.name })
    r.session.actionHistory.forEach { Mono("t=${it.tMs} ${it.actionId} → ${it.status}${it.note?.let { n -> " ($n)" } ?: ""}") }
    val card = r.session.caseCard
    if (card == null) {
        Body(stringResource(R.string.case_not_frozen))
    } else {
        Kv("frozenEvents", card.frozenEventCount.toString())
        Kv("sha256", card.frozenSha256)
        Kv("location", card.location?.let { it.roadName ?: it.address ?: "%.5f, %.5f".format(it.lat, it.lng) } ?: "null")
        Kv("receipts", card.receiptNumbers.toString())
        card.timeline.filter { it.channel != "SPEED" }.takeLast(8).forEach { Mono("t=${it.tMs} ${it.channel}:${it.value}", Color.Gray) }
        card.photoChecklist.forEach { Body("· ${it.text}") }
    }
    if (r.session.state != SessionState.CLOSED && r.session.state != SessionState.DISMISSED) {
        TextButton({ vm.userAction { UserActionEvent.CloseSession(it) } }) { Text("CloseSession") }
    }
}

/** AI 판단 원본. 판단 출처(AI/규칙)와 실패 이유까지 그대로 보여 준다. */
@Composable
private fun AiRaw(s: UiState) {
    when (val a = s.advisor) {
        is AdvisorUi.Done -> {
            val d = a.decision
            Kv("source", d.source.name)
            Kv("callTow", d.callTow.name)
            Kv("towReason", d.towReason)
            d.coverage.forEach { Mono("coverage ${it.item}: ${it.verdict} ${it.outOfPocketKrw ?: ""} - ${it.note}") }
            d.actionsTaken.forEach { Mono("taken ${it.tool} #${it.referenceNo}") }
            d.fallbackReason?.let { Kv("fallback", it) }
        }
        else -> Kv("state", a.toString())
    }
    s.pendingApproval?.let { Kv("pendingApproval", it.tool) }
}
