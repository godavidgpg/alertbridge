package com.alertbridge.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.alertbridge.app.R
import com.alertbridge.app.adapter.DummyPleosSource
import com.alertbridge.app.adapter.FixtureSignalSource
import com.alertbridge.app.adapter.LiveSignalSource
import com.alertbridge.app.adapter.SignalSource
import com.alertbridge.app.platform.AndroidDialer
import com.alertbridge.app.platform.Dialer
import com.alertbridge.app.platform.FakeGuardianNotifier
import com.alertbridge.app.platform.GuardianNotifier
import com.alertbridge.app.platform.RecordingTts
import com.alertbridge.app.platform.TestModeDialer
import com.alertbridge.app.BuildConfig
import com.alertbridge.app.platform.Tts
import com.alertbridge.engine.AlertBridgeEngine
import com.alertbridge.engine.fixture.Scenario
import com.alertbridge.engine.fixture.ScenarioJson
import com.alertbridge.engine.model.Action
import com.alertbridge.engine.model.ActionId
import com.alertbridge.engine.model.ActionStatus
import com.alertbridge.engine.model.Answer
import com.alertbridge.engine.model.CapabilityMap
import com.alertbridge.engine.model.EngineResult
import com.alertbridge.engine.model.TriageAnswer
import com.alertbridge.engine.model.TriageQ
import com.alertbridge.engine.model.UserActionEvent
import com.alertbridge.engine.model.VehicleEvent
import com.alertbridge.engine.model.requiredCapability
import com.alertbridge.engine.policy.Messages
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class InputRow(val event: VehicleEvent, val used: Boolean)

data class UiState(
    /** null이면 실시간 GPS 모드 */
    val scenario: Scenario? = null,
    val playing: Boolean = true,
    val speed: Float = 1f,
    val autoAnswers: Boolean = true,
    val nowMs: Long = 0,
    val result: EngineResult? = null,
    val inputs: List<InputRow> = emptyList(),
    /** 더미 차량이 보낸 Pleos 콜백 */
    val pleosCalls: List<String> = emptyList(),
    val useRealGps: Boolean = false,
    val capabilities: CapabilityMap = CapabilityMap(),
    val lastSpoken: String? = null,
    /** 전화 화면을 열지 못한 번호 */
    val dialFailedNumber: String? = null,
    val locationDenied: Boolean = false,
    /** 디버그 빌드에서 전화 앱 대신 기록만 한 번호 */
    val testModeDialed: String? = null,
)

