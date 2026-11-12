# Pleos Vehicle SDK ↔ 엔진 입력 매핑

`Vehicle-2.0.3.aar`와 `Vehicle-2.3.2.aar`의 `classes.jar`를 `javap`로 직접 읽어 확인했다 (2026-11-07).
저장소: `https://nexus-playground.pleos.ai/repository/maven-releases/`

- **확인**: AAR에서 시그니처를 읽었다. 단, 메서드 이름과 타입만 확인했고 실차 동작은 확인하지 않았다.
- **TODO(VERIFY)**: 실차·에뮬레이터 또는 Pleos 측 답변이 필요하다.

## 1. 어떤 버전을 쓸 것인가: 2.3.2 권장

지시문은 `Vehicle:2.0.3`을 지정했지만, 지시문에 나열된 메서드 상당수가 2.0.3에 없다.

| 메서드 | 2.0.3 | 2.3.2 |
|---|---|---|
| `getTelltalesInfo`, `registerTelltales` (`constant.telltale.*`) | 없음 | 있음 |
| `checkLowTirePressureCapabilities`, `getTirePressure`, `registerTirePressure` | 없음 | 있음 |
| `checkTurnSignalStateCapabilities`, `checkBrakePedalStatusCapabilities` | 없음 | 있음 |
| `getEvBatteryStateOfCharge`, `registerEvBatteryStateOfCharge` | 없음 (`getEvBatteryLevel`만) | 있음 |
| `checkPowerDownWarningIndicatorCapabilities`, `checkEngineOilLevelStateCapabilities` 등 | 없음 | 있음 |

`PleosSignalSource`는 **2.3.2 기준으로 작성**한다. 2.3.2는 차량 서비스 `min_version_name=2.3.13`을 요구하고 2.0.3은 `2.1.0`을 요구하므로, 대상 차량·시뮬레이터의 서비스 버전을 먼저 확인할 것. TODO(VERIFY)

2.3.2의 `check...Capabilities` 대부분은 콜백이 `CapabilityConfig`(`getAccessType(): AccessType` = `NONE/READ/WRITE/READ_WRITE`)와 지원 영역 목록을 함께 준다. `AccessType.NONE`이거나 `onError`면 엔진에는 `false`/`null`을 넣는다.

`:engine`은 SDK에 의존하지 않으므로 이 선택은 어댑터에만 영향을 준다.

## 2. 지시문과 실제 SDK가 다른 점

