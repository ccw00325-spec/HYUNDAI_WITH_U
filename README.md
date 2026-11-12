# WiTH U (윗유)

> **사고 순간의 ‘내 운전’을 차량 데이터로 증명하는 Pleos Connect 앱**
> 주행 신호를 7일간 조용히 기록하다가, 사고가 나면 직후 대응·신고·진술을 돕고, 앞뒤 5분 기록을 보험 담당자가 한눈에 보는 리포트(QR·웹·PDF)로 만듭니다.

팀 WiTH U · 대표자 조찬우, 팀원 백서율 · 2026 현대자동차그룹 차량용 앱 개발 공모전

---

## 1. APK 위치

| 항목 | 경로 |
| --- | --- |
| 실행용 release APK | [`apk/WiTHU_WiTHU_release.apk`](apk/WiTHU_WiTHU_release.apk) |
| 패키지 이름 | `com.withu.app` (versionName 1.0.0, versionCode 1) |
| 서명 | release 키 서명 (APK Signature Scheme v2) |
| 지원 ABI | x86_64(에뮬레이터), arm64-v8a(실차) |

파일명 규칙 `팀명_앱이름_release.apk`에 맞춰, 팀명과 앱 이름이 모두 ‘WiTH U’라서 공백을 뺀 `WiTHU_WiTHU_release.apk`로 올렸습니다.

## 2. 핵심 기능

