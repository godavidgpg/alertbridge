package com.alertbridge.app.ui.driver

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.alertbridge.app.R
import com.alertbridge.app.ui.AppViewModel
import com.alertbridge.app.ui.UiState
import com.alertbridge.app.ui.theme.TouchMin
import com.alertbridge.engine.model.Action
import com.alertbridge.engine.model.ActionId
import com.alertbridge.engine.model.ActionKind
import com.alertbridge.engine.model.ActionStatus
import com.alertbridge.engine.model.CaseCard
import com.alertbridge.engine.model.EngineResult
import com.alertbridge.engine.model.RiskGrade
import com.alertbridge.engine.model.TimelineEntry
import com.alertbridge.engine.model.UserActionEvent
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/**
 * ④ 최종 확인. 연결 계열 조치는 모두 이 화면을 거치며 자동으로 진행하지 않는다.
 * [신고 연결]을 눌러도 "완료"라고 하지 않는다. 전화 화면을 열었다고만 말한다.
 * 문장이 길어도 버튼이 밀려나지 않도록 버튼을 아래에 고정한다.
 */
@Composable
internal fun FinalConfirmDialog(a: Action, s: UiState, vm: AppViewModel, onClose: () -> Unit, onEnterReceipt: () -> Unit) {
    var pressed by remember(a.id) { mutableStateOf(false) }
    var showOther by remember(a.id) { mutableStateOf(false) }
    val notify = a.kind == ActionKind.NOTIFY
    val number = a.contact?.number.orEmpty()

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) { com.alertbridge.app.ui.theme.Scaled {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(a.confirmTitle?.text ?: a.title.text, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(a.reason.text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    a.script?.let { script ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp)) {
                                Text(stringResource(R.string.d_confirm_script), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.height(6.dp))
                                Text(script.text, style = MaterialTheme.typography.headlineSmall)
                            }
                        }
                    }
                    if (showOther) {
                        Text(
                            when (a.id) {
                                ActionId.CALL_119 -> stringResource(R.string.d_other_119)
                                ActionId.CALL_112 -> stringResource(R.string.d_other_112)
                                else -> stringResource(R.string.d_other_default, number)
                            },
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                if (!pressed) {
                    BigButton(
                        stringResource(if (notify) R.string.d_confirm_notify else R.string.d_confirm_primary),
                        onClick = {
                            if (notify) vm.notifyGuardian(a) else vm.dial(a)
                            pressed = true
                        },
                        modifier = Modifier.fillMaxWidth().height(96.dp),
                        danger = a.grade == RiskGrade.R0,
                        fontSize = 26,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        a.script?.let { script -> BigOutlined(stringResource(R.string.d_confirm_read), { vm.speak(script.text) }, Modifier.weight(1f), fontSize = 16) }
                        if (!notify) BigOutlined(stringResource(R.string.d_confirm_other), { showOther = !showOther }, Modifier.weight(1f), fontSize = 16)
                        BigOutlined(stringResource(R.string.d_confirm_later), onClose, Modifier.weight(1f), fontSize = 16)
                    }
                } else {
                    val result = when {
                        notify -> stringResource(R.string.d_guardian_sent_note)
                        s.dialFailedNumber == number -> stringResource(R.string.d_confirm_failed, number)
                        s.testModeDialed == number -> stringResource(R.string.d_confirm_test_mode)
                        else -> stringResource(R.string.d_confirm_opened)
                    }
                    Text(result, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(10.dp))
                    if (!notify) BigButton(stringResource(R.string.d_confirm_receipt), onEnterReceipt, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    BigOutlined(stringResource(R.string.d_confirm_close), onClose, Modifier.fillMaxWidth())
                }
            }
        }
    } }
}

