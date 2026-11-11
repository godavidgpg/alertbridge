package com.alertbridge.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.alertbridge.app.R
import com.alertbridge.app.adapter.DummyPleosSource
import com.alertbridge.app.adapter.FixtureSignalSource
import com.alertbridge.app.adapter.CarSpeedSource
import com.alertbridge.app.adapter.CompositeSignalSource
import com.alertbridge.app.adapter.LiveSignalSource
import com.alertbridge.app.adapter.PleosLiveSource
import com.alertbridge.app.adapter.SignalSource
import com.alertbridge.app.platform.AndroidDialer
import com.alertbridge.app.platform.Dialer
import com.alertbridge.app.platform.FakeGuardianNotifier
import com.alertbridge.app.platform.GuardianNotifier
import com.alertbridge.app.platform.AndroidTts
import com.alertbridge.app.platform.TestModeDialer
import com.alertbridge.app.BuildConfig
import com.alertbridge.app.platform.Tts
import com.alertbridge.engine.AlertBridgeEngine
import com.alertbridge.engine.fixture.Scenario
import com.alertbridge.engine.fixture.ScenarioJson
import com.alertbridge.engine.model.Action
import com.alertbridge.engine.model.ActionId
import com.alertbridge.engine.model.ActionStatus
import com.alertbridge.engine.model.Experience
import com.alertbridge.engine.model.OccupantProfile
import com.alertbridge.engine.model.Answer
import com.alertbridge.engine.model.CapabilityMap
import com.alertbridge.engine.model.EngineResult
import com.alertbridge.engine.model.TriageAnswer
import com.alertbridge.engine.model.TriageQ
import com.alertbridge.engine.model.UserActionEvent
import com.alertbridge.engine.model.VehicleEvent
import com.alertbridge.engine.model.requiredCapability
import com.alertbridge.engine.policy.Messages
import com.alertbridge.agent.advisor.ActionRequest
import com.alertbridge.agent.advisor.AdvisorDecision
import com.alertbridge.agent.advisor.AdvisorProgress
import com.alertbridge.agent.advisor.ClaudeAgentModel
import com.alertbridge.agent.advisor.InsuranceAdvisor
import com.alertbridge.agent.advisor.Tools
import com.alertbridge.agent.insurance.CostService
import com.alertbridge.agent.insurance.MockInsurerApi
import com.alertbridge.agent.stats.ClaimStats
import com.alertbridge.agent.stats.DataGoKrClaimStats
import com.alertbridge.agent.stats.SnapshotClaimStats
import com.alertbridge.app.data.PolicyStore
import com.alertbridge.engine.model.InsurancePolicy
import com.alertbridge.engine.model.IncidentKind
import com.alertbridge.engine.model.SessionState
import com.alertbridge.engine.model.VehicleProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class InputRow(val event: VehicleEvent, val used: Boolean)

