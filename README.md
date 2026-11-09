# AlertBridge

차량 신호로 사고를 감지하고, 위험도를 **규칙으로** 판정해 지금 무엇을 먼저 해야 하는지(119, 대피, 112, 보험 접수 등) 안내하는 판단 엔진과, 그 판단 과정을 보여 주는 Android 앱.

> **현재 단계:** 판단 엔진과 Pleos 어댑터 완성, 더미 차량 데이터로 검증 완료. 운전자용 화면(①~⑤)과 실차 연결은 남아 있음.

## 진행 상황

지시문의 완료 기준(DoD) 기준이다.

| 항목 | 상태 | 비고 |
|---|---|---|
| `./gradlew :engine:test` (Android SDK 없이) | ✅ 522개 통과 | 단위·골든·불변 조건·무작위 입력 400세트 |
| 시나리오 S01~S12 골든 테스트 | ✅ | `engine/src/test/resources/scenarios/` |
| 더미 Pleos 차량 → 어댑터 → 엔진 | ✅ 13개 통과 | 12개 시나리오를 SDK 형식으로 재생 |
| `docs/pleos-api-mapping.md` | ✅ | SDK AAR을 직접 열어 확인한 시그니처 + 미확인(TODO) 목록 |
| `docs/engine-contract.md` | ✅ | 팀원 통합 방법 |
| `docs/policy-table.md` | ✅ | 정책 JSON에서 자동 생성 |
| 연결 전 최종 확인, `ACTION_CALL` 미사용 | ✅ | 테스트로 검사 |
| 운전자 화면 ①~⑤ (확인→질문→대응 계획→최종 확인→사건 카드) | ❌ | 로직 검증을 먼저 하느라 보류. 엔진은 필요한 값을 이미 낸다 |
| 실차(Pleos 차량 서비스) 연결 | ❌ | 어댑터는 구현됨. 실차·시뮬레이터 확인 필요 |

## 빠른 시작

필요: JDK 17 이상 (Android Studio에 포함된 `jbr`를 써도 됨). 앱을 빌드하려면 Android SDK도 필요하다.

```bash
# 엔진만 (Android SDK 불필요)
./gradlew :engine:test

# 앱 (local.properties에 sdk.dir=... 또는 ANDROID_HOME 필요)
./gradlew :app:testDebugUnitTest     # 더미 Pleos 차량 → 어댑터 → 엔진
./gradlew :app:assembleDebug         # app/build/outputs/apk/debug/app-debug.apk

# 정책을 고친 뒤
./gradlew :engine:generatePolicyDoc  # docs/policy-table.md 재생성
```

Windows Git Bash 예: `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"`

Android SDK가 없으면 `:app` 모듈은 자동으로 빠지고 `:engine`만 빌드된다.

## 앱에서 볼 수 있는 것

세로 화면 하나에 판단 과정이 위에서 아래로 나온다.
`기대값 비교 → 1 감지 → 질문 → 2 파손 추정 → 3 위험 등급 → 4 조치 계획 → 5 보험 매칭 → 6 사건 세션 → 엔진 입력 → Pleos 콜백`

| 모드 | 입력 |
|---|---|
| **실시간 GPS** | 실제 위치 API(`LocationManager` + `Geocoder`). [수동 사고 신고]로 시작한다. 차량 신호는 "알 수 없음" |
| **S01~S12** | 더미 차량이 **Pleos SDK의 실제 타입·콜백 형식**으로 값을 보내고 실제 어댑터를 거쳐 엔진에 들어간다. 기대값과 현재 값이 함께 표시된다 |
| S01~S12 + [위치는 실제 GPS 사용] | 차량 신호는 더미, 위치만 실제 GPS. 도로 종류를 몰라 기대값과 달라질 수 있다 |

에뮬레이터에서 위치를 넣으려면 `adb emu geo fix <경도> <위도>`를 쓴다.

**디버그 빌드는 [신고 연결]을 눌러도 전화 앱을 열지 않는다.** 테스트 중 실수로 112·119에 걸리지 않게 하기 위해서다(`BuildConfig.OPEN_REAL_DIALER`). 릴리스 빌드에서만 번호를 띄운다.

## 구조

```
engine/   순수 Kotlin(JVM). Android·Pleos 의존성 없음. 모든 판단이 여기 있다
  model/      입력·출력 모델 (Signal은 Pleos 메서드와 1:1)
  detect/     사고 감지 (링 버퍼 60초, 트리거 규칙)
  damage/     파손 추정 (충격 위치·심각도·주행 가능·탑승자)
  risk/       위험 등급 R0~R4 (사실 계산 + 정책 규칙 해석)
  action/     조치 계획, 신고 문장
  coverage/   보험 담보 매칭 (참고 정보)
  session/    사건 상태 기계
  policy/     정책 테이블 로더·검증
  resources/policy/policy_v1.json   임계값·규칙·조치 순서 (코드는 해석만 한다)
  resources/messages_ko.json        사용자 문구
app/      Android 앱 (Compose)
  adapter/PleosSignalSource.kt   Pleos Vehicle SDK 2.3.2 → 엔진 입력 (실제 구현)
  adapter/DummyPleosVehicle.kt   SDK 형식을 재현하는 더미 차량
  adapter/LiveSignalSource.kt    실제 GPS
  ui/                            로직 뷰
docs/
  pleos-api-mapping.md   SDK 확인 결과와 지시문과 다른 점
  engine-contract.md     팀원 통합 방법
  policy-table.md        정책 표 (자동 생성)
```