// ⑤ 사건 카드
@Composable
internal fun CaseCardScreen(vm: AppViewModel, s: UiState, r: EngineResult, onBack: () -> Unit) {
    val card = r.session.caseCard
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onBack, Modifier.defaultMinSize(minHeight = TouchMin)) { Text(stringResource(R.string.d_card_back)) }
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.d_case_card), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        }
        if (card == null || r.vehicleMoving) {
            // 차가 움직이면 입력·사진·QR 화면에 들어갈 수 없다.
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.d_stop_first), style = MaterialTheme.typography.titleLarge) }
            return@Column
        }
        // 가로 2단: 왼쪽은 지금 할 일(접수번호, 사진, 인적사항 QR), 오른쪽은 기록(신호, 처리 이력, 무결성).
        Row(Modifier.weight(1f).imePadding(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val place = card.location?.let { it.roadName ?: it.address } ?: stringResource(R.string.d_card_location_unknown)
                Text(place, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Section(stringResource(R.string.d_card_receipt)) { Receipt(r, card, vm) }
                Section(stringResource(R.string.d_card_photos)) { Photos(card, vm) }
                Section(stringResource(R.string.d_card_qr)) { InfoQr(s) }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Section(stringResource(R.string.d_card_signals)) { SignalGraph(card) }
                Section(stringResource(R.string.d_card_history)) { History(card, r) }
                Text(stringResource(R.string.d_card_integrity, card.frozenEventCount), style = MaterialTheme.typography.bodySmall)
                Text(card.frozenSha256, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun Receipt(r: EngineResult, card: CaseCard, vm: AppViewModel) {
    card.receiptNumbers.forEach { (id, n) ->
        Text(stringResource(R.string.d_card_receipt_saved, r.plan.find(id)?.title?.text ?: id.name, n), color = MaterialTheme.colorScheme.primary)
    }
    // 연결을 시도했지만 아직 완료되지 않은 곳에 접수번호를 받는다.
    val target = r.plan.actions.firstOrNull { it.kind == ActionKind.CONTACT && it.status == ActionStatus.CONTACT_ATTEMPTED }
    if (target == null) {
        if (card.receiptNumbers.isEmpty()) Text(stringResource(R.string.d_card_receipt_none))
        return
    }
    var number by rememberSaveable(target.id) { mutableStateOf("") }
    Text(target.title.text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedTextField(number, { number = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text(stringResource(R.string.d_card_receipt_hint)) })
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BigButton(stringResource(R.string.d_card_receipt_save), {
            vm.userAction { UserActionEvent.ReceiptNumberEntered(target.id, number, it) }
            number = ""
        }, Modifier.weight(1f), enabled = number.isNotBlank(), fontSize = 18)
        BigOutlined(stringResource(R.string.d_card_mark_done), { vm.userAction { UserActionEvent.MarkedDone(target.id, it) } }, Modifier.weight(1f), fontSize = 15)
    }
}

@Composable
private fun History(card: CaseCard, r: EngineResult) {
    if (card.actionHistory.isEmpty()) Text(stringResource(R.string.d_card_history_empty))
    card.actionHistory.forEach { h ->
        val sec = (h.tMs - card.suspicionMs) / 1000.0
        val title = r.plan.find(h.actionId)?.title?.text ?: h.actionId.name
        Text("%+.0f초  %s · %s".format(sec, title, stringResource(statusText(h.status))))
    }
}

private fun statusText(s: ActionStatus) = when (s) {
    ActionStatus.SUGGESTED -> R.string.d_status_SUGGESTED
    ActionStatus.CONTACT_ATTEMPTED -> R.string.d_status_CONTACT_ATTEMPTED
    ActionStatus.CONFIRMED -> R.string.d_status_CONFIRMED
    ActionStatus.DISMISSED -> R.string.d_status_DISMISSED
}

@Composable
private fun Photos(card: CaseCard, vm: AppViewModel) {
    card.photoChecklist.forEach { item ->
        var done by remember(item.text) { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = TouchMin), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(done, { checked ->
                if (checked && !done) vm.userAction { UserActionEvent.PhotoTaken(tMs = it) }
                done = checked
            })
            Text(item.text)
        }
    }
}

@Composable
private fun InfoQr(s: UiState) {
    val driver = s.driver
    if (driver == null) {
        Text(stringResource(R.string.d_qr_no_profile))
        return
    }
    var name by rememberSaveable { mutableStateOf(true) }
    var insurer by rememberSaveable { mutableStateOf(true) }
    var policy by rememberSaveable { mutableStateOf(false) }
    Text(stringResource(R.string.d_card_qr_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Option(stringResource(R.string.d_qr_name), name) { name = it }
            Option(stringResource(R.string.d_qr_insurer), insurer) { insurer = it }
            Option(stringResource(R.string.d_qr_policy), policy) { policy = it }
        }
        val text = listOfNotNull(
            driver.name.takeIf { name },
            driver.insurance?.insurer?.takeIf { insurer },
            driver.insurance?.policyNo?.takeIf { policy },
        ).joinToString("\n")
        if (text.isEmpty()) Text(stringResource(R.string.d_qr_empty), Modifier.width(140.dp)) else QrCode(text, Modifier.size(140.dp))
    }
}

@Composable
private fun Option(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.defaultMinSize(minHeight = TouchMin), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange)
        Text(label)
    }
}

@Composable
private fun QrCode(text: String, modifier: Modifier) {
    val m = remember(text) {
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.MARGIN to 1))
    }
    Canvas(modifier.background(Color.White)) {
        val cell = size.width / m.width
        for (y in 0 until m.height) for (x in 0 until m.width) {
            if (m.get(x, y)) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
        }
    }
}