sealed interface AdvisorUi {
    data object Idle : AdvisorUi
    /** 보험 정보가 없어 판단할 수 없다 */
    data object Unavailable : AdvisorUi
    data class Running(val step: String?) : AdvisorUi
    data class Done(val decision: AdvisorDecision) : AdvisorUi
}

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
    /** 실시간 모드에서 연결된 입력 (GPS, 차속, Pleos 차량) */
    val liveInputs: List<String> = emptyList(),
    /** 시나리오의 운전자 프로필. 실시간 모드는 프로필 선택 화면이 없어 null */
    val driver: OccupantProfile? = null,
    /** AI 보험 판단 */
    val advisor: AdvisorUi = AdvisorUi.Idle,
    /** AI가 실행하려는 조치. 사용자 승인을 기다린다 */
    val pendingApproval: ActionRequest? = null,
    /** 가입할 때 등록한 내 보험. 실시간 모드에서 쓴다 (시나리오는 시나리오의 프로필을 쓴다) */
    val myPolicy: InsurancePolicy? = null,
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

    private val tts = AndroidTts(app) { text -> _state.update { it.copy(lastSpoken = text) } }
    private val dialer: Dialer =
        if (BuildConfig.OPEN_REAL_DIALER) AndroidDialer(app)
        else TestModeDialer { number -> _state.update { it.copy(testModeDialed = number) } }
    private val guardian: GuardianNotifier = FakeGuardianNotifier { _, _ -> }

    private var engine: AlertBridgeEngine? = null
    private var source: SignalSource? = null
    private var playback: FixtureSignalSource? = null
    private var job: Job? = null
    private var lastQuestion: TriageQ? = null
    private var driver: OccupantProfile? = null
    private var vehicle: VehicleProfile? = null

    /** 키가 없으면 AI 대신 규칙 기반 판단을 쓴다. */
    val aiAvailable: Boolean = BuildConfig.ANTHROPIC_API_KEY.isNotBlank()
    private var advisorJob: Job? = null
    private var approval: CompletableDeferred<Boolean>? = null
    private var advisedSession: Long? = null

    private val policyStore = PolicyStore(app)

    /** 공공 손해 통계. 키가 없으면 앱에 내장한 통계를 쓴다. 한 번 받으면 앱이 켜져 있는 동안 다시 받지 않는다 */
    private val claimStats: ClaimStats =
        if (BuildConfig.DATAGOKR_SERVICE_KEY.isBlank()) SnapshotClaimStats else DataGoKrClaimStats(BuildConfig.DATAGOKR_SERVICE_KEY)

    private fun readAsset(path: String) = getApplication<Application>().assets.open(path).use { it.readBytes().toString(Charsets.UTF_8) }

    init {
        _state.update { it.copy(myPolicy = policyStore.load()) }
        startLive()
    }

    /** 데모 운전자의 보험. 등록 화면의 처음 값으로 쓴다 */
    fun demoPolicy(): InsurancePolicy? = ScenarioJson.profile(readAsset(DEMO_PROFILE)).insurance

    /** 보험 등록·수정. 실시간 대기 중이면 바로 반영하고, 사고 처리 중이면 다음 실시간 시작부터 반영한다 */
    fun savePolicy(p: InsurancePolicy?) {
        if (p == null) policyStore.clear() else policyStore.save(p)
        _state.update { it.copy(myPolicy = p) }
        val st = _state.value
        val idle = st.result == null || st.result.session.state in setOf(SessionState.DISMISSED, SessionState.CLOSED)
        if (st.scenario == null && idle) startLive()
    }

    /** 실제 위치 API로 시작한다. 차량 신호 없이도 수동 신고는 동작한다. */
    fun startLive() {
        val app = getApplication<Application>()
        val gps = LiveSignalSource(app)
        val speed = CarSpeedSource(app)
        val pleos = PleosLiveSource.tryCreate(app)
        val inputs = listOfNotNull(
            app.getString(R.string.input_gps),
            app.getString(R.string.input_speed).takeIf { speed.isAutomotive },
            app.getString(R.string.input_pleos).takeIf { pleos != null },
        )
        playback = null
        run(CompositeSignalSource(listOfNotNull(gps, speed, pleos)), null) { _ -> }
        _state.update { it.copy(locationDenied = !gps.hasPermission(), liveInputs = inputs) }
    }

    override fun onCleared() {
        job?.cancel()
        tts.shutdown()
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
        // 실시간 모드에는 프로필 선택 화면이 없어 데모 운전자를 쓴다.
        val base = ScenarioJson.profile(readAsset(s?.profile ?: DEMO_PROFILE))
        // 실시간 모드는 사용자가 등록한 보험을 쓴다. 등록하지 않았으면 데모 운전자의 보험을 쓴다.
        val driver = if (s == null) _state.value.myPolicy?.let { base.copy(insurance = it) } ?: base else base
        this.driver = driver
        this.vehicle = s?.vehicle
        advisorJob?.cancel()
        approval?.complete(false)
        advisedSession = null
        val eng = AlertBridgeEngine.withDefaultPolicy()
        engine = eng
        source = src
        _state.update { UiState(scenario = s, speed = it.speed, autoAnswers = it.autoAnswers, useRealGps = it.useRealGps, driver = driver, myPolicy = it.myPolicy) }
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

    /**
     * AI 보험 판단. 보험 조회·보장 판단은 바로 하고, 견인 요청·사고 접수는 사용자 승인 후에만 실행한다.
     * 실행된 조치의 접수번호는 엔진에 접수번호로 넘겨 사건 기록에 남긴다.
     */
    fun runAdvisor() {
        val eng = engine ?: return
        val r = eng.currentResult(now()) ?: return
        val insurance = driver?.insurance ?: run {
            _state.update { it.copy(advisor = AdvisorUi.Unavailable) }
            return
        }
        advisorJob?.cancel()
        advisedSession = r.candidate.detectedMs
        _state.update { it.copy(advisor = AdvisorUi.Running(null)) }
        val v = vehicle
        advisorJob = viewModelScope.launch {
            val decision = withContext(Dispatchers.IO) {
                InsuranceAdvisor(ClaudeAgentModel.fromApiKey(BuildConfig.ANTHROPIC_API_KEY)).decide(
                    result = r,
                    vehicle = v,
                    insurer = MockInsurerApi(insurance),
                    costs = CostService(insurance, claimStats),
                    approve = { req -> awaitApproval(req) },
                    onProgress = { p ->
                        val label = (p as? AdvisorProgress.Calling)?.label
                        _state.update { it.copy(advisor = AdvisorUi.Running(label)) }
                    },
                )
            }
            recordActions(decision)
            _state.update { it.copy(advisor = AdvisorUi.Done(decision), pendingApproval = null) }
        }
    }

    private suspend fun awaitApproval(req: ActionRequest): Boolean {
        val d = CompletableDeferred<Boolean>()
        approval = d
        _state.update { it.copy(pendingApproval = req) }
        val ok = d.await()
        _state.update { it.copy(pendingApproval = null) }
        return ok
    }

    fun answerApproval(ok: Boolean) {
        approval?.complete(ok)
        approval = null
    }

    /** 보험사가 준 번호는 사람이 승인한 조치의 접수번호다. 엔진 기록에 남긴다. */
    private fun recordActions(d: AdvisorDecision) {
        val eng = engine ?: return
        d.actionsTaken.forEach { a ->
            val id = when (a.tool) {
                Tools.REQUEST_TOW.name -> ActionId.CALL_INSURER_EMERGENCY
                Tools.FILE_CLAIM.name -> ActionId.CALL_INSURER_CLAIM
                else -> return@forEach
            }
            if (a.referenceNo.isBlank()) return@forEach
            eng.onUserAction(UserActionEvent.ContactAttempted(id, now()))
            eng.onUserAction(UserActionEvent.ReceiptNumberEntered(id, a.referenceNo, now()))
        }
        refresh()
    }

    /** 신고 문장 읽어 주기 */
    fun speak(text: String) = tts.speak(text)

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
        if (q != null && q != lastQuestion) {
            val text = getApplication<Application>().getString(questionRes(q))
            tts.speak(text)
            // 초보·고령 모드는 같은 안내를 한 번 더 읽는다.
            val slow = driver?.experience.let { it == Experience.NOVICE || it == Experience.SENIOR }
            if (slow) tts.speak(text)
        }
        lastQuestion = q

        // 질문이 끝나면 AI 보험 판단을 한 번 자동으로 돌린다.
        val r = result
        val active = r != null && r.session.state != SessionState.DISMISSED && r.session.state != SessionState.CLOSED
        if (active && r!!.pendingQuestion == null && r.candidate.kind != IncidentKind.ACCIDENT_SUSPECTED &&
            advisedSession != r.candidate.detectedMs && _state.value.advisor !is AdvisorUi.Running
        ) runAdvisor()
        if (!active && _state.value.advisor != AdvisorUi.Idle) {
            advisorJob?.cancel()
            approval?.complete(false)
            advisedSession = null
            _state.update { it.copy(advisor = AdvisorUi.Idle, pendingApproval = null) }
        }
        // 바뀐 것이 없으면 상태를 다시 만들지 않는다. 화면 재구성과 CPU 사용을 줄인다.
        _state.update { if (it.result == result && it.nowMs / 1000 == now / 1000) it else it.copy(nowMs = now, result = result) }
    }

    private class Scripted(val tMs: Long, val apply: (AlertBridgeEngine) -> Unit)

    private companion object {
        const val TICK_MS = 250L
        const val DEMO_PROFILE = "profiles/driver_normal.json"
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