| 기능 | 내용 |
| --- | --- |
| 상시 기록 | 속도·기어·브레이크·방향지시등·조향각·전조등·와이퍼·운전석 문·위치를 10Hz로 기록. 1분마다 묶어 앞 묶음의 확인값(SHA-256)을 이어 붙이고 차량 안 Keystore 키(ECDSA P-256)로 서명 |
| 보관 정책 | 모든 운행은 끝난 뒤 **7일** 지나면 자동 삭제(secure_delete). 사고로 확인했거나 리포트를 발급한 운행만 **1년** 보존 |
| 사고 확인 | **급제동(1초 −15km/h & 브레이크) → 10초 안 정차 → 정차가 이어진 채 P단**일 때만 “지금 사고가 난 상황인가요?”를 묻습니다. 신호 대기처럼 D단으로 섰다가 다시 출발하면 묻지 않습니다. 홈의 **사이렌 버튼**으로 언제든 직접 알릴 수 있습니다 |
| 대응 안내 | ‘예’를 누르면 위치(FusedLocation·NaviHelper)로 도로를 판단해 순서대로 안내(TTS). 고속도로는 대피 먼저. 운전석 문이 열리면 안내를 멈추고, 닫히면 이어 갑니다 |
| 신고 가이드 | 위치·시각·상황·내 차·현재 조치를 자동으로 정리하고 112·119·보험사 번호와 함께 읽어 줍니다 |
| 진술 녹음 | “11월 13일 오전 8시 46분경, ○○ 부근에서…”처럼 날짜·시각·장소를 채운 첫 문장과 질문 5개를 보여 주고, 녹음(WAV)과 Pleos STT 받아쓰기를 함께 저장. 음성 파일의 SHA-256을 남깁니다 |
| 사고 리포트 제작 | 날짜와 시각만 고르면 앞뒤 5분 기록을 꺼내 해시 사슬·서명을 검증하고 리포트를 발급. 고른 시각 ±2분 안의 급제동·사이렌·사고 확인이 있으면 그 시각으로 맞춥니다. 직후 5분이 지나야 발급, 같은 시각은 다시 결제하지 않습니다 |
| QR · 웹 · PDF | 차량 화면의 QR을 휴대폰으로 찍으면 [웹 리포트](https://ccw00325-spec.github.io/HYUNDAI_WITH_U/viewer/)가 열리고, 같은 양식을 A4 PDF로 저장할 수 있습니다. 웹 리포트는 브라우저에서 해시 사슬과 차량 서명을 다시 계산해 원본 여부를 표시합니다 |
| 비용 | 제휴(B2B) 보험사 가입자는 보험사가 건당 비용을 부담하고, 그 밖에는 개인이 건당 결제합니다(시연판은 결제 연동 없이 전체 공개) |
| 유디 버블 | 화면을 최소화해도 다른 앱 위에 버블이 남아 기록 중임을 알리고, 누르면 홈(사이렌)으로 돌아옵니다. 사고 확인 대기 중에는 주황 테두리로 바뀝니다 |

리포트는 과실 비율을 판단하지 않고, 차량이 기록한 조작 신호를 사실 그대로 보여 줍니다.

## 3. 테스트 환경

| 항목 | 값 |
| --- | --- |
| 에뮬레이터 | **Pleos Connect Emulator v2.1.0** (Android Automotive 14 / API 34, user build) |
| 화면 규격 | **2560 × 1440**, density 160 · 앱 영역 **1682 × 1210**(단독 실행, 가로) · 내비 옆 분할 시 841 × 1210(세로) |
| AVD 설정 | RAM 3–4GB, 데이터 파티션 6GB, `hw.gpu.mode=host` |
| 빌드 | JDK 21(소스·타깃 17) · Gradle 8.11.1(wrapper 포함) · AGP 8.7.3 · Kotlin 2.0.21 · compileSdk 34 · minSdk 28 |
| Pleos SDK | Vehicle 2.3.2 · FusedLocation 1.1.3 · NaviHelper 3.0.5 · TextToSpeech 3.3.1 · SpeechToText 3.3.1 (Pleos 공개 Maven) |
| 그 밖의 라이브러리 | Jetpack Compose(BOM 2024.09.03) · sqlcipher-android 4.6.1 · ZXing core 3.5.3 · Pretendard 글꼴(SIL OFL 1.1, `licenses/`) |

## 4. 실행 방법

### APK로 바로 실행
```bash
adb install -r -g apk/WiTHU_WiTHU_release.apk
# 유디 버블(다른 앱 위 표시) 허용 — Pleos 설정 화면에 해당 메뉴가 없어 adb로 허용합니다
adb shell appops set --user 10 com.withu.app SYSTEM_ALERT_WINDOW allow
adb shell am start --user 10 -n com.withu.app/.MainActivity
```
- 처음 실행하면 기록 항목·보관 기간을 설명하는 화면이 나오고, ‘동의하고 시작하기’를 누르면 마이크·속도·위치 권한을 묻습니다.
- 한 번 실행한 뒤에는 시동(부팅)만으로 기록이 자동 시작됩니다(BOOT_COMPLETED).

### 소스에서 빌드
```bash
# 경로에 한글이 없는 폴더에서 빌드하세요 (Android Gradle Plugin 제약)
./gradlew :core:test            # 순수 로직 단위 테스트 (66개)
./gradlew :core:pitest          # 변이 테스트 (PIT)
./gradlew :app:assembleDebug    # 디버그 APK
./gradlew :app:assembleRelease  # release APK — keystore.properties 필요(저장소에 포함하지 않음)
```

### Virtual Console로 사고 상황 재현
Virtual Console 내부 HTTP API(포트 8088)로 차량 신호를 넣습니다. 예시:
```bash
adb forward tcp:8088 tcp:8088
# 주행 → 급제동 → 정차 → P단  ⇒ 사고 확인 카드
curl -X PUT "http://127.0.0.1:8088/vehicle/gear?gear=Drive"
curl -X PUT "http://127.0.0.1:8088/vehicle/controls?speed=64&accelerator=20&brake=0&steering=0"
curl -X PUT "http://127.0.0.1:8088/vehicle/turn-signal?mode=BlinkRight"
curl -X PUT "http://127.0.0.1:8088/vehicle/controls?speed=0&accelerator=0&brake=100&steering=0"
curl -X PUT "http://127.0.0.1:8088/vehicle/gear?gear=Park"
```
시연·시험용 명령 수신기(`com.withu.app.CMD`)는 `android.permission.DUMP` 권한으로 보호되어 adb shell에서만 호출됩니다.
```bash
adb shell am broadcast -n com.withu.app/.CmdReceiver -a com.withu.app.CMD --es cmd siren
adb shell am broadcast -n com.withu.app/.CmdReceiver -a com.withu.app.CMD --es cmd report --es arg "2026-11-13 08:46:51"
```

## 5. 필요한 설정

| 설정 | 이유 |
| --- | --- |
| 런타임 권한: `CAR_SPEED`, `FUSED_LOCATION`, `RECORD_AUDIO`, `POST_NOTIFICATIONS` | 속도 기록, 사고 위치, 진술 녹음, 기록 중 알림 |
| `SYSTEM_ALERT_WINDOW` (appops) | 유디 버블. 허용하지 않아도 기록·사고 확인은 동작하고 버블만 표시되지 않습니다 |
| 설정 화면: 차종·색·번호, 가입 보험사 | 신고 가이드 문장과 리포트 비용 부담 주체(제휴 보험사 여부)에 쓰입니다 |

## 6. 저장소 구조

```
core/   순수 Kotlin 로직 (사고 판단·대응 순서·해시 사슬·보관 정책·리포트 사실 추출·과금 주체) + 단위 테스트 + PIT
app/    Android Automotive 앱 (Compose 화면, 포그라운드 기록 서비스, SQLCipher 저장소, 유디 버블)
docs/viewer/   QR로 여는 웹 리포트 (GitHub Pages) — 해시 사슬·ECDSA 서명 브라우저 검증, A4 인쇄(PDF) 양식
docs/viewer/reports/   에뮬레이터에서 발급한 시험 리포트
apk/    제출용 release APK
docs/   검증 보고서
```

## 7. 알려진 한계

- 에어백 전개 신호는 에뮬레이터가 지원하지 않습니다(`ERROR_NOT_SUPPORTED_PROPERTY`). 실차에서는 지원 차종일 때 질문 없이 사고로 처리합니다.
- 에뮬레이터 마이크는 실제 음성을 받지 못해, 받아쓰기는 저장된 진술 음성 파일을 넣어 확인했습니다.
- 결제(PG)와 리포트 서버는 출시 단계 범위입니다. 지금은 시험 리포트를 GitHub Pages에 올려 QR → 웹 리포트 흐름을 보여 줍니다.
- 비상등·가속 페달·충격 세기·차선 종류·상대 차량은 차량 SDK로 받을 수 없어 기록하지 않습니다.

## 8. 검증 결과

| 항목 | 결과 | 자료 |
| --- | --- | --- |
| 에뮬레이터 기능·화면 검증 | 39개 항목 중 통과 30 · 수정 후 통과 5 · 조건부 2 · 미지원·미검증 2 | [검증 보고서 PDF](docs/WiTHU_verification_report.pdf) |
| 화면 캡처 | release APK를 Pleos Connect Emulator v2.1.0에서 실행한 실제 화면 | [docs/screenshots](docs/screenshots) |
| 단위 테스트 | `core` 66개 모두 통과 | `./gradlew :core:test` |
| 변이 테스트(PIT) | 376개 중 365개 제거(97%), 테스트 미실행 0, 남은 11개는 동등 변이 | [PIT 리포트](docs/mutation/pit_report/index.html) · [보강 전후 비교](docs/mutation/compare_before_after.json) |
| 웹 리포트 원본 확인 | 해시 사슬·차량 서명(ECDSA P-256)·표본 일치를 브라우저에서 다시 계산 | [원본 리포트 예시](https://ccw00325-spec.github.io/HYUNDAI_WITH_U/viewer/?r=R-20261113-011502-3173&h=53616dd59eb2d904) |
| 위·변조 탐지 | 값 1개만 바꿔도 ‘검증 실패’ | [표본 조작 예시](https://ccw00325-spec.github.io/HYUNDAI_WITH_U/viewer/?r=TAMPER-SAMPLE) · [묶음 조작 예시](https://ccw00325-spec.github.io/HYUNDAI_WITH_U/viewer/?r=TAMPER-CHAIN) |