| # | 지시문 | 실제 SDK | 엔진·어댑터 처리 |
|---|---|---|---|
| 1 | 에어백 API의 종류 구분 여부 확인 | **좌석별 `Boolean`만 준다. 정면/측면/커튼 구분 없음** | `airbagKind`는 어댑터에서 항상 `null`. 파손 추정은 "종류 구분 불가" 규칙(FRONT, MEDIUM)을 탄다. 측면 충격 HIGH 규칙은 SDK가 종류를 주기 전까지 픽스처에서만 동작한다 |
| 2 | TurnSignal에 비상등 포함 여부 확인 | **없다.** `TurnSignalValue = OFF/RIGHT/LEFT`. SDK 전체에 "hazard" 문자열 없음 | `HAZARD_STATE` capability를 별도로 둔다. 기본 `null`이면 `T_STOP_HAZARD`와 비상등 근거는 동작하지 않는다 (아래 3절 대안 참고) |
| 3 | `LowTirePressure(wheel, low: Boolean)` | `getLowTirePressure(WheelArea, (Float)->Unit, err)`: **`Float` 반환, 등록 리스너 없음.** 의미는 저압 경고 기준값으로 추정 TODO(VERIFY) | 어댑터가 `registerTirePressure`(2.3.2) 값과 비교해 `low` 계산. 엔진은 `Boolean` 유지 |
| 4 | `DoorPosition(door, open: Boolean)` | `onPositionUpdated(Integer)`: **정수 위치** | 어댑터: `open = position > checkDoorPositionCapabilities의 최솟값`. TODO(VERIFY) |
| 5 | `BrakeState(pedalPressed, parkingBrakeOn)` | Brake는 페달 `Boolean`만. **주차 브레이크 없음** | `parkingBrakeOn`은 `null`, 또는 `TelltaleType.PARKING_BRAKE`/`ELECTRONIC_PARKING_BRAKE`로 어댑터가 채움 |
| 6 | `CarInfo`: 크기·제조사·연식 | **`getVINInfo`, `getCRNInfo`, `getCapabilityInfo`뿐** | 2504 소형 차량 판단은 사용자 등록 `VehicleProfile.sizeClass`로 한다. 미등록은 `UNKNOWN` |
| 7 | `FluidLevel`: NORMAL/LOW/UNKNOWN | `OilLevelState = LOW/HIGH/NORMAL/ERROR/CRITICALLY_LOW` | 엔진 `LevelState`에 같은 값 + `UNKNOWN`. `LOW`, `CRITICALLY_LOW`만 경고. `ERROR`는 "읽기 실패"로 보고 경고로 쓰지 않음 |
| 8 | `TelltaleData.color: String?` | `getColor(): Int` (`TelltaleColor`와 관계 TODO(VERIFY)) | 엔진은 문자열로 받는다. 판정에 색을 쓰지 않는다 |
| 9 | 좌석 7개 | `SeatValue`는 9개 (`ROW_1_CENTER`, `ROW_3_CENTER` 포함) | 엔진 `SeatArea`도 9개 |
| 10 | 차속: `CAR_SPEED` | 2.0.3의 `VehicleSpeedListener`는 등록 메서드가 없고 2.3.2에서 삭제 | 지시문대로 Android 차량 속성 사용 |

추가 확인: 2.3.2 리스너의 인자는 enum까지 모두 nullable로 컴파일된다. 어댑터는 null을 이벤트로 만들지 않는다. `WiperState`의 `OTHER`, `SERVICE`도 이벤트로 만들지 않는다.

## 3. 비상등 대안 (TODO(VERIFY))

2.3.2의 `TelltaleType`에 `TURN_SIGNAL_LEFT`, `TURN_SIGNAL_RIGHT`가 있다. **두 경고등이 동시에 `BLINK`/`ON`이면 비상등**으로 볼 수 있으나 실차 확인이 필요하다. 확인되면 어댑터가 `TurnSignalState(HAZARD)`를 만들고 `HAZARD_STATE = true`를 넣으면 되며 엔진 변경은 필요 없다.

## 4. 메서드 매핑

SDK 진입점: `Vehicle(context)` → `initialize()` → `getSafety()/getSeat()/...` → `release()`.
비동기 콜백 형식: `getX(area?, onSuccess: (T)->Unit, onError: (Exception)->Unit)`. 리스너는 모두 `onFailed(Exception)`을 포함한다.

