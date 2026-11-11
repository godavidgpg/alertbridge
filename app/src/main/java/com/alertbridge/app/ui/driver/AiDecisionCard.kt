package com.alertbridge.app.ui.driver

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.alertbridge.agent.advisor.ActionRequest
import com.alertbridge.agent.advisor.AdvisorDecision
import com.alertbridge.agent.advisor.DecisionSource
import com.alertbridge.agent.advisor.TowCall
import com.alertbridge.agent.advisor.Verdict
import com.alertbridge.app.R
import com.alertbridge.app.ui.AdvisorUi
import com.alertbridge.app.ui.AppViewModel
import com.alertbridge.app.ui.UiState
import com.alertbridge.app.ui.theme.AB
import com.alertbridge.app.ui.theme.IconBadge
import com.alertbridge.app.ui.theme.PrimaryAction
import com.alertbridge.app.ui.theme.SecondaryAction
import com.alertbridge.app.ui.theme.SectionCard
import com.alertbridge.app.ui.theme.SectionTitle
import com.alertbridge.app.ui.theme.Space
import com.alertbridge.app.ui.theme.StatusPill
import java.text.NumberFormat
import java.util.Locale

/** AI 보험 판단 카드. 견인 여부, 담보별 보장, 처리한 일, 이어서 할 일을 보여 준다. */
@Composable
internal fun AiDecisionCard(s: UiState, vm: AppViewModel) {
    SectionCard {
        val done = s.advisor as? AdvisorUi.Done
        SectionTitle(stringResource(R.string.ai_title)) {
            done?.let { SourcePill(it.decision.source) }
        }
        when (val a = s.advisor) {
            AdvisorUi.Idle -> {
                Text(stringResource(R.string.ai_idle_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SecondaryAction(stringResource(R.string.ai_start), vm::runAdvisor, Modifier.fillMaxWidth())
            }
            AdvisorUi.Unavailable -> Text(stringResource(R.string.ai_unavailable), style = MaterialTheme.typography.bodyMedium)
            is AdvisorUi.Running -> {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    a.step?.let { stringResource(R.string.ai_running_step, it) } ?: stringResource(R.string.ai_running),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            is AdvisorUi.Done -> Decision(a.decision, vm)
        }
    }
}

@Composable
private fun SourcePill(src: DecisionSource) {
    if (src == DecisionSource.AI) {
        StatusPill(stringResource(R.string.ai_source_ai), MaterialTheme.colorScheme.onPrimaryContainer, MaterialTheme.colorScheme.primaryContainer)
    } else {
        StatusPill(stringResource(R.string.ai_source_rules), MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.surfaceVariant)
    }
}

private fun won(v: Long) = NumberFormat.getInstance(Locale.KOREA).format(v) + "원"

@Composable
private fun Decision(d: AdvisorDecision, vm: AppViewModel) {
    val sem = AB.semantic
    // 견인 판단
    Row(verticalAlignment = Alignment.CenterVertically) {
        val (fg, bg) = when (d.callTow) {
            TowCall.YES -> sem.warning to sem.warningContainer
            TowCall.NO -> sem.success to sem.successContainer
            TowCall.UNSURE -> MaterialTheme.colorScheme.onSurfaceVariant to MaterialTheme.colorScheme.surfaceVariant
        }
        IconBadge(Icons.Filled.Build, fg, bg)
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f)) {
            Text(stringResource(when (d.callTow) { TowCall.YES -> R.string.ai_tow_YES; TowCall.NO -> R.string.ai_tow_NO; TowCall.UNSURE -> R.string.ai_tow_UNSURE }), style = MaterialTheme.typography.titleMedium)
            Text(d.towReason, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Text(d.summary, style = MaterialTheme.typography.bodyLarge)

    if (d.coverage.isNotEmpty()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(stringResource(R.string.ai_coverage), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        d.coverage.forEach { c ->
            Row(verticalAlignment = Alignment.Top) {
                val (icon, tint) = when (c.verdict) {
                    Verdict.COVERED -> Icons.Filled.CheckCircle to sem.success
                    Verdict.NOT_COVERED -> Icons.Filled.Close to MaterialTheme.colorScheme.onSurfaceVariant
                    Verdict.CHECK_NEEDED -> Icons.Filled.Info to sem.warning
                }
                Icon(icon, null, tint = tint, modifier = Modifier.padding(top = 2.dp))
                Spacer(Modifier.width(Space.s))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(c.item, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Text(
                            stringResource(when (c.verdict) { Verdict.COVERED -> R.string.ai_verdict_COVERED; Verdict.NOT_COVERED -> R.string.ai_verdict_NOT_COVERED; Verdict.CHECK_NEEDED -> R.string.ai_verdict_CHECK_NEEDED }),
                            style = MaterialTheme.typography.labelMedium, color = tint,
                        )
                    }
                    Text(c.note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    c.outOfPocketKrw?.let { Text(stringResource(R.string.ai_out_of_pocket, won(it)), style = MaterialTheme.typography.labelLarge) }
                }
            }
        }
    }

    if (d.actionsTaken.isNotEmpty()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(stringResource(R.string.ai_actions_taken), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        d.actionsTaken.forEach { t ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CheckCircle, null, tint = sem.success)
                Spacer(Modifier.width(Space.s))
                Column(Modifier.weight(1f)) {
                    Text(t.label, style = MaterialTheme.typography.titleMedium)
                    Text(t.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StatusPill(t.referenceNo, sem.success, sem.successContainer)
            }
        }
    }

    if (d.nextSteps.isNotEmpty()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(stringResource(R.string.ai_next_steps), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        d.nextSteps.forEachIndexed { i, step -> Text("${i + 1}. $step", style = MaterialTheme.typography.bodyLarge) }
    }

    d.fallbackReason?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    Text(d.disclaimer, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    TextButton(vm::runAdvisor) { Text(stringResource(R.string.ai_rerun)) }
}

/** AI가 실행하려는 조치의 승인. 닫으면 거절로 본다. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ApprovalSheet(req: ActionRequest, vm: AppViewModel) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = { vm.answerApproval(false) }, sheetState = state) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Space.l).padding(bottom = Space.l).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            Text(stringResource(R.string.approve_title, req.label), style = MaterialTheme.typography.headlineSmall)
            if (req.rationale.isNotBlank()) {
                Text(stringResource(R.string.approve_why), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(req.rationale, style = MaterialTheme.typography.bodyLarge)
            }
            if (req.detail.isNotBlank()) {
                Text(stringResource(R.string.approve_detail), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SectionCard(container = MaterialTheme.colorScheme.surfaceVariant) { Text(req.detail, style = MaterialTheme.typography.bodyLarge) }
            }
            Text(stringResource(R.string.approve_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            PrimaryAction(stringResource(R.string.approve_yes), { vm.answerApproval(true) })
            SecondaryAction(stringResource(R.string.approve_no), { vm.answerApproval(false) }, Modifier.fillMaxWidth())
        }
    }
}
