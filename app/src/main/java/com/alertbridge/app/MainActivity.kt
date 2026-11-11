package com.alertbridge.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alertbridge.app.adapter.CarSpeedSource
import com.alertbridge.app.ui.AppViewModel
import com.alertbridge.app.ui.LogicScreen
import com.alertbridge.app.ui.ScenarioControls
import com.alertbridge.app.ui.UiState
import com.alertbridge.app.ui.driver.DriverScreen
import com.alertbridge.app.ui.theme.AlertBridgeTheme
import com.alertbridge.app.ui.theme.Space

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    // 권한 응답은 늦게 올 수 있다. 그사이 고른 시나리오를 덮어쓰지 않도록 실시간 모드일 때만 다시 시작한다.
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (vm.state.value.scenario == null) vm.startLive()
    }

    private fun missing(): List<String> {
        val hasLocation = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return buildList {
            if (!hasLocation) { add(Manifest.permission.ACCESS_FINE_LOCATION); add(Manifest.permission.ACCESS_COARSE_LOCATION) }
            // 차속 권한은 차량용 Android에서만 요청한다.
            if (packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE) &&
                checkSelfPermission(CarSpeedSource.SPEED_PERMISSION) != PackageManager.PERMISSION_GRANTED
            ) add(CarSpeedSource.SPEED_PERMISSION)
        }
    }

    private fun requestIfNeeded() {
        val m = missing()
        if (m.isNotEmpty()) permissions.launch(m.toTypedArray())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) requestIfNeeded()
        val onLive = {
            vm.startLive()
            requestIfNeeded()
        }
        setContent {
            AlertBridgeTheme {
                val s by vm.state.collectAsStateWithLifecycle()
                var tab by rememberSaveable { mutableIntStateOf(0) }
                var sourceSheet by rememberSaveable { mutableStateOf(false) }
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                        TopBar(s, tab, { tab = it }, onSource = { sourceSheet = true })
                        if (tab == 1) ScenarioControls(s, vm, onLive)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        val body = Modifier.fillMaxWidth().weight(1f)
                        if (tab == 0) DriverScreen(vm, s, body) else LogicScreen(vm, s, body)
                    }
                    if (sourceSheet) SourceSheet(s, vm, onLive, onClose = { sourceSheet = false })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(s: UiState, tab: Int, onTab: (Int) -> Unit, onSource: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Space.l, vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
            // 지금 입력이 무엇인지 늘 보이게 한다. 누르면 바꿀 수 있다.
            Row(
                Modifier.clickable(onClick = onSource).defaultMinSize(minHeight = 32.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    s.scenario?.let { "${it.id.take(3)} · ${it.description.substringBefore(",")}" } ?: stringResource(R.string.mode_live),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1,
                )
                Icon(Icons.Filled.KeyboardArrowDown, null, tint = MaterialTheme.colorScheme.primary)
            }
        }
        // 개발·시연용 전환이라 출시 빌드에서는 숨긴다.
        if (BuildConfig.DEBUG) SingleChoiceSegmentedButtonRow {
            listOf(R.string.tab_driver, R.string.tab_logic).forEachIndexed { i, label ->
                SegmentedButton(
                    selected = tab == i, onClick = { onTab(i) },
                    shape = SegmentedButtonDefaults.itemShape(i, 2), icon = {},
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = MaterialTheme.colorScheme.primary,
                        activeContentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) { Text(stringResource(label), style = MaterialTheme.typography.labelLarge) }
            }
        }
    }
}

/** 입력 선택: 실시간 또는 시나리오 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourceSheet(s: UiState, vm: AppViewModel, onLive: () -> Unit, onClose: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = Space.xl)) {
            Text(stringResource(R.string.demo_sheet_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = Space.l))
            Text(stringResource(R.string.demo_sheet_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = Space.l, vertical = Space.s))
            SourceRow(stringResource(R.string.demo_sheet_live), null, selected = s.scenario == null) { onLive(); onClose() }
            vm.scenarios.forEach { sc ->
                SourceRow(sc.description, sc.id.take(3), selected = s.scenario?.id == sc.id) { vm.start(sc); onClose() }
            }
        }
    }
}

@Composable
private fun SourceRow(title: String, tag: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).defaultMinSize(minHeight = 56.dp).padding(horizontal = Space.l, vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        Surface(
            shape = CircleShape,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Text(
                tag ?: "GPS", Modifier.padding(horizontal = Space.s, vertical = Space.xs).width(36.dp),
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(0.dp))
    }
}