| Pleos 메서드 (2.3.2) | 반환 타입 | 엔진 필드 (`Signal`) | 확인 여부 |
|---|---|---|---|
| `Safety.registerSeatAirbagDeployed(SeatArea, l)` / `hasSeatAirbagBeenDeployed` | `Boolean` | `SeatAirbagDeployed(seat, deployed, airbagKind=null)` | 확인 |
| `Safety.checkAirbagDeployedCapabilities` | `CapabilityConfig, List<SeatArea>` | `SEAT_AIRBAG_DEPLOYED` | 확인 |
| `Seat.registerSeatOccupancy(SeatArea, l)` / `getCurrentSeatOccupancy` | `Boolean` | `SeatOccupancy(seat, occupied)` | 확인 |
| `Seat.checkSeatOccupancyCapabilities` | `CapabilityConfig, List<SeatArea>` | `SEAT_OCCUPANCY` | 확인 |
| `Seat.registerSeatBeltBuckledState(SeatArea, l)` / `getCurrentSeatBeltBuckledState` | `Boolean` | `SeatBeltBuckled(seat, buckled)` | 확인 |
| `Seat.checkSeatBeltBuckledCapabilities` | `CapabilityConfig, List<SeatArea>` | `SEAT_BELT_BUCKLED` | 확인 |
| `Tire.getLowTirePressure(WheelArea)` + `Tire.registerTirePressure(WheelArea, l)` | `Float` | `LowTirePressure(wheel, low)` (어댑터 변환) | 시그니처 확인, 의미 TODO(VERIFY) |
| `Tire.checkLowTirePressureCapabilities` | `CapabilityConfig, List<WheelArea>` | `LOW_TIRE_PRESSURE` | 확인 |
| `Door.registerDoorPosition(DoorArea, l)` / `getCurrentDoorPosition` | `Integer` | `DoorPosition(door, open)` (어댑터 변환) | 시그니처 확인, 변환 기준 TODO(VERIFY) |
| `Door.checkDoorPositionCapabilities` | `Map<DoorArea, AreaBounds<Int>>` | `DOOR_POSITION` | 확인 |
| `TurnSignal.registerTurnSignalState(l)` / `getCurrentTurnSignalState` | `TurnSignalValue` | `TurnSignalState(state)` | 확인 |
| `TurnSignal.checkTurnSignalStateCapabilities` | `CapabilityConfig` | `TURN_SIGNAL` | 확인 |
| `Brake.registerBrakePedalStatus(l)` / `getCurrentBrakePedalStatus` | `Boolean` | `BrakeState(pedalPressed, parkingBrakeOn=null)` | 확인 |
| `Brake.checkBrakePedalStatusCapabilities` | `CapabilityConfig` | `BRAKE_PEDAL` | 확인 |
| `SteeringWheel.registerSteeringWheelAngle(l)` / `getCurrentSteeringWheelAngle` | `Float` | `SteeringAngle(degrees)` (**부호 방향** TODO(VERIFY), 정책값 `steeringRightPositive`) | 시그니처 확인 |
| `SteeringWheel.checkSteeringWheelAngleCapabilities` | `CapabilityConfig, AreaBounds<Float>` | `STEERING_ANGLE` | 확인 |
| `Safety.registerBrakeFluidLevelState(l)` / `getBrakeFluidLevelState` | `OilLevelState` | `FluidLevel(BRAKE_FLUID, state)` | 확인 |
| `Safety.registerEngineOilLevelState(l)` / `getEngineOilLevelState` | `OilLevelState` | `FluidLevel(ENGINE_OIL, state)` | 확인 |
| `Safety.registerTransmissionOilLevelState(l)` / `getTransmissionOilLevelState` | `OilLevelState` | `FluidLevel(TRANSMISSION_OIL, state)` | 확인 |
| `Safety.check{EngineOil,BrakeFluid,TransmissionOil}LevelStateCapabilities` | `CapabilityConfig, List<OilLevelState>` | `FLUID_LEVEL` | 확인 |
| `Safety.registerPowerDownWarningIndicator(l)` / `hasPowerDownWarningIndicator` | `Boolean` | `PowerDownWarning(on)` | 확인 |
| `Safety.checkPowerDownWarningIndicatorCapabilities` | `CapabilityConfig` | `POWER_DOWN_WARNING` | 확인 |
| `Safety.registerTelltales(l)` / `getTelltalesInfo` | `Telltales(List<TelltaleData>)` | `Telltale(telltaleType, state, color)` (항목별로 펼침) | 확인 |
| `Safety.checkTelltalesCapabilities` | `CapabilityConfig` | `TELLTALES` | 확인 |
| `EvBattery.registerEvBatteryStateOfCharge(l)` / `getEvBatteryStateOfCharge` | `Integer` | `EvStateOfCharge(percent)` | 확인 |
| `EvBattery.checkEvBatteryStateOfChargeCapabilities` | `CapabilityConfig` | `EV_SOC` | 확인 |
| `Light.registerLowBeamLightState(l)` / `getCurrentLowBeamLightState` | `LightState` | `LowBeamLight(on)` (야간 추정 보조) | 시그니처 확인 |
| `Wiper.registerWiperState(WindowArea, l)` / `getCurrentWiperState` | `WiperState` | `WiperActive(active)` (우천 추정 보조) | 시그니처 확인 |
| `android.car` 차속 (`android.car.permission.CAR_SPEED`) | km/h 환산 | `VehicleSpeed(kmh)` | Android 표준, 단위 변환은 어댑터 |
| `FusedLocation.registerFusedLocationCallback(l)` / `getLastKnownLocation(l)` | `LocationData` | `Location(lat, lng, ...)` (8절) | 시그니처 확인 |