## 팀원별 시작점

- **차량 센싱 / Pleos 연결:** [docs/engine-contract.md](docs/engine-contract.md) 6절 → `app/.../adapter/PleosSignalSource.kt`. 남은 일은 실제 `Vehicle` 서비스 연결, 차속(`CAR_SPEED`) 입력, [pleos-api-mapping.md](docs/pleos-api-mapping.md) 7절의 실차 확인 항목이다.
- **UI:** 엔진 출력 `EngineResult` 하나만 보고 그리면 된다. 판단 로직은 UI에 넣지 않는다. 지금의 `LogicScreen.kt`가 어떤 값이 있는지 보여 주는 예시다.
- **정책·문구:** `policy_v1.json`과 `messages_ko.json`만 고치고 `:engine:test`를 돌린다. 안전 원칙을 깨는 정책(R0 첫 조치가 119가 아님 등)은 로더가 거부한다.

## 안전 원칙 (바꾸지 않는다)

- 112·119·보험사에 **자동 발신하지 않는다.** 사람이 확인 버튼을 눌러야 하고 `ACTION_DIAL`로 번호만 띄운다. 직접 발신 권한을 선언하지 않는다.
- "연결 시도"와 "접수 완료"를 분리한다. 접수번호 입력 또는 완료 표시가 있어야 `CONFIRMED`가 된다.
- 인명 위험(R0)이 있으면 `CALL_119`가 항상 1순위다.
- 위험도·기관 선택은 규칙 기반이며 LLM을 쓰지 않는다.
- 지원되지 않는 신호는 "없음"이 아니라 "알 수 없음"이다. (예: 에어백 API 미지원이면 "미전개"라고 말하지 않는다)
- 사고 확정 전에는 신호·위치를 저장하지 않는다. 사고가 아니면 메모리 기록도 버린다.
- 신고 문장에는 확실한(HIGH) 충격 위치만 넣는다. 추정은 화면과 사건 카드에만 남긴다.

**면책:** 파손 추정과 보험 매칭은 참고 정보다. 최종 보상 여부는 약관과 보험사 판단에 따릅니다. 사고 화면에서는 가입 권유 문구를 넣지 않는다.

## 지시문과 달라진 점 (중요)

SDK AAR을 직접 열어 확인한 결과다. 자세한 내용은 [pleos-api-mapping.md](docs/pleos-api-mapping.md).

- **SDK 버전:** 지시문은 `Vehicle:2.0.3`이지만 필요한 메서드(경고등, 배터리 잔량, 각종 check...Capabilities)는 **2.3.2**에만 있어 2.3.2를 쓴다.
- **에어백:** 좌석별 `Boolean`만 주고 정면·측면 종류를 주지 않는다. 그래서 실차에서는 측면 충돌도 "앞(MEDIUM)"으로 추정되며, 신고 문장에는 넣지 않는다.
- **비상등:** SDK에 없다. `T_STOP_HAZARD` 트리거는 실차에서 동작하지 않는다.
- **도로 종류·km 지점:** `FusedLocation`, `NaviHelper` 어디에도 없다. 엔진이 "고속도로인가요?"를 묻고, 2504 긴급견인은 도로를 확실히 알 때만 제안한다.
- **차량 크기:** `CarInfo`에 없어 사용자 등록값(`VehicleProfile`)으로 받는다.
- **콜백 값:** Pleos 콜백 인자는 전부 nullable이다. null은 버리고 `false`로 바꾸지 않는다.

지시문에 없던 규칙도 몇 가지 정했다.
- 답이 없어도 차가 계속 달리면 R0로 올리지 않는다.
- 화면을 조작 중인 사람(예: 119 통화 중)은 무응답으로 처리하지 않는다.
- 사고 여부 "모르겠어요"는 사고로 보고 안내한다.

## 미완료

- 운전자 화면 ①~⑤ (76dp 터치, 차량 실루엣, 신호 그래프, 사진, 인적사항 QR)
- 실차 연결, 차속 입력, 도로 종류 데이터
- TTS·STT·보호자 알림은 가짜 구현
- 국토부 견인 운임표(`towFareTable`) 비어 있음
- 릴리스 빌드 성능 미측정 (에뮬레이터 디버그 빌드는 시작이 느리다)

## 퍼미션

현재: `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `INTERNET`(역지오코딩). SDK가 `ai.pleos.playground.service.vehicle.VEHICLE_BINDING`을 병합한다.

실차 연결 시 추가: `pleos.car.permission.CAR_SAFETY`, `CAR_SEATS`, `CAR_DOORS`, `CAR_LIGHTS`, `CAR_DRIVE`, `CAR_ENERGY`, `CAR_INFO`, `android.car.permission.CAR_SPEED`. Pleos 퍼미션은 App Market과 협의·승인된 범위에서만 쓸 수 있다.