/** 충돌 전후 속도 선, 브레이크·깜빡이 띠, 충돌 의심 시점 세로선. 값은 다음 샘플까지 유지된다. */
@Composable
private fun SignalGraph(card: CaseCard) {
    val events = card.timeline
    if (events.isEmpty()) {
        Text(stringResource(R.string.d_card_signals_empty))
        return
    }
    val speedColor = MaterialTheme.colorScheme.primary
    val brakeColor = MaterialTheme.colorScheme.error
    val turnColor = Color(0xFFE0A800)
    val markColor = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.fillMaxWidth().height(140.dp)) {
        val start = maxOf(events.minOf { it.tMs }, card.suspicionMs - 30_000)
        val end = maxOf(events.maxOf { it.tMs }, card.suspicionMs + 5_000)
        val span = (end - start).coerceAtLeast(1).toFloat()
        fun x(t: Long) = ((t - start).coerceAtLeast(0) / span) * size.width
        val band = 10.dp.toPx()
        val chartH = size.height - 2 * band - 8.dp.toPx()

        val speed = events.filter { it.channel == "SPEED" }
        val maxKmh = (speed.maxOfOrNull { it.value.toFloat() } ?: 1f).coerceAtLeast(10f)
        val path = Path()
        speed.forEachIndexed { i, e ->
            val px = x(e.tMs)
            val py = chartH * (1 - e.value.toFloat() / maxKmh)
            if (i == 0) path.moveTo(px, py) else {
                path.lineTo(px, chartH * (1 - speed[i - 1].value.toFloat() / maxKmh)) // 값 유지 후 변화
                path.lineTo(px, py)
            }
        }
        speed.lastOrNull()?.let { path.lineTo(size.width, chartH * (1 - it.value.toFloat() / maxKmh)) }
        drawPath(path, speedColor, style = Stroke(2.5.dp.toPx()))

        fun bandOf(channel: String, on: (TimelineEntry) -> Boolean, top: Float, color: Color) {
            val list = events.filter { it.channel == channel }
            list.forEachIndexed { i, e ->
                if (!on(e)) return@forEachIndexed
                val from = x(e.tMs)
                val to = list.getOrNull(i + 1)?.let { x(it.tMs) } ?: size.width
                drawRect(color, Offset(from, top), Size((to - from).coerceAtLeast(2f), band))
            }
        }
        bandOf("BRAKE", { it.value == "ON" }, chartH + 4.dp.toPx(), brakeColor)
        bandOf("TURN_SIGNAL", { it.value != "NONE" }, chartH + 8.dp.toPx() + band, turnColor)
        val mark = x(card.suspicionMs)
        drawLine(markColor, Offset(mark, 0f), Offset(mark, size.height), strokeWidth = 2.dp.toPx())
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Legend(speedColor, stringResource(R.string.d_legend_speed))
        Legend(brakeColor, stringResource(R.string.d_legend_brake))
        Legend(turnColor, stringResource(R.string.d_legend_turn))
        Legend(markColor, stringResource(R.string.d_legend_impact))
    }
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}