**지시문에 있으나 엔진이 쓰지 않는 메서드**: `hasSeatAirbagEnabled`, `hasSeatBeltNotBuckledWarning`, `hasLowWasherFluidWarning`, `getDoorLockState`. 필요한 판단 근거가 아니어서 입력 모델에 넣지 않았다.

## 5. 상수 → 엔진 enum 변환표

| Pleos 상수 | 엔진 enum |
|---|---|
| `SeatValue.ROW_1_LEFT` … `ROW_3_RIGHT` (9개) | `SeatArea.ROW1_LEFT` … `ROW3_RIGHT` (밑줄 하나 제거) |
| `WheelValue.LEFT_FRONT/RIGHT_FRONT/LEFT_REAR/RIGHT_REAR` | `WheelArea` 동일 이름. `WheelValue.UNKNOWN`은 이벤트로 만들지 않음 |
| `DoorValue.ROW_1_LEFT` … `ROW_3_RIGHT`, `HOOD`, `REAR` | `DoorArea.ROW1_LEFT` … `ROW3_RIGHT`, `HOOD`, `REAR` |
| `TurnSignalValue.OFF/LEFT/RIGHT` | `TurnSignal.NONE/LEFT/RIGHT` (`HAZARD`는 3절 대안으로만) |
| `OilLevelState.NORMAL/LOW/HIGH/ERROR/CRITICALLY_LOW` | `LevelState` 동일 이름 |
| `TelltaleType.*`, `TelltaleState.OFF/ON/BLINK` | 문자열 그대로 (`Signal.Telltale`). 경고로 볼 종류는 정책값 `telltaleWarnings` |

주의: `SeatValue`는 비트 플래그 ID를 쓴다 (`ROW_1_LEFT=1, ROW_1_RIGHT=4, ROW_2_LEFT=16, …`). `SeatArea(SeatValue...)` 생성자에는 좌석 하나씩 넣어 좌석별로 등록한다.

## 6. 퍼미션

선언 형식 `pleos.car.permission.{상수}` (`PleosPermission` 클래스에서 확인).
엔진 입력에 필요한 읽기 권한: `CAR_SAFETY`, `CAR_SEATS`, `CAR_DOORS`, `CAR_LIGHTS`, `CAR_DRIVE`, `CAR_ENERGY`, `CAR_INFO`, 그리고 `android.car.permission.CAR_SPEED`.
SDK 자체가 `ai.pleos.playground.service.vehicle.VEHICLE_BINDING`을 매니페스트에 병합한다.
어느 메서드가 어느 권한을 요구하는지는 SDK 어노테이션(`RequiresCustomPermission`)으로 보아야 하며 여기서는 확인하지 않았다. TODO(VERIFY)

## 7. 남은 TODO(VERIFY)

1. 대상 차량·시뮬레이터의 Vehicle 서비스 버전 (2.3.2는 서비스 2.3.13 이상 요구)
2. `getLowTirePressure`의 `Float` 의미와 단위
3. 도어 위치 정수값의 닫힘 기준
4. 조향각 부호 (오른쪽이 양수인지. 엔진 기본값은 양수 = 오른쪽)
5. 비상등 추정 방법 (`TURN_SIGNAL_LEFT` + `TURN_SIGNAL_RIGHT` 동시 점멸)
6. 에어백 콜백이 시동 직후 현재 값을 한 번 주는지 여부
7. `TelltaleData.color` 정수와 `TelltaleColor`의 관계
8. `roadType`, `roadName`, `kmPost`의 데이터 출처 (아래 8절)

