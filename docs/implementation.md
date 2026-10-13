# TXT MVP 구현 구조와 실행

2026-10-13. 이 문서는 실제 코드의 구조입니다. 제품 요구는 [plan.md](../plan.md), 실행 결과는 [검증 기록](releases/0.1.0-verification.md)을 따릅니다.

## 실행 환경

- Android 11(API 30) 이상, compile/target SDK 36, JDK 17.
- Gradle 8.13, AGP 8.13.2, Kotlin 2.2.21. 전체 버전은 `gradle/libs.versions.toml`에 고정했습니다.
- Android Studio에서 저장소를 열고 SDK 36과 Build Tools 35.0.0을 설치합니다. `local.properties`에 본인의 SDK 경로를 지정합니다.
- CLI에서는 `JAVA_HOME`, `ANDROID_HOME`을 지정한 뒤 `scripts/build.sh`를 사용합니다. 개인 도구가 `.tools/`에 있다면 그 경로를 우선합니다. 다른 프로젝트의 소스·설정 파일은 필요하지 않습니다.
- Windows에서는 `scripts/build.ps1`과 `gradlew.bat`를 사용합니다. `.tools/env.ps1`에 개인 환경 변수를 둘 수 있으며 SDK 경로는 `ANDROID_HOME`, `ANDROID_SDK_ROOT`, `local.properties`를 지원합니다.

```sh
scripts/build.sh :core:txt:test :app:lintDebug :app:assembleDebug
```

디버그 APK는 `app/build/outputs/apk/debug/app-debug.apk`입니다. 앱 ID는 `com.poyal.yeobaek.debug`이며 개인 검증용 정식 APK와 나란히 설치할 수 있습니다.

```sh
scripts/create-signing.sh
scripts/build.sh :app:assembleRelease
```

서명 스크립트는 최초 한 번만 키를 만들며 기존 키를 덮어쓰지 않습니다. `.tools/signing/`과 `signing.properties`는 Git에서 제외합니다. 업데이트를 위해 이 키는 별도로 보관해야 합니다. 서명 정보가 없는 환경에서는 unsigned release가 생성됩니다. APK에 서명 비밀번호를 넣지 않습니다.

Windows의 대응 명령은 `.\scripts\create-signing.ps1`과 `.\scripts\build.ps1 :app:assembleRelease`입니다. 기존 배포 키가 없는 PC에서 새 키를 생성하면 과거 서명 APK를 덮어 설치할 수 없습니다. 현재 설치의 데이터를 유지해야 한다면 과거 키를 복원하여 빌드합니다.

## 모듈과 상태

| 모듈/위치 | 책임 |
| --- | --- |
| `:core:txt` | 스트리밍 디코딩, 정규 본문·디스크 인덱스, SHA-256, 검색, 캐시 용량 제한 |
| `app/data` | SAF 문서 조회, Room 책·위치·책갈피·세션, 독립된 앱/TXT DataStore |
| `AppViewModel` | 폴더 조회·열기·검색 취소, 최신 요청 반영, 세션 저장과 복구 |
| `app/rendering` | 글꼴·폭별 화면 블록과 줄 경계, 디스크 체크포인트, 측정 LRU |
| `app/ui` | 확정 색상·리듬 심볼·앱 화면·TXT 독서·보조 시트 |
| `app/update` | GitHub 릴리즈 확인, APK 다운로드·검증·설치 준비, 독립된 업데이트 DataStore |
| `:e2e` | 앱 밖의 UIAutomator 프로세스에서 SAF·PID 교체·복구·캡처·부하 검사 |
| `:benchmark` | 최적화 빌드에 대한 FrameTimingMetric 및 열기 트레이스 |

첫 버전은 TXT만 연결합니다. 앱 기능과 TXT 표시 옵션의 저장소는 분리했습니다. PDF·이미지용 빈 메뉴나 추상 엔진을 미리 추가하지 않았습니다. 다른 형식이 실제로 추가될 때 현재 TXT의 문자 Locator와 별도의 페이지/이미지 Locator를 도입합니다.

## 폴더와 기록