/**
 * 엔진을 소유하고 모든 호출을 메인 스레드 한 곳에서 한다 (엔진은 단일 스레드 전제).
 * 판단은 하지 않는다. 입력을 전달하고 결과를 화면에 내놓을 뿐이다.
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    val messages: Messages = Messages.loadDefault()
    val scenarios: List<Scenario> = app.assets.list("scenarios").orEmpty().sorted()
        .map { ScenarioJson.scenario(readAsset("scenarios/$it")) }

    private val tts: Tts = RecordingTts { text -> _state.update { it.copy(lastSpoken = text) } }
    private val dialer: Dialer =
        if (BuildConfig.OPEN_REAL_DIALER) AndroidDialer(app)
        else TestModeDialer { number -> _state.update { it.copy(testModeDialed = number) } }
    private val guardian: GuardianNotifier = FakeGuardianNotifier { _, _ -> }

    private var engine: AlertBridgeEngine? = null
    private var source: SignalSource? = null
    private var playback: FixtureSignalSource? = null
    private var job: Job? = null
    private var lastQuestion: TriageQ? = null

    private fun readAsset(path: String) = getApplication<Application>().assets.open(path).use { it.readBytes().toString(Charsets.UTF_8) }

    init {
        startLive()
    }

    /** 실제 위치 API로 시작한다. 차량 신호 없이도 수동 신고는 동작한다. */
    fun startLive() {
        val src = LiveSignalSource(getApplication())
        playback = null
        run(src, null) { _ -> }
        _state.update { it.copy(locationDenied = !src.hasPermission()) }
    }

    fun start(s: Scenario) {
        // 시나리오 → 더미 Pleos 콜백 → PleosSignalSource(실제 어댑터) → 엔진
        val gps = if (_state.value.useRealGps) LiveSignalSource(getApplication()) else null
        val src = DummyPleosSource(s, gps) { call ->
            _state.update { it.copy(pleosCalls = (it.pleosCalls + call).takeLast(MAX_INPUT_ROWS)) }
        }
        playback = src.playback.also { it.speed = _state.value.speed }
        val scripted = ArrayDeque(
            (s.triage.map { Scripted(it.tMs) { e -> e.onTriage(it) } } + s.userActions.map { Scripted(it.tMs) { e -> e.onUserAction(it) } })
                .sortedBy { it.tMs }
        )
        run(src, s) { eng ->
            while (_state.value.autoAnswers && scripted.isNotEmpty() && scripted.first().tMs <= src.nowMs()) {
                scripted.removeFirst().apply(eng)
            }
        }
    }

    private fun run(src: SignalSource, s: Scenario?, beforeTick: (AlertBridgeEngine) -> Unit) {
        job?.cancel()
        lastQuestion = null
        val driver = s?.profile?.let { ScenarioJson.profile(readAsset(it)) }
        val eng = AlertBridgeEngine.withDefaultPolicy()
        engine = eng
        source = src
        _state.update { UiState(scenario = s, speed = it.speed, autoAnswers = it.autoAnswers, useRealGps = it.useRealGps) }
        job = viewModelScope.launch {
            val caps = src.capabilities()
            eng.configure(caps, driver, s?.vehicle)
            _state.update { it.copy(capabilities = caps) }
            launch {
                src.events().collect { e ->
                    eng.onEvent(e)
                    val row = InputRow(e, caps.isSupported(e.signal.requiredCapability()))
                    // 같은 값이 반복되면(1초마다 오는 GPS 등) 시각만 갱신해 로직이 묻히지 않게 한다.
                    _state.update {
                        val base = if (it.inputs.lastOrNull()?.event?.signal == e.signal) it.inputs.dropLast(1) else it.inputs
                        it.copy(inputs = (base + row).takeLast(MAX_INPUT_ROWS))
                    }
                }
            }
            while (isActive) {
                delay(TICK_MS)
                beforeTick(eng)
                refresh()
            }
        }
    }

    fun togglePlay() {
        val src = playback ?: return
        src.paused = !src.paused
        _state.update { it.copy(playing = !src.paused) }
    }

    fun restart() {
        val s = _state.value.scenario
        if (s != null) start(s) else startLive()
    }

    fun setSpeed(v: Float) {
        playback?.speed = v
        _state.update { it.copy(speed = v) }
    }

    fun setAutoAnswers(v: Boolean) = _state.update { it.copy(autoAnswers = v) }

    /** 위치만 실제 GPS로 바꾸고 차량 신호는 더미로 둔다. 처음부터 다시 재생한다. */
    fun setUseRealGps(v: Boolean) {
        _state.update { it.copy(useRealGps = v) }
        restart()
    }

    private fun now() = source?.nowMs() ?: 0

    fun answer(q: TriageQ, a: Answer) {
        engine?.onTriage(TriageAnswer(q, a, now()))
        refresh()
    }

    fun userAction(build: (Long) -> UserActionEvent) {
        engine?.onUserAction(build(now()))
        refresh()
    }

    fun selectSupport(id: ActionId) = userAction { UserActionEvent.SupportSelected(id, it) }

    /** 최종 확인의 [신고 연결]. 전화 화면이 열렸을 때만 "연결 시도"로 기록한다. */
    fun dial(action: Action) {
        val number = action.contact?.number ?: return
        val opened = dialer.dial(number)
        _state.update { it.copy(dialFailedNumber = if (opened) null else number) }
        if (opened) userAction { UserActionEvent.ContactAttempted(action.id, it) }
    }

    fun notifyGuardian(action: Action) {
        val phone = action.contact?.number ?: return
        guardian.notify(phone, action.reason.text)
        userAction { UserActionEvent.GuardianNotified(it) }
    }

    private fun refresh() {
        val eng = engine ?: return
        val now = now()
        var result = eng.currentResult(now)

        // 무응답 + 사전 동의일 때의 보호자 알림만 자동이다. 기관 연결은 여기로 오지 않는다.
        result?.plan?.actions?.firstOrNull { it.autoExecutable && it.status == ActionStatus.SUGGESTED }?.let { a ->
            a.contact?.number?.let { guardian.notify(it, a.reason.text) }
            eng.onUserAction(UserActionEvent.GuardianNotified(now))
            result = eng.currentResult(now)
        }

        val q = result?.pendingQuestion
        if (q != null && q != lastQuestion) tts.speak(getApplication<Application>().getString(questionRes(q)))
        lastQuestion = q
        _state.update { it.copy(nowMs = now, result = result) }
    }

    private class Scripted(val tMs: Long, val apply: (AlertBridgeEngine) -> Unit)

    private companion object {
        const val TICK_MS = 100L
        const val MAX_INPUT_ROWS = 40
    }
}

fun questionRes(q: TriageQ): Int = when (q) {
    TriageQ.IS_ACCIDENT -> R.string.question_IS_ACCIDENT
    TriageQ.IS_EXPRESSWAY -> R.string.question_IS_EXPRESSWAY
    TriageQ.INJURY -> R.string.question_INJURY
    TriageQ.FIRE_SMOKE -> R.string.question_FIRE_SMOKE
    TriageQ.DRIVABLE -> R.string.question_DRIVABLE
    TriageQ.OTHER_PARTY_FLED -> R.string.question_OTHER_PARTY_FLED
    TriageQ.OTHER_PARTY_SUSPICIOUS -> R.string.question_OTHER_PARTY_SUSPICIOUS
}
