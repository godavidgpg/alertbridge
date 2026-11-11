package com.alertbridge.app.ui.policy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.alertbridge.agent.insurance.coverageName
import com.alertbridge.app.R
import com.alertbridge.app.ui.theme.AB
import com.alertbridge.app.ui.theme.PrimaryAction
import com.alertbridge.app.ui.theme.Space
import com.alertbridge.engine.model.Coverage
import com.alertbridge.engine.model.DeductibleRule
import com.alertbridge.engine.model.InsuredCarClass
import com.alertbridge.engine.model.InsurancePolicy
import com.alertbridge.engine.model.VehicleUse

/** 등록 화면에 보여 줄 담보. 전기차 특약 등은 보험사 연동으로 받는다 */
private val EDITABLE = listOf(
    Coverage.DAEIN_1, Coverage.DAEIN_2, Coverage.DAEMUL, Coverage.JASIN, Coverage.JASANG,
    Coverage.JACHA, Coverage.MUBOHEOM, Coverage.EMERGENCY_TOW,
)
private val DAEMUL_LIMITS = listOf(20_000_000L, 100_000_000L, 200_000_000L, 300_000_000L, 500_000_000L, 1_000_000_000L)
private val RATES = listOf(10, 20, 30)
private val MINS = listOf(200_000L, 300_000L, 500_000L)
private val MAXES = listOf(500_000L, 1_000_000L)
private val SURCHARGES = listOf(500_000L, 1_000_000L, 1_500_000L, 2_000_000L)
private val TOW_KM = listOf(10, 40, 60, 100)

private fun man(v: Long): String = when {
    v >= 100_000_000 -> "${v / 100_000_000}억"
    else -> "%,d만".format(v / 10_000)
}