## 8. 위치 API

`ai.pleos.playground:FusedLocation:1.1.3`, `NaviHelper:3.0.5`, `navi-data:3.0.4`의 AAR에서 확인했다.

| API | 주는 값 | 엔진 필드 |
|---|---|---|
| `FusedLocation` → `LocationData` | `latitude`, `longitude`, `altitude`, `heading`, `confidence`, `timestampMillis` | `lat`, `lng`. 오차(m)는 없다. `confidence`와의 관계 TODO(VERIFY) |
| `FusedLocation.isSupported()` | 지원 여부 | `LOCATION` capability |
| `NaviHelper.getCurrentLocationInfo()` → `onCurrentLocationInfo(CurrentLocationInfo)` | `latitude`, `longitude`, `heading`, `address`, `routeId`, `currentRouteIndex` | `address` |

**도로 종류·도로명·km 지점은 어느 SDK도 주지 않는다.** 그래서 `roadType`은 `UNKNOWN`이고, 엔진은 사용자에게 고속도로인지 묻는다. 2504 안내까지 쓰려면 별도 지도 데이터가 필요하다.

현재 앱의 `LiveSignalSource`는 차량 서비스 없이도 동작하도록 Android `LocationManager` + `Geocoder`를 쓴다. 차량에서는 같은 `SignalSource` 뒤에서 `FusedLocation`과 `NaviHelper` 주소로 바꾸면 된다 (미구현, 실차·시뮬레이터 필요).


## 9. Pleos 에뮬레이터에서 확인한 것 (2026-11-12)

- **좌석 점유 값 해석 불일치 (TODO(VERIFY), Pleos 문의 필요):** SDK 2.3.2의 `getCurrentSeatOccupancy`/`registerSeatOccupancy`는 원본 값이 `1`일 때 `true`(탑승)로 바꾼다(`SeatImpl` 바이트코드 확인). Pleos Connect 에뮬레이터 VHAL은 Android 표준(`0`=모름, `1`=빈 좌석, `2`=탑승)대로 보내서, 빈 좌석이 모두 "탑승"으로 보인다. 실차가 어느 쪽을 따르는지 확인해야 한다. 앱은 SDK가 준 true/false만 받으므로 앱에서 고칠 수 없다.
- **차속 API 없음:** Vehicle SDK에는 실제 차속 API가 없다(`DrivingMode`는 크루즈 목표 속도만). 차속은 Android 표준 `PERF_VEHICLE_SPEED`(권한 `CAR_SPEED`)를 쓰고, 받지 못하면 GPS 속도로 대신한다.
- **충격·가속도 API 없음:** 충돌 관련 신호는 에어백 전개뿐이다. 접촉 사고 감지를 위해 차량 화면 기기의 가속도 센서(`ImpactSensorSource`)를 쓴다. 화면 장착 방향(기기 -Z = 차 앞, +X = 오른쪽)은 실차에서 확인해야 한다.
- **ADAS 신호:** `PleosPropertyIds`에 `FORWARD_COLLISION_WARNING_STATE`, `AUTOMATIC_EMERGENCY_BRAKING_STATE`, `BLIND_SPOT_WARNING_STATE`, `AUTO_EMERGENCY_SIGNAL_LIGHTS_SWITCH` 등의 이름은 있으나 공개 Vehicle SDK에는 구독 함수가 없다. 에뮬레이터에 `ai.pleos.playground.service.adas` 서비스가 있으므로 ADAS SDK를 받으면 `Signal.AdasWarning`으로 넣는다(엔진·시나리오 S16은 준비됨).
