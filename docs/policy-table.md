# 정책 테이블 (자동 생성)

`engine/src/main/resources/policy/policy_v1.json`에서 생성했다. 직접 고치지 말고 `./gradlew :engine:generatePolicyDoc`로 다시 만든다.

- 버전: `1.0.0`, 시행일: 2026-11-07

## 임계값

| 이름 | 값 |
|---|---|
| `ringBufferMs` | 60000 |
| `noResponseTimeoutMs` | 30000 |
| `hardStopWindowMs` | 3000 |
| `hardStopDropKmh` | 30.0 |
| `hardStopToZeroMs` | 10000 |
| `stopSpeedKmh` | 0.5 |
| `movingSpeedKmh` | 5.0 |
| `stopHazardWindowMs` | 30000 |
| `preImpactWindowMs` | 5000 |
| `steeringMinDeg` | 5.0 |
| `steeringRightPositive` | true |
| `deltaVWindowMs` | 2000 |
| `deltaVS2Kmh` | 25.0 |
| `roadTypeMinConfidence` | 0.7 |
| `warningBaselineMs` | 5000 |
| `farFromHomeKm` | 100.0 |
| `evLowSocPercent` | 5.0 |
| `batteryEstimateRangePct` | 15 |
| `allow2504WhenSizeUnknown` | true |
| `coordinateMaxAccuracyM` | 100.0 |
| `scriptZoneMinConfidence` | HIGH |
| `impactMinG` | 1.2 |
| `impactHighG` | 2.5 |
| `impactS2G` | 4.0 |
| `impactCooldownMs` | 10000 |
| `impactAxisRatio` | 1.5 |
| `adasStopWindowMs` | 10000 |
| `softStopDropKmh` | 15.0 |
| `softStopWindowMs` | 4000 |
| `stopDoorWindowMs` | 30000 |

## 트리거 (위가 우선)

| ID | 기본 신뢰도 |
|---|---|
| T_AIRBAG | CONFIRMED |
| T_MANUAL | CONFIRMED |
| T_IMPACT | HIGH |
| T_HARD_STOP | HIGH |
| T_ADAS_STOP | HIGH |
| T_EXPRESSWAY_STOP | MEDIUM |
| T_STOP_HAZARD | MEDIUM |
| T_STOP_DOOR | LOW |
| T_VEHICLE_WARNING | MEDIUM |

## 위험 등급 규칙 (한 줄이라도 맞으면 그 등급)

| 등급 | 조건 (모두 참) | 제외 조건 | 이유 코드 |
|---|---|---|---|
| R0 | 부상 있음 응답 | - | `INJURY_REPORTED` |
| R0 | 화재·연기 있음 응답 | - | `FIRE_SMOKE_REPORTED` |
| R0 | 사고 확인 질문에 무응답 | - | `CONFIRM_NO_RESPONSE` |
| R0 | 심각도 S3 | - | `AIRBAG_DEPLOYED` |
| R0 | 사고 확인됨 + 탑승했는데 벨트 미착용인 좌석 있음 + 심각도 S2 이상 | - | `OCCUPIED_UNBELTED` |
| R0 | 사고 확인됨 + 전기차 + 출력 제한 경고 신규 | - | `EV_POWER_DOWN` |
| R1 | 센서상 고속도로·터널에 정차 (도로 신뢰도 충족) | - | `EXPRESSWAY_STOPPED` |
| R1 | 도로 판정이 불확실하나 사용자가 고속도로라고 응답 | - | `EXPRESSWAY_REPORTED_BY_USER` |
| R1 | 야간·우천 추정 + 도로 위 정차 | - | `NIGHT_OR_RAIN_ON_ROADWAY` |
| R2 | 상대 도주 응답 | - | `OTHER_PARTY_FLED` |
| R2 | 상대 음주·다툼·위협 의심 응답 | - | `OTHER_PARTY_SUSPICIOUS` |
| R2 | 보행자·자전거 관련 (수동 선택) | - | `PEDESTRIAN_OR_BICYCLE` |
| R3 | 사고 확인됨 | R0 판정됨, R2 판정됨 | `ACCIDENT_MINOR` |
| R4 | 사고가 아닌 고장 흐름 + 차량 경고 신규 | - | `VEHICLE_WARNING_NEW` |

## 조치 순서

등급이 여럿이면 R0 > R1 > R2 > R3 > R4 순으로 합치고 중복을 제거한다. R0가 있으면 `CALL_119`가 항상 1순위다.

### R0

| 순서 | 조치 | 조건 |
|---|---|---|
| 1 | `CALL_119` | - |
| 2 | `BLUELINK_SOS_GUIDE` | - |
| 3 | `EVACUATE_GUIDE` | R1 판정됨 |
| 4 | `NOTIFY_GUARDIAN` | 보호자 등록됨 |
| 5 | `CALL_INSURER_CLAIM` | - |

### R1

| 순서 | 조치 | 조건 |
|---|---|---|
| 1 | `EVACUATE_GUIDE` | - |
| 2 | `CALL_2504` | - |
| 3 | `CALL_112` | - |
| 4 | `CALL_INSURER_EMERGENCY` | - |

### R2

| 순서 | 조치 | 조건 |
|---|---|---|
| 1 | `CALL_112` | - |
| 2 | `TAKE_PHOTOS` | - |
| 3 | `CALL_INSURER_CLAIM` | - |
| 4 | `TOW_GUARD` | - |

### R3