SAF의 선택 폴더 하나만 기억하고 하위 폴더는 진입할 때 조회합니다. 원본에는 쓰지 않습니다. 재실행 시 설정한 루트로 돌아오며, 독서 중 종료한 세션만 이어 읽기 후보로 표시합니다. 원본 접근 불가 시 기록을 보존하고 파일 다시 찾기를 제공합니다. 명시적으로 독서 화면을 나가거나 폴더 보기를 선택하면 후보를 소비합니다.

책의 정체성은 공급자 authority와 document ID입니다. 내용 해시가 같아도 별도 문서를 자동 병합하지 않습니다. 다시 찾기로 재연결할 때는 기존 책 ID와 기록을 유지하고, 다른 책의 문서로 합쳐지는 연결은 거절합니다. 재열기에도 원본 전체를 확인하며, 내용이 바뀌면 이전 기록을 보관한 채 문맥/진행률 이전에 동의를 받습니다.

본문 위치는 UTF-16 오프셋입니다. 같은 레이아웃에서 줄 내부 비율을 보조 복원하고, 글꼴·폭이 달라지면 그 문자가 포함된 줄로 이동합니다. 300ms 정지 저장, 1초 주기 저장, `onStop` 저장을 조합합니다. 강제 종료 직전 미저장 구간까지 저장을 보장하지는 않습니다. Room 트랜잭션의 세션 revision과 sequence로 이전 요청이 새 기록이나 삭제한 후보를 되살리지 못하게 합니다.

## 메모리와 본문 보존

원본은 최대 100MiB의 임시 파일로 스트리밍 확인한 뒤 정규 UTF-8 파일로 준비합니다. BOM 제거와 CRLF/CR→LF 외에는 빈 줄·들여쓰기를 보존합니다. 실패·취소된 임시 파일은 제거하고 완성된 버전만 연결합니다. 캐시는 해시로 손상을 검출해 재생성하며 정상 버전 정리 목표는 256MiB입니다. 현재 읽는 버전과 새로 준비한 버전을 보호하는 동안에는 일시적으로 초과할 수 있으며 다음 준비/캐시 정리에서 회수합니다. 준비 중 임시 원본과 작업 파일만큼 추가 저장 공간이 필요합니다.

디스크 인덱스는 8,192 UTF-16 단위 내외의 고정 24바이트 레코드입니다. 본문 전체를 String이나 줄별 객체 목록으로 만들지 않습니다. 화면 블록은 약 2,048자와 64줄 이내에서 줄/문단 경계에 맞추고, 보이는 주변 최대 24블록과 측정 LRU 32개를 유지합니다. 단일 블록 측정 입력은 최대 4,096자입니다. 거대한 단일 grapheme은 오류로 안내하며 강제로 분리하지 않습니다.

아직 방문하지 않은 긴 문단 중간으로 이동할 때는 해당 문단 앞에서 줄 경계를 준비하므로 일반 문단보다 오래 걸릴 수 있습니다. 취소 가능한 코루틴과 로딩 표시를 제공하며, 이후에는 레이아웃별 체크포인트를 활용합니다.

검색은 KMP로 32K 문자 창을 읽고 일치 오프셋을 디스크에 기록합니다. UI는 한 번에 100개 결과만 표시하며 256 code point까지의 정확한 부분 문자열을 지원합니다. 결과 인덱스가 32MiB를 넘으면 더 긴 검색어를 요청합니다. 검색 강조는 표시 주변 본문만 읽습니다.

결과 목록의 시작 번호와 이전/다음으로 선택한 결과 번호는 별도 상태입니다. 100개 경계를 넘어 탐색해도 목록에서 누른 문장의 오프셋으로 이동합니다. 새 책 열기·독서 종료는 검색 세대를 무효화하고, 위치 이동은 이전 창의 선행 로드를 취소합니다. 폴더 화면으로 앱이 복귀하면 현재 하위 폴더를 다시 조회합니다.

## 디자인 적용

`design/final/`의 색상과 확정 리듬 SVG 경로를 네이티브 리소스로 옮겼습니다. 런처는 adaptive icon과 monochrome을 제공합니다. 앱 테마는 SYSTEM/LIGHT/DARK, TXT 본문은 FOLLOW_APP/LIGHT/SEPIA/DARK로 별도 저장합니다. 본문·헤더·푸터·시스템 바는 실제 본문 색상에 맞추고 보조 시트는 앱 테마를 따릅니다. Android 11의 3버튼 탐색 영역도 앱 배경이 이어지도록 대비용 기본 scrim을 해제했습니다.

