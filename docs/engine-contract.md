# 엔진 통합 계약

차량 센싱 담당 팀원이 `PleosSignalSource`를 구현할 때 보는 문서다. 엔진은 Android·Pleos를 모르며, 어댑터가 Pleos 값을 엔진 입력으로 바꿔 넣는다.
메서드·상수 대응은 [pleos-api-mapping.md](pleos-api-mapping.md)에 있다.

```
Pleos SDK ─▶ PleosSignalSource(어댑터) ─▶ AlertBridgeEngine ─▶ EngineResult ─▶ UI
```

## 1. 어댑터가 할 일

1. `Vehicle(context).initialize()` 후 도메인별로 `check...Capabilities`를 호출해 `CapabilityMap`을 만든다.
   - 지원 확인(`AccessType`이 `READ`/`READ_WRITE`): `true`
   - 미지원(`NONE`, 또는 오류로 미지원 확정): `false`
   - 아직 모름, 또는 호출 실패 원인 불명: `null`
   - **`false`와 `null`을 판단 값 `false`와 섞지 않는다.** 엔진은 `true`인 신호만 근거로 쓰고, 나머지는 "알 수 없음"으로 표시한다.
   - 차속·위치처럼 Pleos capability가 없는 신호도 `VEHICLE_SPEED`, `LOCATION` 키를 직접 `true`로 넣어야 쓰인다.
2. 프로필과 함께 `engine.configure(capabilities, driver, vehicle)`을 호출한다.
3. `register...` 콜백을 `VehicleEvent(tMs, Signal.X(...))`로 바꿔 `onEvent`에 넣는다. 등록 직후 `getCurrent...`로 현재 값도 한 번씩 넣는다.
4. Pleos 상수를 엔진 enum으로 변환한다 (변환표는 매핑 문서 5절).
5. 다음 변환은 어댑터 책임이다.
   - 타이어: `registerTirePressure` 값을 `getLowTirePressure` 값과 비교해 `Signal.LowTirePressure(wheel, low)`
   - 도어: 정수 위치를 `Signal.DoorPosition(door, open)`으로
   - 경고등: `Telltales` 목록을 항목별 `Signal.Telltale`로 펼침
   - 위치: `Signal.Location`으로. 도로 종류를 모르면 `roadType=UNKNOWN`과 낮은 `roadTypeConfidence`를 넣는다. 동작하는 예는 `app/.../adapter/LiveSignalSource.kt`

## 2. 이벤트 주기

| 신호 | 권장 주기 |
|---|---|
| 차속, 조향각 | 10Hz 이상 |
| 나머지 | 값이 바뀔 때 |

차속 주기가 느리면 `T_HARD_STOP`(3초 안에 30km/h 감속)과 `deltaV` 정확도가 떨어진다. 엔진은 샘플 사이의 값이 유지된다고 보고 계산한다.

## 3. 시각 기준

- `tMs`는 **앱이 콜백을 받은 시각(epoch ms)**이다. 차량 제어기 시각이 아니다.
- 제어기마다 신호가 서로 동기화되지 않을 수 있어 수십~수백 ms의 순서 오차는 정상이다.
- 엔진은 현재 시각을 직접 읽지 않는다. `currentResult(nowMs)`의 `nowMs`도 같은 시계를 쓴다.
- 차가 멈춘 동안에도 무응답 타이머가 돌도록 **`currentResult`를 주기적으로 호출한다** (앱은 250ms).

## 4. 스레드 모델

엔진은 스레드 안전하지 않다. 모든 호출(`configure`, `onEvent`, `onTriage`, `onUserAction`, `currentResult`)을 **한 스레드에서 시각 순서대로** 한다. Pleos 콜백이 여러 스레드에서 오면 단일 스레드 디스패처로 모은다.

```kotlin
private val engineDispatcher = Dispatchers.Default.limitedParallelism(1)
```

## 5. 공개 API

```kotlin
class AlertBridgeEngine(policy: PolicyTable, messages: Messages = Messages.loadDefault()) {
    fun configure(capabilities: CapabilityMap, driver: OccupantProfile? = null, vehicle: VehicleProfile? = null)
    fun onEvent(e: VehicleEvent)
    fun onTriage(a: TriageAnswer)
    fun onUserAction(u: UserActionEvent)
    fun currentResult(nowMs: Long): EngineResult?   // 사건이 없으면 null
}
```