| 순서 | 조치 | 조건 |
|---|---|---|
| 1 | `MOVE_TO_SAFETY` | - |
| 2 | `CALL_INSURER_CLAIM` | - |
| 3 | `EXCHANGE_INFO_QR` | - |
| 4 | `TAKE_PHOTOS` | - |
| 5 | `TOW_GUARD` | 견인 필요 |

### R4

| 순서 | 조치 | 조건 |
|---|---|---|
| 1 | `EXPLAIN_WARNING` | - |
| 2 | `CALL_2504` | 고속도로로 판단됨 (센서 또는 사용자 응답) |
| 3 | `CALL_INSURER_EMERGENCY` | 고속도로로 판단됨 (센서 또는 사용자 응답) 아님 |
| 4 | `SERVICE_BOOKING` | - |

## 보험 매칭 (참고 정보)

| 조건 | 청구 후보 | 담보가 없을 때 |
|---|---|---|
| 상대 차량 있음 | DAEMUL, DAEIN_1, DAEIN_2 | - |
| 내 차 손상 | JACHA | JACHA |
| 부상 있음 응답 | JASIN, JASANG | - |
| R0 판정됨 | JASIN, JASANG | - |
| 주행 불가 | EMERGENCY_TOW | - |
| 전기차 + 전기차 배터리 손상 가능성 (하부 충격 또는 출력 제한 경고) | JACHA, EV_BATTERY_NEW_VALUE | EV_BATTERY_NEW_VALUE |
| 전기차 + 전기차 견인 필요 또는 배터리 잔량 부족 | EV_TOW_SOS | EV_TOW_SOS |
| 집에서 먼 곳 | REMOTE_RETURN, TRANSPORT_COST | - |

## 도로·차량 조건

- 고속도로로 보는 도로: EXPRESSWAY_MAIN, EXPRESSWAY_SHOULDER, TUNNEL
- 2504 안내 도로: EXPRESSWAY_MAIN, EXPRESSWAY_SHOULDER
- 2504 안내 차량 크기: SMALL (크기 미등록 허용: true)
- 경고로 보는 경고등: AIRBAG, ABS, ENGINE, ENGINE_OIL_PRESSURE, ENGINE_TEMPERATURE, EV_SYSTEM, HEV_SYSTEM, FCEV_SYSTEM, EREV_SYSTEM, MDPS, TPMS, LOW_VOLT_BATTERY, MASTER_WARNING, RBS, H2_LEAK

## 연락처

- 119: 119
- 112: 112
- 2504: 1588-2504

## 견인 운임표

- 출처: TODO: 국토교통부 구난형 특수자동차 운임·요금표 최신 고시로 팀이 채운다
- 기준일: 미입력
- 행 수: 0

## 사실(Fact) 목록

| 이름 | 뜻 |
|---|---|
| `ACCIDENT` | 사고 확인됨 |
| `FAULT` | 사고가 아닌 고장 흐름 |
| `INJURY_YES` | 부상 있음 응답 |
| `FIRE_SMOKE_YES` | 화재·연기 있음 응답 |
| `CONFIRM_NO_RESPONSE` | 사고 확인 질문에 무응답 |
| `SEVERITY_S3` | 심각도 S3 |
| `SEVERITY_S2_PLUS` | 심각도 S2 이상 |
| `OCCUPIED_UNBELTED` | 탑승했는데 벨트 미착용인 좌석 있음 |
| `EV` | 전기차 |
| `POWER_DOWN_NEW` | 출력 제한 경고 신규 |
| `EXPRESSWAY` | 고속도로로 판단됨 (센서 또는 사용자 응답) |
| `EXPRESSWAY_STOPPED` | 센서상 고속도로·터널에 정차 (도로 신뢰도 충족) |
| `EXPRESSWAY_REPORTED_BY_USER` | 도로 판정이 불확실하나 사용자가 고속도로라고 응답 |
| `NIGHT_OR_RAIN_ON_ROADWAY` | 야간·우천 추정 + 도로 위 정차 |
| `OTHER_PARTY_FLED_YES` | 상대 도주 응답 |
| `OTHER_PARTY_SUSPICIOUS_YES` | 상대 음주·다툼·위협 의심 응답 |
| `PEDESTRIAN_OR_BICYCLE` | 보행자·자전거 관련 (수동 선택) |
| `VEHICLE_WARNING_NEW` | 차량 경고 신규 |
| `DRIVABLE_NO` | 주행 불가 |
| `TOW_NEEDED` | 견인 필요 |
| `TOW_OVER_FREE_KM` | 목적지가 무료 견인 거리 초과 |
| `ELIGIBLE_2504` | 2504 긴급견인 안내 조건 충족 |
| `GUARDIAN_REGISTERED` | 보호자 등록됨 |
| `GUARDIAN_AUTO` | 무응답 + 보호자 사전 동의 |
| `HAS_OTHER_VEHICLE` | 상대 차량 있음 |
| `MY_CAR_DAMAGED` | 내 차 손상 |
| `EV_BATTERY_AT_RISK` | 전기차 배터리 손상 가능성 (하부 충격 또는 출력 제한 경고) |
| `EV_TOW_OR_LOW_SOC` | 전기차 견인 필요 또는 배터리 잔량 부족 |
| `FAR_FROM_HOME` | 집에서 먼 곳 |
| `GRADE_R0` | R0 판정됨 |
| `GRADE_R1` | R1 판정됨 |
| `GRADE_R2` | R2 판정됨 |
| `GRADE_R3` | R3 판정됨 |
| `GRADE_R4` | R4 판정됨 |
