package com.alertbridge.app.ui.driver

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.alertbridge.app.R
import com.alertbridge.app.ui.AppViewModel
import com.alertbridge.app.ui.UiState
import com.alertbridge.app.ui.questionRes
import com.alertbridge.app.ui.theme.AB
import com.alertbridge.app.ui.theme.IconBadge
import com.alertbridge.app.ui.theme.InfoRow
import com.alertbridge.app.ui.theme.PrimaryAction
import com.alertbridge.app.ui.theme.SecondaryAction
import com.alertbridge.app.ui.theme.SectionCard
import com.alertbridge.app.ui.theme.SectionTitle
import com.alertbridge.app.ui.theme.Space
import com.alertbridge.app.ui.theme.StatusPill
import com.alertbridge.app.ui.theme.Tone
import com.alertbridge.app.ui.theme.TouchMin
import com.alertbridge.engine.model.Action
import com.alertbridge.engine.model.ActionId
import com.alertbridge.engine.model.ActionKind
import com.alertbridge.engine.model.ActionStatus
import com.alertbridge.engine.model.Answer
import com.alertbridge.engine.model.Confidence
import com.alertbridge.engine.model.DamageProfile
import com.alertbridge.engine.model.EngineResult
import com.alertbridge.engine.model.Experience
import com.alertbridge.engine.model.IncidentKind
import com.alertbridge.engine.model.OccupantState
import com.alertbridge.engine.model.RiskGrade
import com.alertbridge.engine.model.SessionState
import com.alertbridge.engine.model.Side
import com.alertbridge.engine.model.Tri
import com.alertbridge.engine.model.TriageQ
import com.alertbridge.engine.model.TriggerId
import com.alertbridge.engine.model.UserActionEvent
import com.alertbridge.engine.model.Zone
import com.alertbridge.engine.model.ZoneEstimate
import kotlinx.coroutines.delay

/** 운전자 화면 ①~⑤. 어떤 화면을 띄울지는 [driverStep]이 엔진 결과로 정한다. */
@Composable
fun DriverScreen(vm: AppViewModel, s: UiState, modifier: Modifier = Modifier) {
    val r = s.result
    val step = driverStep(r)
    var confirming by remember { mutableStateOf<ActionId?>(null) }
    var cardOpen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(step) { if (step == DriverStep.IDLE) { cardOpen = false; confirming = null } }

    // 초보·고령 모드는 글자를 키운다.
    val density = LocalDensity.current
    val slow = s.driver?.experience.let { it == Experience.NOVICE || it == Experience.SENIOR }
    CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * if (slow) 1.15f else 1f)) {
        Box(modifier.background(MaterialTheme.colorScheme.background)) {
            when {
                cardOpen && r != null && step != DriverStep.IDLE -> CaseCardScreen(vm, s, r, onBack = { cardOpen = false })
                step == DriverStep.IDLE -> Idle(r, s, vm)
                step == DriverStep.VOICE_ONLY -> VoiceOnly(r!!)
                step == DriverStep.CONFIRM -> Confirm(r!!, s, vm)
                step == DriverStep.TRIAGE -> Triage(r!!, s, vm, slow)
                else -> Plan(r!!, s, vm, onConnect = { a -> vm.selectSupport(a.id); confirming = a.id }, onOpenCard = { cardOpen = true })
            }
            val action = confirming?.let { id -> r?.plan?.find(id) }
            if (action != null && step != DriverStep.IDLE) {
                FinalConfirmDialog(action, s, vm, onClose = { confirming = null }, onEnterReceipt = { confirming = null; cardOpen = true })
            }
            s.pendingApproval?.let { ApprovalSheet(it, vm) }
        }
    }
}

/** 이전 화면 코드와의 호환. 새 컴포넌트로 그린다. */
@Composable
internal fun BigButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, danger: Boolean = false, enabled: Boolean = true, @Suppress("UNUSED_PARAMETER") fontSize: Int = 22) =
    PrimaryAction(text, onClick, modifier, tone = if (danger) Tone.Danger else Tone.Primary, enabled = enabled)

@Composable
internal fun BigOutlined(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, @Suppress("UNUSED_PARAMETER") fontSize: Int = 22) =
    SecondaryAction(text, onClick, modifier, enabled = enabled)