기본값은 Noto Serif CJK KR 명조 18sp, 행간 1.85, 여백 24dp입니다. OFL 원문을 APK와 저장소에 포함했습니다. 시스템 고딕으로 바꿀 수 있습니다. 브라우저 시안의 OS 글꼴을 기기에 안정적으로 표시하기 위해 번들 명조를 사용하므로 개별 글리프 모양은 웹 시안과 완전히 같지는 않습니다.

메뉴는 본문 위에 겹칩니다. 짧은 탭만 메뉴를 토글하며 숨김 여부에 따라 본문 폭·패딩을 바꾸지 않습니다. 상단 퍼센트, 하단 다섯 동작, 별도 이동 시트의 미리보기/적용/취소를 구현했습니다. 큰 시스템 글씨에서는 버튼 높이와 두 줄 라벨을 허용합니다.

## 테스트 실행

앱 정보·문의와 업데이트는 페리로그의 흐름을 여백의 공통 UI에 맞춰 적용했습니다. 조회 주소는 `https://api.github.com/repos/poyal/yeobaek/releases/latest`입니다. 앱 프로세스별 안내는 한 번, 자동 조회는 마지막 시도부터 24시간 간격이고 수동 확인에는 중복 요청을 막는 5초 간격만 적용합니다. 읽기·이어 읽기 확인·오류 대화상자를 가리지 않도록 업데이트 안내는 폴더·설정 화면에서 표시합니다.

DownloadManager의 전송 ID와 전체 릴리즈 정보(버전·주소·크기·해시)를 연결하고, 지난 버전의 다운로드나 늦은 콜백이 새 버전의 설치 버튼을 활성화하지 못하게 합니다. Download 폴더 원본은 보존하고 설치할 바이트만 앱 전용 캐시로 복사하면서 재검증합니다. FileProvider는 이 캐시 폴더만 공유합니다. Android 설치 권한은 사용자가 설치할 때 요청합니다. release는 HTTPS만 사용하며 debug의 로컬 통합 검사에만 `127.0.0.1` HTTP를 허용합니다.

Windows에서 `.\scripts\test-updates.ps1 -Serial emulator-5580`은 작은 고버전 debug 서명 APK를 만들어 에뮬레이터 내부 HTTP 서버로 내려받습니다. 실제 DownloadManager·사설 복사본·설치 화면 취소·완료 파일 보존·변조 거절과 Compose 업데이트 상태를 검사합니다. 테스트용 설치 화면을 열지만 테스트 APK를 설치하지 않습니다. `-PupdateFixture`일 때만 통합 검사 코드와 APK 자산을 포함하며 release에는 포함하지 않습니다.

Windows에서 Emulator와 `system-images;android-36;default;x86_64`를 설치한 SDK 경로를 `scripts/start-emulator.ps1 -SdkPath <SDK 경로>`에 전달하면 창이 있는 전용 `yeobaek-api36` AVD를 만들고 실행합니다. AVD 데이터는 `.tools/avd/`에 있으며 기존 AVD를 초기화하지 않습니다. 개인 환경 파일의 `YEOBAEK_EMULATOR_SDK`도 지원합니다. 기본 시리얼은 `emulator-5580`입니다.

샘플은 생성 후 `adb -s emulator-5580 push .tools/fixtures/Yeobaek /sdcard/Download/`로 복사합니다. 앱의 시작 폴더 선택에서 `Download/Yeobaek`을 연결합니다. `--large`를 사용하면 1,021개 합성 파일, 약 266MiB가 생성되며 1,000개 파일은 별도 하위 폴더에 있습니다. 원본 편집 복구 검사도 이 합성 폴더만 사용합니다.

```sh
python3 scripts/generate-fixtures.py --large
scripts/build.sh :app:assembleDebugAndroidTest :app:assembleBenchmark :e2e:assembleDebug :benchmark:assembleBenchmark
```

합성 자료를 전용 `yeobaek-*` AVD의 `/sdcard/Download/Yeobaek`으로 push합니다. 빈 폴더는 ADB push가 생략하므로 검증 스크립트가 생성합니다. 테스트는 대상 앱 데이터를 초기화하므로 `verify-device.py`는 실물 기기와 다른 이름의 AVD 실행을 거절합니다. AVD 재부팅 후에는 실제 화면 크기를 다시 확인합니다. 기준 캡처는 720×1600 / density 320이며, 실행 manifest가 기록한 크기를 우선합니다.