엔진이 하지 않는 일:

- 전화·문자 발신. `Action.contact`에 번호만 주고, UI가 사용자 확인 후 `ACTION_DIAL`을 연다.
- 저장. `EngineResult.session.caseCard`가 나타난 뒤(사고 확정 뒤)에만 앱이 저장할 수 있다. 사건이 `DISMISSED`되면 메모리 기록도 버린다.

앱이 해야 하는 일:

- `Action.autoExecutable == true`이고 `status == SUGGESTED`이면 `GuardianNotifier`로 알린 뒤 `UserActionEvent.GuardianNotified`를 보낸다. 무응답 + 사전 동의일 때의 `NOTIFY_GUARDIAN`만 해당한다.

## 6. PleosSignalSource

`app/.../adapter/PleosSignalSource.kt`에 SDK 2.3.2 기준으로 구현되어 있다. 실제 차량 서비스에는 아직 연결해 보지 않았다.

```kotlin
val vehicle = Vehicle(context).also { it.initialize() }
val source = PleosSignalSource(
    PleosVehicleApis.from(vehicle),
    auxCapabilities = mapOf(CapabilityKey.VEHICLE_SPEED to true, CapabilityKey.LOCATION to true),
)
engine.configure(source.capabilities(), driver, vehicleProfile)  // suspend
source.events().collect(engine::onEvent)
```

- 차속과 위치는 Vehicle API가 아니므로 호출자가 `onEvent`로 따로 넣는다. 위치 예는 `LiveSignalSource`.
- 실차 연결 전 할 일: 서비스 연결 완료를 기다린 뒤 `capabilities()` 호출 (`initialize()`의 비동기 동작 확인 필요), 차속 입력 구현, 매핑 문서 7절 확인.
- 모든 Pleos 콜백 인자는 nullable이다. null과 `onFailed`는 이벤트를 만들지 않는다.

### 더미 차량으로 시험

`DummyPleosVehicle`은 SDK의 실제 인터페이스·상수·콜백 형식으로 값을 보낸다. 시나리오 12개를 이 형식으로 재생해 어댑터와 엔진을 함께 검증한다.

```bash
./gradlew :app:testDebugUnitTest
```

값은 더미이므로 실차 동작을 보증하지 않는다. 타이어 기준값 200, 도어 범위 0~100은 임의 값이다.

## 7. 출력 읽는 법

| 필드 | 뜻 |
|---|---|
| `candidate.trigger` / `confidence` / `kind` | 무엇으로 감지했는가. `kind`: 의심/사고/고장 |
| `pendingQuestion`, `questionDeadlineMs` | 다음에 물을 질문과 무응답 처리 시각 |
| `vehicleMoving` | `true`면 UI는 질문 화면과 입력을 띄우지 않는다 |
| `risk.grades[].because` | 등급과 이유 코드 |
| `plan.actions` | 우선순위 순 조치. `first()`가 1순위 |
| `damage` | 충격 위치(신뢰도 포함), 심각도, 주행 가능, 좌석별 상태. `null`은 알 수 없음 |
| `coverage` | 보험 매칭. 면책 문구를 항상 함께 표시한다 |
| `session.caseCard` | 사고 확정 뒤에만 있다 |
| `policyVersion` | 판단에 쓴 정책 버전 |

## 8. 지시문에 없어 추가한 입력

| 항목 | 이유 |
|---|---|
| `TriageQ.IS_EXPRESSWAY` | 4-3의 "고속도로인가요?" 질문 |
| `Signal.LowBeamLight`, `Signal.WiperActive` | 4-3의 야간·우천 R1 판정 |
| `Signal.Location.address` | 4-5의 "없으면 주소" |
| `VehicleProfile` | `CarInfo`에 차량 크기가 없어 전기차 여부·크기를 사용자 등록값으로 받음 |
| `OccupantProfile.guardianAutoNotifyConsent`, `medicalShareConsent`, `homeLat/homeLng` | 사전 동의 판단, 의료 정보 공유 동의, "집에서 먼 곳" 판단 |
| `Signal.Telltale.telltaleType` | JSON 구분자 `type`과 이름이 충돌해 지시문의 `type` 필드를 바꿈 |