/** 가입할 때 보험 정보를 등록한다. [initial]이 데모 값이면 [isDemo]로 알려 준다. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PolicySheet(initial: InsurancePolicy?, isDemo: Boolean, canDelete: Boolean, onSave: (InsurancePolicy?) -> Unit, onClose: () -> Unit) {
    var insurer by remember { mutableStateOf(initial?.insurer.orEmpty()) }
    var product by remember { mutableStateOf(initial?.productName.orEmpty()) }
    var policyNo by remember { mutableStateOf(initial?.policyNo.orEmpty()) }
    var emergency by remember { mutableStateOf(initial?.emergencyPhone.orEmpty()) }
    var claim by remember { mutableStateOf(initial?.claimPhone.orEmpty()) }
    var use by remember { mutableStateOf(initial?.vehicleUse ?: VehicleUse.PERSONAL) }
    var carClass by remember { mutableStateOf(initial?.carClass?.takeIf { it != InsuredCarClass.UNKNOWN } ?: InsuredCarClass.MID) }
    var coverages by remember { mutableStateOf(initial?.coverages ?: setOf(Coverage.DAEIN_1, Coverage.DAEIN_2, Coverage.DAEMUL)) }
    var daemul by remember { mutableStateOf(initial?.daemulLimitKrw ?: 200_000_000L) }
    val ded = initial?.jachaDeductible ?: DeductibleRule(20, 200_000, 500_000)
    var rate by remember { mutableStateOf(ded.ratePct) }
    var min by remember { mutableStateOf(ded.minKrw) }
    var max by remember { mutableStateOf(ded.maxKrw) }
    var surcharge by remember { mutableStateOf(initial?.propertySurchargeThresholdKrw ?: 2_000_000L) }
    var towKm by remember { mutableStateOf(initial?.freeTowKm ?: 40) }
    var showError by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), sheetMaxWidth = com.alertbridge.app.ui.theme.SheetMaxWidth) { com.alertbridge.app.ui.theme.Scaled {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Space.l).padding(bottom = Space.l).imePadding().navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Space.l),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                Text(stringResource(R.string.pol_title), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.pol_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (isDemo) Text(stringResource(R.string.pol_demo_prefill), style = MaterialTheme.typography.labelLarge, color = AB.semantic.warning)
            }

            Group(stringResource(R.string.pol_basic)) {
                Field(stringResource(R.string.pol_insurer), insurer) { insurer = it }
                Field(stringResource(R.string.pol_product), product) { product = it }
                Field(stringResource(R.string.pol_policy_no), policyNo) { policyNo = it }
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    Field(stringResource(R.string.pol_emergency_phone), emergency, Modifier.weight(1f), phone = true) { emergency = it }
                    Field(stringResource(R.string.pol_claim_phone), claim, Modifier.weight(1f), phone = true) { claim = it }
                }
            }

            Group(stringResource(R.string.pol_use)) {
                Segments(VehicleUse.entries, use, { useLabel(it) }) { use = it }
            }
            Group(stringResource(R.string.pol_car_class), help = stringResource(R.string.pol_class_help)) {
                Segments(InsuredCarClass.entries - InsuredCarClass.UNKNOWN, carClass, { classLabel(it) }) { carClass = it }
            }

            Group(stringResource(R.string.pol_coverages)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    EDITABLE.forEach { c ->
                        val on = c in coverages
                        FilterChip(
                            selected = on, onClick = { coverages = if (on) coverages - c else coverages + c },
                            label = { Text(coverageName(c)) },
                            leadingIcon = if (on) ({ Icon(Icons.Filled.Check, null) }) else null,
                        )
                    }
                }
            }

            if (Coverage.DAEMUL in coverages) Group(stringResource(R.string.pol_daemul_limit)) {
                Choices(DAEMUL_LIMITS, daemul, { man(it) + "원" }) { daemul = it }
            }
            if (Coverage.JACHA in coverages) Group(stringResource(R.string.pol_jacha)) {
                Labeled(stringResource(R.string.pol_jacha_rate)) { Choices(RATES, rate, { "$it%" }) { rate = it } }
                Labeled(stringResource(R.string.pol_jacha_min)) { Choices(MINS, min, { man(it) + "원" }) { min = it } }
                Labeled(stringResource(R.string.pol_jacha_max)) { Choices(MAXES, max, { man(it) + "원" }) { max = it } }
            }
            Group(stringResource(R.string.pol_surcharge), help = stringResource(R.string.pol_surcharge_help)) {
                Choices(SURCHARGES, surcharge, { man(it) + "원" }) { surcharge = it }
            }
            if (Coverage.EMERGENCY_TOW in coverages) Group(stringResource(R.string.pol_free_tow)) {
                Choices(TOW_KM, towKm, { "${it}km" }) { towKm = it }
            }

            if (showError) Text(stringResource(R.string.pol_need_insurer), style = MaterialTheme.typography.bodyMedium, color = AB.semantic.danger)
            PrimaryAction(stringResource(R.string.pol_save), {
                if (insurer.isBlank() || emergency.isBlank()) { showError = true; return@PrimaryAction }
                onSave(
                    InsurancePolicy(
                        insurer = insurer.trim(), policyNo = policyNo.trim(), emergencyPhone = emergency.trim(),
                        claimPhone = claim.trim().ifBlank { emergency.trim() },
                        // 등록 화면에 없는 담보(전기차 특약 등)는 그대로 둔다.
                        coverages = coverages + initial?.coverages.orEmpty().filter { it !in EDITABLE },
                        freeTowKm = towKm.takeIf { Coverage.EMERGENCY_TOW in coverages },
                        evBattery = initial?.evBattery,
                        productName = product.trim().ifBlank { null },
                        vehicleUse = use, carClass = carClass,
                        daemulLimitKrw = daemul.takeIf { Coverage.DAEMUL in coverages },
                        jachaDeductible = DeductibleRule(rate, min, max).takeIf { Coverage.JACHA in coverages },
                        propertySurchargeThresholdKrw = surcharge,
                    )
                )
                onClose()
            })
            if (canDelete) TextButton({ onSave(null); onClose() }, Modifier.align(Alignment.CenterHorizontally)) {
                Text(stringResource(R.string.pol_delete), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    } }
}

@Composable
private fun useLabel(u: VehicleUse) = stringResource(when (u) {
    VehicleUse.PERSONAL -> R.string.pol_use_PERSONAL
    VehicleUse.BUSINESS -> R.string.pol_use_BUSINESS
    VehicleUse.COMMERCIAL -> R.string.pol_use_COMMERCIAL
})

@Composable
internal fun classLabel(c: InsuredCarClass) = when (c) {
    InsuredCarClass.SMALL -> stringResource(R.string.pol_class_SMALL)
    InsuredCarClass.MID -> stringResource(R.string.pol_class_MID)
    InsuredCarClass.LARGE -> stringResource(R.string.pol_class_LARGE)
    InsuredCarClass.MULTI -> stringResource(R.string.pol_class_MULTI)
    InsuredCarClass.UNKNOWN -> ""
}

@Composable
private fun Group(title: String, help: String? = null, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (help != null) Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

@Composable
private fun Labeled(label: String, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = Space.xs))
        content()
    }
}

@Composable
private fun Field(label: String, value: String, modifier: Modifier = Modifier.fillMaxWidth(), phone: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, onChange, modifier, label = { Text(label) }, singleLine = true,
        keyboardOptions = if (phone) KeyboardOptions(keyboardType = KeyboardType.Phone) else KeyboardOptions.Default,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Segments(items: List<T>, selected: T, label: @Composable (T) -> String, onPick: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        items.forEachIndexed { i, item ->
            SegmentedButton(selected = item == selected, onClick = { onPick(item) }, shape = SegmentedButtonDefaults.itemShape(i, items.size)) {
                Text(label(item), maxLines = 1)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Choices(items: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        items.forEach { item -> FilterChip(selected = item == selected, onClick = { onPick(item) }, label = { Text(label(item)) }) }
    }
}