```sh
python3 scripts/verify-device.py --serial emulator-5580 --suite engine
python3 scripts/verify-device.py --serial emulator-5580 --suite about --variant release
python3 scripts/verify-device.py --serial emulator-5580 --suite e2e --variant release --theme LIGHT
python3 scripts/verify-device.py --serial emulator-5580 --suite e2e --variant release --theme DARK
python3 scripts/verify-device.py --serial emulator-5580 --suite recovery --variant release
# DARK E2E 직후 덮어 설치, 이어서 에뮬레이터 재부팅 후 같은 명령으로 기록 보존 확인
python3 scripts/verify-device.py --serial emulator-5580 --suite restore --variant release
python3 scripts/verify-device.py --serial emulator-5580 --suite load --variant benchmark
python3 scripts/verify-device.py --serial emulator-5580 --suite soak --variant benchmark
python3 scripts/verify-device.py --serial emulator-5580 --suite repeat --variant benchmark
python3 scripts/verify-device.py --serial emulator-5580 --suite macro --variant benchmark
```

같은 에뮬레이터에 두 UI 검사를 동시에 실행하지 않습니다. `python3 -m pip install -r scripts/requirements.txt`로 화면 보고서 도구를 준비할 수 있습니다. 통과한 E2E 실행 폴더를 `python3 scripts/screenshot-report.py <run> ...`에 전달하면 필수 화면·조작·색상 검사와 오프라인 갤러리를 생성합니다.

`python3 scripts/performance-report.py <run> ...`는 통과한 실행의 원시 로그와 표본을 보존하고 프레임·열기 P50/P95/P99, 기한 초과율, Heap Alloc을 산출합니다. 프레임 표본은 반복 전체를 합쳐 선형 보간하며 Java+native Heap Alloc과 프로세스 PSS를 구분합니다.

원시 결과·APK 해시·기기·PNG·UI 계층은 `.tools/verification/<run>/`에 저장합니다. 대용량·벤치마크는 요청한 파일 이름이 독서 헤더와 일치하는지도 확인합니다. instrumentation 종료 코드만 보지 않고 실제 `OK (N tests)`와 실패/중단을 검사합니다. `soak`는 실제 30분, `repeat`는 실제 100회이며 시간을 단축한 결과로 대체하지 않습니다.

실물 성능 검사는 별도 승인된 테스트 기기에서 같은 테스트 APK의 instrumentation을 실행합니다. `--suite macro`는 에뮬레이터 오류를 측정 목적으로만 완화합니다. 이런 결과를 실제 60/120Hz 성능 통과 근거로 사용하면 안 됩니다. [공식 측정값 정의](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-metrics), [Baseline Profile 적용·측정 조건](https://developer.android.com/topic/performance/baselineprofiles/overview)을 따릅니다.

## Baseline Profile

`ReaderProfile`이 실제 SAF 연결·본문 스크롤·설정·검색 경로를 실행합니다. 수집용 `profile` 변형은 난독화를 하지 않으며 독립된 앱 ID를 사용합니다. 실행에서 얻은 여백 코드의 1,304개 규칙을 `app/src/main/baseline-prof.txt`에 포함했습니다. Compose 등 라이브러리 규칙은 각 AAR이 제공하는 프로필과 병합합니다. 수집한 전체 23,334개 규칙을 중복 포함한 빌드는 DEX 처리 시간이 길어져 앱 코드만 유지했습니다.

```sh
scripts/build.sh :app:assembleProfile :benchmark:assembleBenchmark
python3 scripts/verify-device.py --serial emulator-5584 --suite profile --variant profile
```

생성된 `ReaderProfile_reading-baseline-prof.txt`를 검토 후 앱의 baseline-prof.txt에 반영합니다. 측정 빌드에서는 `BaselineProfileMode.Require`로 실제 설치를 요구하며, 열기 비교는 `CompilationMode.None`과 각각 30회 측정합니다. 동일한 기기에서 측정한 원시 표본으로만 비교하고, 에뮬레이터 수치를 실물 개선율로 일반화하지 않습니다.