// 대기
@Composable
private fun Idle(r: EngineResult?, s: UiState, vm: AppViewModel) {
    Column(Modifier.fillMaxSize().padding(Space.l)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.l)) {
            Spacer(Modifier.height(Space.s))
            IconBadge(Icons.Filled.CheckCircle, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer, size = 56.dp)
            Text(stringResource(R.string.d_idle_title), style = MaterialTheme.typography.headlineMedium)
            val note = when (r?.session?.state) {
                SessionState.DISMISSED -> R.string.d_idle_dismissed
                SessionState.CLOSED -> R.string.d_idle_closed
                else -> R.string.d_idle_body
            }
            Text(stringResource(note), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SectionCard {
                val loc = s.inputs.lastOrNull { it.event.signal is com.alertbridge.engine.model.Signal.Location }?.event?.signal as? com.alertbridge.engine.model.Signal.Location
                InfoRow(stringResource(R.string.idle_location), loc?.let { it.roadName ?: it.address ?: "%.4f, %.4f".format(it.lat, it.lng) } ?: stringResource(R.string.idle_location_wait))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                val vehicle = when {
                    s.scenario != null -> stringResource(R.string.idle_vehicle_demo)
                    s.liveInputs.size > 1 -> stringResource(R.string.idle_vehicle_live, s.liveInputs.drop(1).joinToString(", "))
                    else -> stringResource(R.string.idle_vehicle_none)
                }
                InfoRow(stringResource(R.string.idle_vehicle), vehicle)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                InfoRow(stringResource(R.string.idle_insurance), s.driver?.insurance?.insurer ?: stringResource(R.string.idle_insurance_none))
            }
        }
        Spacer(Modifier.height(Space.l))
        PrimaryAction(stringResource(R.string.d_manual), { vm.userAction { UserActionEvent.ManualReport(it) } }, tone = Tone.Danger, icon = Icons.Filled.Warning, height = 72.dp)
        Text(
            stringResource(R.string.idle_hint), Modifier.fillMaxWidth().padding(top = Space.s),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun VoiceOnly(r: EngineResult) {
    Column(Modifier.fillMaxSize().padding(Space.xl), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.d_voice_title), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(Space.s))
        Text(stringResource(R.string.d_voice_body), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        r.pendingQuestion?.let {
            Spacer(Modifier.height(Space.xl))
            Text(stringResource(questionRes(it)), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        }
    }
}

/** R1(2차 사고 위험)이 1순위면 질문 화면에서도 대피 안내를 먼저 보여 준다. */
@Composable
private fun EvacuateBanner(r: EngineResult) {
    val first = r.plan.first() ?: return
    if (first.grade != RiskGrade.R1) return
    Row(
        Modifier.fillMaxWidth().background(AB.semantic.warningContainer, MaterialTheme.shapes.medium).padding(Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Warning, null, tint = AB.semantic.warning)
        Spacer(Modifier.width(Space.s))
        Text(first.reason.text, color = AB.semantic.warning, style = MaterialTheme.typography.labelLarge)
    }
}

/** 남은 시간 원 */
@Composable
private fun CountdownRing(seconds: Int, total: Int, color: Color) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(96.dp)) {
        CircularProgressIndicator(
            progress = { (seconds.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.size(96.dp),
            color = color, trackColor = color.copy(alpha = 0.15f), strokeWidth = 6.dp,
        )
        Text("$seconds", style = MaterialTheme.typography.headlineMedium, color = color)
    }
}

// ① 사고 확인
@Composable
private fun Confirm(r: EngineResult, s: UiState, vm: AppViewModel) {
    val sem = AB.semantic
    Column(Modifier.fillMaxSize().background(sem.dangerContainer.copy(alpha = 0.55f)).padding(Space.l)) {
        EvacuateBanner(r)
        Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            secondsLeft(r, s.nowMs)?.let { CountdownRing(it, 30, sem.danger) }
            Spacer(Modifier.height(Space.xl))
            Text(stringResource(R.string.question_IS_ACCIDENT), style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center)
            secondsLeft(r, s.nowMs)?.let {
                Spacer(Modifier.height(Space.s))
                Text(stringResource(R.string.d_countdown_confirm, it), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
        PrimaryAction(stringResource(R.string.d_confirm_yes), { vm.answer(TriageQ.IS_ACCIDENT, Answer.YES) }, tone = Tone.Danger, height = 96.dp)
        Spacer(Modifier.height(Space.m))
        SecondaryAction(stringResource(R.string.d_confirm_no), { vm.answer(TriageQ.IS_ACCIDENT, Answer.NO) }, Modifier.fillMaxWidth(), height = 96.dp, large = true)
    }
}

// ② 트리아지
@Composable
private fun Triage(r: EngineResult, s: UiState, vm: AppViewModel, slow: Boolean) {
    val q = r.pendingQuestion ?: return
    var ready by remember(q) { mutableStateOf(!slow) }
    // 초보·고령 모드는 질문 사이 간격을 둔다. 앞 질문 버튼을 잘못 누르는 것도 막는다.
    LaunchedEffect(q) { if (slow) { delay(1200); ready = true } }
    Column(Modifier.fillMaxSize().padding(Space.l)) {
        EvacuateBanner(r)
        Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center) {
            Text(stringResource(R.string.plan_question), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(Space.s))
            Text(stringResource(questionRes(q)), style = MaterialTheme.typography.displaySmall)
            secondsLeft(r, s.nowMs)?.let {
                Spacer(Modifier.height(Space.m))
                Text(stringResource(R.string.d_countdown, it), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // "예"가 위험을 뜻할 때도, 안전을 뜻할 때도 있어 의미 색을 쓰지 않는다.
        PrimaryAction(stringResource(R.string.answer_yes), { vm.answer(q, Answer.YES) }, tone = Tone.Neutral, enabled = ready, height = 80.dp)
        Spacer(Modifier.height(Space.m))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            SecondaryAction(stringResource(R.string.answer_no), { vm.answer(q, Answer.NO) }, Modifier.weight(1f), enabled = ready, height = 80.dp, large = true)
            SecondaryAction(stringResource(R.string.answer_unsure), { vm.answer(q, Answer.UNSURE) }, Modifier.weight(1f), enabled = ready, height = 80.dp)
        }
    }
}

// ③ 대응 계획
@Composable
private fun Plan(r: EngineResult, s: UiState, vm: AppViewModel, onConnect: (Action) -> Unit, onOpenCard: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Space.l),
        verticalArrangement = Arrangement.spacedBy(Space.l),
    ) {
        if (isNoResponse(r)) NoResponseBanner(r)
        val actions = r.plan.actions
        actions.firstOrNull()?.let { first ->
            Text(stringResource(R.string.plan_first), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HeroAction(first, r, vm, { onConnect(first) }, onOpenCard)
        }
        r.pendingQuestion?.let { InlineQuestion(it, r, vm) }
        AiDecisionCard(s, vm)
        if (actions.size > 1) {
            Text(stringResource(R.string.plan_rest), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SectionCard(Modifier.padding(0.dp)) {
                actions.drop(1).forEachIndexed { i, a ->
                    if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    key(a.id) { ActionRow(a, r, vm, { onConnect(a) }, onOpenCard) }
                }
            }
        }
        if (actions.isEmpty()) Text(stringResource(R.string.d_no_actions))
        VehicleCard(r.damage, vm)
        if (r.session.caseCard != null) SecondaryAction(stringResource(R.string.d_case_card), onOpenCard, Modifier.fillMaxWidth(), enabled = !r.vehicleMoving)
        SessionButtons(r, vm)
    }
}

@Composable
private fun NumberBadge(n: Int, strong: Boolean) {
    val bg = if (strong) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (strong) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface
    Box(Modifier.size(28.dp).background(bg, CircleShape), contentAlignment = Alignment.Center) {
        Text("$n", style = MaterialTheme.typography.labelLarge, color = fg)
    }
}

private fun gradeTone(g: RiskGrade) = if (g == RiskGrade.R0) Tone.Danger else Tone.Primary

/** 1순위 조치. 가장 크게 보여 준다. */
@Composable
private fun HeroAction(a: Action, r: EngineResult, vm: AppViewModel, onConnect: () -> Unit, onOpenCard: () -> Unit) {
    val sem = AB.semantic
    val danger = a.grade == RiskGrade.R0
    SectionCard(
        container = if (danger) sem.dangerContainer else MaterialTheme.colorScheme.surface,
        border = if (danger) sem.danger.copy(alpha = 0.3f) else MaterialTheme.colorScheme.outlineVariant,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NumberBadge(a.priority, strong = true)
            Spacer(Modifier.width(Space.m))
            Text(a.title.text, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            StatusChip(a.status)
        }
        Text(a.reason.text, style = MaterialTheme.typography.bodyLarge)
        a.checklist.forEachIndexed { i, m -> Text("${i + 1}. ${m.text}", style = MaterialTheme.typography.bodyLarge) }
        ActionButtons(a, r, vm, onConnect, onOpenCard, tone = gradeTone(a.grade), hero = true)
    }
}

/** 이어서 할 일 한 줄. 누르면 펼쳐진다. */
@Composable
private fun ActionRow(a: Action, r: EngineResult, vm: AppViewModel, onConnect: () -> Unit, onOpenCard: () -> Unit) {
    // 두 번째 조치는 펼쳐 둔다. 경미 사고에서 "보험사 사고 접수"가 첫 화면에 보이게 하기 위해서다.
    var open by remember { mutableStateOf(a.priority == 2 && a.status != ActionStatus.CONFIRMED) }
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.defaultMinSize(minHeight = 56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NumberBadge(a.priority, strong = false)
            Spacer(Modifier.width(Space.m))
            Text(a.title.text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            StatusChip(a.status)
            Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AnimatedVisibility(open) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s), modifier = Modifier.padding(bottom = Space.s)) {
                Text(a.reason.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                a.checklist.forEachIndexed { i, m -> Text("${i + 1}. ${m.text}", style = MaterialTheme.typography.bodyMedium) }
                ActionButtons(a, r, vm, onConnect, onOpenCard, tone = gradeTone(a.grade), hero = false)
            }
        }
    }
}

@Composable
private fun ActionButtons(a: Action, r: EngineResult, vm: AppViewModel, onConnect: () -> Unit, onOpenCard: () -> Unit, tone: Tone, hero: Boolean) {
    val number = a.contact?.number
    when {
        a.status == ActionStatus.CONFIRMED -> Unit
        a.kind == ActionKind.CONTACT && number == null -> Text(stringResource(R.string.d_no_number), color = AB.semantic.warning, style = MaterialTheme.typography.bodyMedium)
        a.kind == ActionKind.CONTACT -> PrimaryAction(
            if (a.status == ActionStatus.CONTACT_ATTEMPTED) stringResource(R.string.d_confirm_receipt) else "${stringResource(R.string.d_connect)} · $number",
            if (a.status == ActionStatus.CONTACT_ATTEMPTED) onOpenCard else onConnect,
            tone = if (hero) tone else Tone.Neutral,
        )
        a.autoExecutable -> Text(stringResource(R.string.d_guardian_auto), color = MaterialTheme.colorScheme.onSurfaceVariant)
        a.kind == ActionKind.NOTIFY && number != null && a.status == ActionStatus.SUGGESTED -> SecondaryAction(stringResource(R.string.d_notify), onConnect, Modifier.fillMaxWidth())
        a.id == ActionId.EXCHANGE_INFO_QR || a.id == ActionId.TAKE_PHOTOS -> if (r.session.caseCard != null) {
            SecondaryAction(stringResource(R.string.d_open_card), onOpenCard, Modifier.fillMaxWidth(), enabled = !r.vehicleMoving)
        }
        a.kind == ActionKind.GUIDE -> SecondaryAction(stringResource(R.string.d_done), { vm.userAction { UserActionEvent.MarkedDone(a.id, it) } }, Modifier.fillMaxWidth())
    }
}

@Composable
private fun StatusChip(s: ActionStatus) {
    val sem = AB.semantic
    when (s) {
        ActionStatus.CONFIRMED -> StatusPill(stringResource(R.string.d_status_CONFIRMED), sem.success, sem.successContainer)
        ActionStatus.CONTACT_ATTEMPTED -> StatusPill(stringResource(R.string.d_status_CONTACT_ATTEMPTED), sem.warning, sem.warningContainer)
        ActionStatus.DISMISSED -> StatusPill(stringResource(R.string.d_status_DISMISSED), MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.surfaceVariant)
        ActionStatus.SUGGESTED -> Unit
    }
}

@Composable
private fun SessionButtons(r: EngineResult, vm: AppViewModel) {
    val early = r.session.state == SessionState.SAFETY_CHECK || r.session.state == SessionState.SUPPORT_SELECTED
    // 에어백 등으로 확정된 사고는 "사고 아님"으로 닫지 못하게 한다. 수동 신고는 실수일 수 있어 허용한다.
    val cancellable = early && (r.candidate.kind != IncidentKind.ACCIDENT || r.candidate.trigger == TriggerId.T_MANUAL)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        if (cancellable) TextButton({ vm.userAction { UserActionEvent.CancelSession(it) } }) { Text(stringResource(R.string.d_not_accident)) }
        if (!early) TextButton({ vm.userAction { UserActionEvent.CloseSession(it) } }) { Text(stringResource(R.string.d_close_session)) }
    }
}

@Composable
private fun NoResponseBanner(r: EngineResult) {
    val sem = AB.semantic
    Column(Modifier.fillMaxWidth().background(sem.danger, MaterialTheme.shapes.large).padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Text(stringResource(R.string.d_no_response_title), color = sem.onDanger, style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.d_no_response_body), color = sem.onDanger, style = MaterialTheme.typography.bodyLarge)
        val g = r.plan.find(ActionId.NOTIFY_GUARDIAN)
        if (g != null && g.status != ActionStatus.SUGGESTED) Text(stringResource(R.string.d_guardian_sent), color = sem.onDanger, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun InlineQuestion(q: TriageQ, r: EngineResult, vm: AppViewModel) {
    SectionCard(container = MaterialTheme.colorScheme.secondaryContainer, border = Color.Transparent) {
        Text(stringResource(R.string.plan_question), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(stringResource(questionRes(q)), style = MaterialTheme.typography.titleLarge)
        if (r.vehicleMoving) {
            Text(stringResource(R.string.d_stop_first))
        } else Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            SecondaryAction(stringResource(R.string.answer_yes), { vm.answer(q, Answer.YES) }, Modifier.weight(1f))
            SecondaryAction(stringResource(R.string.answer_no), { vm.answer(q, Answer.NO) }, Modifier.weight(1f))
            SecondaryAction(stringResource(R.string.answer_unsure), { vm.answer(q, Answer.UNSURE) }, Modifier.weight(1f))
        }
    }
}

private fun drivableRes(t: Tri) = when (t) {
    Tri.YES -> R.string.d_drivable_YES
    Tri.NO -> R.string.d_drivable_NO
    Tri.UNKNOWN -> R.string.d_drivable_UNKNOWN
}

private fun confidenceRes(c: Confidence) = when (c) {
    Confidence.HIGH -> R.string.d_conf_HIGH
    Confidence.MEDIUM -> R.string.d_conf_MEDIUM
    Confidence.LOW -> R.string.d_conf_LOW
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VehicleCard(d: DamageProfile, vm: AppViewModel) {
    val sem = AB.semantic
    SectionCard {
        SectionTitle(stringResource(R.string.plan_vehicle))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.l)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.d_front), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                VehicleDiagram(d.impactZones, d.occupants, Modifier.width(64.dp).height(124.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    when (d.drivable) {
                        Tri.NO -> StatusPill(stringResource(drivableRes(d.drivable)), sem.danger, sem.dangerContainer)
                        Tri.YES -> StatusPill(stringResource(drivableRes(d.drivable)), sem.success, sem.successContainer)
                        Tri.UNKNOWN -> StatusPill(stringResource(drivableRes(d.drivable)), MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.surfaceVariant)
                    }
                    if (d.evRisk) StatusPill(stringResource(R.string.d_ev_risk), sem.danger, sem.dangerContainer)
                }
                if (d.impactZones.isEmpty()) Text(stringResource(R.string.d_zone_unknown), style = MaterialTheme.typography.bodyMedium)
                d.impactZones.forEach {
                    Text(stringResource(R.string.d_zone_line, vm.messages.text("zone.${it.zone}"), stringResource(confidenceRes(it.confidence))), style = MaterialTheme.typography.bodyMedium)
                }
                d.occupants.forEach { OccupantLine(it, vm.messages.text("seat.${it.seat}")) }
            }
        }
        Text(stringResource(R.string.d_estimate_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun OccupantLine(o: OccupantState, seat: String) {
    val parts = buildList {
        if (o.occupied == null) add(stringResource(R.string.d_occupied_unknown))
        add(stringResource(when (o.buckled) { true -> R.string.d_belted; false -> R.string.d_unbelted; null -> R.string.d_belt_unknown }))
        when (o.airbagDeployed) {
            true -> add(stringResource(R.string.d_airbag))
            null -> add(stringResource(R.string.d_airbag_unknown))
            false -> Unit
        }
    }
    val alert = o.occupied == true && o.buckled == false
    Text(
        "$seat · ${parts.joinToString(", ")}",
        style = MaterialTheme.typography.bodySmall,
        color = if (alert) AB.semantic.danger else MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = if (alert) FontWeight.SemiBold else FontWeight.Normal,
    )
}

/** 위에서 본 차량. 추정 충격 위치는 신뢰도가 높을수록 진하게, 좌석 점은 벨트 상태(빨강=미착용, 테두리만=모름). */
@Composable
private fun VehicleDiagram(zones: List<ZoneEstimate>, occupants: List<OccupantState>, modifier: Modifier) {
    val body = MaterialTheme.colorScheme.surfaceVariant
    val line = MaterialTheme.colorScheme.outline
    val hit = AB.semantic.danger
    val ok = AB.semantic.success
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val r = CornerRadius(w * 0.32f)
        drawRoundRect(body, cornerRadius = r)
        zones.forEach { z ->
            val alpha = when (z.confidence) { Confidence.HIGH -> 0.85f; Confidence.MEDIUM -> 0.5f; Confidence.LOW -> 0.25f }
            val band = h * 0.2f
            val side = w * 0.3f
            val (o, s) = when (z.zone) {
                Zone.FRONT -> Offset(0f, 0f) to Size(w, band)
                Zone.REAR -> Offset(0f, h - band) to Size(w, band)
                Zone.LEFT -> Offset(0f, band) to Size(side, h - 2 * band)
                Zone.RIGHT -> Offset(w - side, band) to Size(side, h - 2 * band)
                Zone.FRONT_LEFT -> Offset(0f, 0f) to Size(w / 2, band)
                Zone.FRONT_RIGHT -> Offset(w / 2, 0f) to Size(w / 2, band)
                Zone.REAR_LEFT -> Offset(0f, h - band) to Size(w / 2, band)
                Zone.REAR_RIGHT -> Offset(w / 2, h - band) to Size(w / 2, band)
                Zone.UNDERBODY -> Offset(w * 0.2f, h * 0.3f) to Size(w * 0.6f, h * 0.4f)
            }
            drawRoundRect(hit.copy(alpha = alpha), o, s, CornerRadius(w * 0.15f))
        }
        drawRoundRect(line, cornerRadius = r, style = Stroke(1.5.dp.toPx()))
        // 앞유리
        drawRoundRect(line.copy(alpha = 0.6f), Offset(w * 0.18f, h * 0.2f), Size(w * 0.64f, h * 0.07f), CornerRadius(4.dp.toPx()))
        occupants.forEach { o ->
            val x = when (o.seat.side) { Side.LEFT -> 0.32f; Side.RIGHT -> 0.68f; Side.CENTER -> 0.5f }
            val y = when (o.seat.row) { 1 -> 0.4f; 2 -> 0.62f; else -> 0.8f }
            val c = Offset(w * x, h * y)
            val dot = 5.dp.toPx()
            when {
                o.occupied == true && o.buckled == false -> drawCircle(hit, dot, c)
                o.occupied == true -> drawCircle(ok, dot, c)
                o.occupied == null -> drawCircle(line, dot, c, style = Stroke(1.5.dp.toPx()))
            }
        }
    }
}

@Suppress("unused")
private val keepIcons = listOf(Icons.Filled.Info)
