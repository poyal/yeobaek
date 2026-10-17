# TXT·EPUB·PDF·이미지 구현 구조와 실행

2026-10-17. 이 문서는 실제 코드의 구조입니다. 제품 요구는 [plan.md](../plan.md), 배포 APK의 실행 결과·서명·업데이트 검사는 [1.0.0 릴리즈 검증](releases/1.0.0-verification.md)을 따릅니다. 이전 버전 문서의 결과는 당시 산출물의 이력입니다.

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

Build Tools는 35.0.0을 사용합니다. 기본 개발 검사는 아래와 같이 실행합니다.

```sh
scripts/build.sh :core:txt:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

```powershell
.\scripts\build.ps1 :core:txt:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

산출물은 `app/build/outputs/apk/debug/app-debug.apk`와 `app/build/outputs/apk/release/app-release.apk`입니다. 서명 설정 없이 release를 빌드하면 설치용 서명이 없는 `app-release-unsigned.apk`가 생성됩니다. 키와 비밀번호는 Git에 포함하지 않고 별도로 보관합니다.

사용자 매뉴얼의 화면은 [별도 촬영 절차](screenshots/README.md#recapture)에 따라 공개 APK로 만듭니다. 문서용 촬영은 성능·TalkBack 인수나 새 릴리즈의 회귀 검사를 대체하지 않습니다.

## 모듈과 상태

| 모듈/위치 | 책임 |
| --- | --- |
| `:core:txt` | 스트리밍 디코딩, 정규 본문·디스크 인덱스, SHA-256, 검색, 캐시 용량 제한 |
| `app/data` | SAF 문서 조회, Room 책·위치·책갈피·세션, 독립된 앱/TXT/PDF DataStore |
| `AppViewModel` | 폴더 조회·열기·검색 취소, 최신 요청 반영, 세션 저장과 복구 |
| `app/rendering` | 글꼴·폭별 화면 블록과 줄 경계, 디스크 체크포인트, 측정 LRU |
| `app/ui` | 확정 색상·리듬 심볼·앱 화면·TXT/PDF 독서·보조 시트 |
| `app/update` | GitHub 릴리즈 확인, APK 다운로드·검증·설치 준비, 독립된 업데이트 DataStore |
| `:e2e` | 앱 밖의 UIAutomator 프로세스에서 SAF·PID 교체·복구·캡처·부하 검사 |
| `:benchmark` | 최적화 빌드에 대한 FrameTimingMetric 및 열기 트레이스 |

TXT는 문자 오프셋, PDF는 페이지와 페이지 내부 좌표·확대율로 위치를 저장합니다. 공통 폴더·최근 읽기·이어 읽기 흐름에서 실제 파일 헤더를 확인해 해당 뷰어를 엽니다. 기본 필터에는 TXT·PDF가 함께 표시됩니다.

## 폴더와 기록

### v0.4.0 EPUB·설정·TXT 입력 보정

- `app/epub`의 Readium Kotlin 3.1.2 shared·streamer·navigator로 DRM 없는 EPUB 2·3의 일반 본문과 고정 지면을 엽니다. `EpubStore`는 취소 가능한 복사·해시·ZIP 검증·캐시 정리를, `EpubReaderController`는 위치·검색·책갈피와 저장을, `EpubHostFragment`는 Native Fragment/WebView 수명을 담당합니다. [EPUB 구조와 사용법](epub-reader.md)
- Room v5는 기존 테이블을 유지하고 EPUB 위치·책갈피를 추가합니다. 백업 v2에는 EPUB 기록·범위별 설정이 포함되며 기존 v1도 가져옵니다. 원본 변경은 처음부터 열기·취소로 처리하고 옛 기록을 보관합니다.
- 같은 레이아웃의 EPUB 재열기는 저장된 진행률을 사용합니다. 폭·글꼴·읽기 방식이 바뀌면 본문 인용과 문단 앵커를 사용합니다. 렌더러 준비 전 위치와 이전 세션의 늦은 저장은 현재 기록을 덮지 않습니다.
- 설정 첫 화면에서 각 형식으로 바로 이동합니다. `SettingsPage`, `SettingsCard`, `SettingsSection`으로 배경·제목·간격을 통일합니다.
- TXT의 `ReaderFlingGuard`는 실제 이동이 거의 없는 정지 터치 뒤 비정상적으로 큰 속도가 전달될 때만 플링을 차단합니다. 정상 드래그와 플링은 유지합니다. 2026-10-17 사용자 진단 로그의 약 01:13 재현을 바탕으로 추가했으며 제조사 실물 기기 재확인은 남아 있습니다.

EPUB 샘플은 `python scripts/generate-epub-fixtures.py`로 생성하고 `/sdcard/Download/YeobaekEPUB`에 넣습니다. release E2E는 `python scripts/verify-device.py --serial <전용 에뮬레이터> --suite epub --variant release --theme LIGHT`로 실행합니다. 테스트는 지정한 전용 AVD의 앱 데이터를 초기화하므로 개인 기록이 없는 환경을 사용합니다.

### v0.3.0 독서 기능 강화 이력

- `ReaderSettingsStore`는 기존 형식별 DataStore 위에 항목 단위 예외를 합성합니다. 책 ID·실제 부모 문서 식별자를 사용하며, 공통값 수정이 설정하지 않은 항목까지 고정시키지 않습니다. 화면 밝기·회전·화면 유지 옵션은 독서 화면이 활성화된 동안만 적용합니다.
- 목록은 현재 폴더 파일명 검색과 자연 이름순·수정일 내림차순·크기 내림차순을 지원합니다. 다음 항목도 같은 정렬을 사용합니다. 즐겨찾기와 최근 기록을 위해 시작 폴더를 바꾸거나 홈 연결을 해제해도 기존 읽기 권한은 유지합니다.
- 미리보기는 기존 표지 캐시 또는 공급자의 썸네일 지원 플래그를 사용합니다. 목록 진입으로 아카이브를 복사하거나 렌더러를 실행하지 않습니다. 공급자 요청 동시성 2, 메모리 4MiB, 이미 읽은 이미지 표지 디스크 캐시 24MiB로 제한합니다.
- 이미지 자르기는 원본 정규화 좌표를 화면 영역으로 변환하며 원본을 수정하지 않습니다. 자동 자르기는 최대 192px 썸네일에서 흰색/검은색의 확실한 가장자리만 검사합니다. 진행 중인 제스처·플링이 끝난 뒤 앵커를 유지하며 결과를 반영합니다. 현재 페이지 예외는 책 설정보다 우선합니다.
- 펼침면 분할은 세로 화면·좌우 넘김 모드의 가로로 긴 페이지에만 적용합니다. Room v4는 이미지 위치·책갈피에 `segment`(-1 전체/기존, 0 왼쪽, 1 오른쪽)를 추가합니다. 원본 페이지 번호와 x/y는 유지하며 마지막 페이지의 첫 반쪽은 완독이 아닙니다. v1→v2→v3→v4 비파괴 이관을 제공합니다.
- `ReadingBackups`의 버전 1 JSON은 책/각 형식 위치/북마크/설정/즐겨찾기를 포함합니다. 32MiB 한도와 참조 관계·좌표 검증 후 복원 미리보기를 표시합니다. DB 트랜잭션과 재시도 가능한 AtomicFile 저널을 사용해 중간 종료 후 누락된 데이터를 추가합니다. 기존 값이 우선하며 독서 세션 후보·원본·캐시·진단 로그·OS 권한은 복원하지 않습니다.
- `ACTION_VIEW`는 지원 MIME의 content URI를 받습니다. 문서 제공자가 아닌 URI는 OpenableColumns로 조회하고, 영구 권한이 제공된 경우에만 이를 보존합니다. 외부 이미지 한 장은 독립 문서로 취급하고 형제 파일 접근을 가정하지 않습니다. 권한이 만료되면 기존 파일 다시 찾기 동선으로 기록을 재연결합니다.
- 당시 TXT 목차·신규 형광펜/메모·TTS/자동 스크롤/리모컨은 제외했습니다. 당시 보류한 EPUB은 v0.4.0에 추가했습니다. 클라우드 동기화·OCR·자동 칸 인식·사용자 글꼴 가져오기는 계속 제외합니다.

새 UI 검증과 화면 캡처는 `scripts/verify-reader-upgrade.py --serial emulator-5588 --stage capture`를 사용합니다. 지정한 여백 전용 AVD의 **debug 앱 데이터만 초기화**하며 공개 앱에는 접근하지 않습니다. 결과와 실기기 미확인 범위는 [HTML 보고서](reader-upgrade-report/index.html)에 기록합니다.

2026-10-15 검증: Debug 빌드·Lint(오류 0, 경고 49), JVM 48개(앱 39·TXT 코어 9), Android 16 기기 내 통합 16개와 E2E 4개가 통과했습니다. E2E는 기존 독서·인코딩 경로 2개, 외부 앱의 임시 권한 열기 1개, 50MiB TXT 연속 스크롤 1개입니다. 연속 검사는 실제 1,800.364초 동안 1,980회 진행했고 검사 기준상 비정상 역행이나 프로세스 교체가 없었습니다. 앱 PSS는 시작 147.4MiB, 종료 148.1MiB, 수집 표본 최대 166.7MiB입니다. 장시간 검사 이후 백업·폴더·설정 보완을 거쳐 최종 APK의 단위·기기 통합 검사를 다시 통과했습니다. 빌드별 해시와 원시 로그는 보고서 메타데이터에 보존합니다.

백업이 저장된 테스트 AVD에서 `--stage restore-fresh`는 빈 debug 앱으로 가져오기와 원본 재연결을 검사합니다. `ExternalOpenTest` 실행 후 `--stage external`로 해당 테스트의 화면을 모으고 `--stage report`로 모든 화면·표시 좌표·검증 결과를 포함한 HTML을 다시 생성합니다. 보고서는 PC와 430px 화면에서 표시 토글·확대/닫기·가로 넘침을 확인했습니다.

SAF의 선택 폴더 하나만 기억하고 하위 폴더는 진입할 때 조회합니다. 원본에는 쓰지 않습니다. 재실행 시 설정한 루트로 돌아오며, 독서 중 종료한 세션만 이어 읽기 후보로 표시합니다. 원본 접근 불가 시 기록을 보존하고 파일 다시 찾기를 제공합니다. 명시적으로 독서 화면을 나가거나 폴더 보기를 선택하면 후보를 소비합니다.

책의 정체성은 공급자 authority와 document ID입니다. 내용 해시가 같아도 별도 문서를 자동 병합하지 않습니다. 다시 찾기로 재연결할 때는 기존 책 ID와 기록을 유지하고, 다른 책의 문서로 합쳐지는 연결은 거절합니다. 재열기에도 원본 전체를 확인하며, 내용이 바뀌면 이전 기록을 보관한 채 문맥/진행률 이전에 동의를 받습니다.

본문 위치는 UTF-16 오프셋입니다. 같은 레이아웃에서 줄 내부 비율을 보조 복원하고, 글꼴·폭이 달라지면 그 문자가 포함된 줄로 이동합니다. 300ms 정지 저장, 1초 주기 저장, `onStop` 저장을 조합합니다. 강제 종료 직전 미저장 구간까지 저장을 보장하지는 않습니다. Room 트랜잭션의 세션 revision과 sequence로 이전 요청이 새 기록이나 삭제한 후보를 되살리지 못하게 합니다.

### TXT 글꼴과 스크롤 진단

2026-10-16 공개 v0.3.0에 포함했습니다. 아래 2026-10-15의 개발 단계 재현·측정과 최종 배포 APK 검증은 구분합니다.

- 글꼴은 기존 내장 Noto Serif와 시스템 고딕(400)·가는 고딕(300)·중간 굵기 고딕(500)·시스템 명조·고정폭입니다. 추가 폰트 파일이나 다운로드는 없습니다. OS의 한글 fallback 때문에 일부 선택지는 같은 한글 형태로 보일 수 있습니다. 새 `font` 설정이 없거나 알 수 없는 값이면 이전 `serif` 설정을 해석합니다. 본문·미리보기는 `TxtTypography`를 공유하며 `layout-2` 캐시 키에 글꼴 ID와 굵기를 포함합니다.
- `TextWindow`는 실제 측정된 블록 키와 목록 세대가 일치할 때만 읽기 위치를 계산합니다. 선행 로드가 끝나면 현재 보이는 블록을 다시 확인해 주변 24개 수준으로 정리합니다. 큰 화면에 24개보다 많이 보이면 보이는 블록을 우선 보존합니다. 일반 선행 로드는 강제 스크롤을 하지 않으며, 명시적 이동은 새 본문과 `requestScrollToItem`을 같은 측정에 적용한 뒤 실제 위치를 저장합니다.
- 기존의 역방향 선행 로드 후 `take(24)` 정책을 분리해 Android 16 에뮬레이터에서 재현했습니다. 로드가 지연되는 동안 보이는 블록을 옮기면 `33000/17px → 29000/17px`로 네 블록 역행했습니다. 수정 후 같은 테스트는 `33000/17px → 33000/17px`입니다. 이는 특정 앱 내부 경합의 재현이며, 사용자가 실물 기기에서 겪은 모든 역방향 스크롤의 원인이 같다는 증거는 아닙니다.

`ScrollDiagnostics`는 앱 전용 `noBackupFilesDir/txt-scroll-diagnostics/`에 8MiB 파일 16개, 최대 128MiB(약 134MB)를 순환 보관합니다. 앱 재실행 후에도 유지되며 앱 데이터 삭제·제거 시 없어집니다. 메인 스레드에서는 제한된 큐에 넣고 단일 IO 코루틴이 파일 쓰기·회전·내보내기를 순서대로 처리합니다. 내보내기도 스트리밍으로 수행하므로 전체 로그를 메모리에 올리지 않습니다. 기록 실패·큐 초과 개수는 내보낸 파일 머리말에 표시합니다.

기록에는 기기 현지 시각(밀리초·UTC 오프셋), 부팅 후 경과 시간, 앱·기기·Android 버전, 포인터 ID·도구·좌표·압력, 실제 Compose `onPreFling` 속도, 화면·키보드 크기, 레이아웃 세대, 본문 교체 전후 키와 문자 위치가 들어갑니다. 일반 MOVE는 100ms 간격, 위치는 250ms 간격 및 블록 변경·예상 방향 역행 시에 기록합니다. 손가락 뗌 분석을 위해 마지막 64개 좌표 샘플(이벤트의 historical 좌표 포함)을 제스처 종료 때 묶어 기록합니다. `raw_touch_tail`은 앞서 기록한 시각을 다시 포함할 수 있습니다. 책 제목·URI·본문·검색어는 기록하지 않으며 외부 전송은 없습니다. 진단 관찰 자체는 입력을 소비하지 않습니다. 별도 `ReaderFlingGuard`가 정지 터치 뒤의 관성 스크롤을 차단합니다.

2026-10-17 v1.0.0 보완: v0.4.0 실기기 로그의 12:53:16.806에 세로 444px를 움직인 뒤 마지막 약 100ms는 1px만 변한 제스처에서 `vy=30000`, `suppressed=false`가 기록됐습니다. 이후 본문 위치도 역행했습니다. 전체 제스처의 이동 거리만 확인하던 방어에 손을 떼기 직전의 정지 판정을 추가했습니다. 포인터별 최근 100ms와 경계 표본 하나를 유지하고, 80ms 이상 관찰된 위치 범위가 터치 슬롭의 1/4 이하면 플링을 소비합니다. 순이동 대신 최대·최소 범위를 사용해 실제 방향 전환을 보존하며, 같은 밀리초 표본은 최신 좌표로 합칩니다. 입력 큐 지연으로 방어가 미리 만료되지 않도록 이동 분석은 이벤트 시각, 250ms 유효기간은 수신 시각을 사용합니다. 진단에는 `releaseTravelPx`·`releaseDurationMs`를 추가했습니다. 로그의 반올림된 시간·세로 좌표를 공용 테스트 자료로 보존했으며, 해당 갤럭시에서 실제 수정 효과의 재확인은 남아 있습니다.

먼저 비공개 로컬 후보 0.4.1(`versionCode 5`)로 수정과 제한된 회귀 검사를 수행한 뒤, 공개 후보를 1.0.0(`versionCode 6`)으로 올려 별도로 검증했습니다. 현재 공개 후보의 소스·APK 해시와 전체 결과는 [1.0.0 검증 기록](releases/1.0.0-verification.md)에 있습니다. 좌표 재생은 정지 판정과 읽던 위치 유지를 검증하며, 갤럭시의 잘못된 30,000px/s 산출 자체를 에뮬레이터에서 재현했다는 의미는 아닙니다.

**설정 → 앱 정보·문의 → 스크롤 진단 로그 저장**에서 Android 파일 저장기를 통해 `.txt`로 내보냅니다. 재현한 대략의 날짜·시분과 이 파일을 함께 전달하면 같은 시각의 입력·플링·본문 교체·뷰포트 변화를 비교할 수 있습니다. 파일은 자동으로 원격 수집되지 않습니다.

관련 Android 계측 검사 11개(`TextWindowTest`, `TxtFontTest`, `ReaderScrollTest`, `ScrollDiagnosticsTest`, `ReaderEngineTest`)가 Android 16 에뮬레이터에서 통과했습니다. 지연 로드 중 방향 전환, 화면 키·픽셀 보존, 오래된 측정·이동 요청 배제, 실제 TXT의 반복 양방향 스크롤과 긴 문단의 글꼴 변경·연속 이동, 설정 저장·초기화, 로그 시각·마지막 터치 샘플 내보내기를 검사합니다. JVM 검사는 기존 설정 호환과 로그 회전·용량·재개방도 포함합니다. 실제 갤럭시의 터치 하드웨어 원인 여부는 재현 시각의 기기 로그로 별도 판단해야 합니다.

추가 검증: 코어·앱 JVM 42개, debug Lint(오류 0개)·APK 빌드, 실제 앱 `ReaderJourneyTest` 2개가 통과했습니다. `PerformanceJourneyTest#continuousReading30Minutes`는 이번 실행에서 `soakMinutes=1`로 단축해 50MiB TXT를 60.2초 동안 178회 양방향 스와이프했고, 블록 위치가 입력 방향을 거스르지 않는지 확인했습니다. 이 짧은 실행은 30분 안정성 검사나 진단 기능의 성능 오버헤드 비교를 대신하지 않습니다. 앱 PSS는 시작 148.8MiB, 종료 157.8MiB였습니다. 설정 메뉴의 Android 파일 저장기로 실제 내보낸 누적 로그는 882,741바이트이며 `droppedRecords=0`, `writeFailures=0`을 확인했습니다. 원시 결과와 화면은 로컬 `.tools/txt-fix-*`에 보관합니다.

참고로 [Issue 216582726](https://issuetracker.google.com/issues/216582726?hl=tr)은 움직이는 `Modifier.draggable`에서 잘못된 플링 속도를 보고한 실제 수정 이슈입니다. [Compose 1.9.0-alpha02](https://developer.android.com/jetpack/androidx/releases/compose-foundation#1.9.0-alpha02)에도 속도 추적 입력 수정 이력이 있습니다. 현재 프로젝트는 Foundation 1.10.0을 사용하므로 과거 보고를 그대로 현재 원인으로 단정하지 않습니다. [Android 포인터 문서](https://developer.android.com/develop/ui/views/touch-and-input/gestures/multi)는 포인터별 ID 추적을 명시하며, `ACTION_POINTER_DOWN`만으로 손바닥 간섭을 확정할 수 없습니다. [삼성의 자석 액세서리 안내](https://www.samsung.com/ph/support/mobile-devices/what-to-do-when-touchscreen-does-not-recognize-s-pen-input-on-certain-screen-areas/)는 S펜 인식 간섭에 관한 것으로 손가락 좌표 순간이동의 근거와 구분합니다.

## 메모리와 본문 보존

원본은 최대 100MiB의 임시 파일로 스트리밍 확인한 뒤 정규 UTF-8 파일로 준비합니다. BOM 제거와 CRLF/CR→LF 외에는 빈 줄·들여쓰기를 보존합니다. 실패·취소된 임시 파일은 제거하고 완성된 버전만 연결합니다. 캐시는 해시로 손상을 검출해 재생성하며 정상 버전 정리 목표는 256MiB입니다. 현재 읽는 버전과 새로 준비한 버전을 보호하는 동안에는 일시적으로 초과할 수 있으며 다음 준비/캐시 정리에서 회수합니다. 준비 중 임시 원본과 작업 파일만큼 추가 저장 공간이 필요합니다.

디스크 인덱스는 8,192 UTF-16 단위 내외의 고정 24바이트 레코드입니다. 본문 전체를 String이나 줄별 객체 목록으로 만들지 않습니다. 화면 블록은 약 2,048자와 64줄 이내에서 줄/문단 경계에 맞추고, 보이는 주변 최대 24블록과 측정 LRU 32개를 유지합니다. 단일 블록 측정 입력은 최대 4,096자입니다. 거대한 단일 grapheme은 오류로 안내하며 강제로 분리하지 않습니다.

아직 방문하지 않은 긴 문단 중간으로 이동할 때는 해당 문단 앞에서 줄 경계를 준비하므로 일반 문단보다 오래 걸릴 수 있습니다. 취소 가능한 코루틴과 로딩 표시를 제공하며, 이후에는 레이아웃별 체크포인트를 활용합니다.

검색은 KMP로 32K 문자 창을 읽고 일치 오프셋을 디스크에 기록합니다. UI는 한 번에 100개 결과만 표시하며 256 code point까지의 정확한 부분 문자열을 지원합니다. 결과 인덱스가 32MiB를 넘으면 더 긴 검색어를 요청합니다. 검색 강조는 표시 주변 본문만 읽습니다.

결과 목록의 시작 번호와 이전/다음으로 선택한 결과 번호는 별도 상태입니다. 100개 경계를 넘어 탐색해도 목록에서 누른 문장의 오프셋으로 이동합니다. 새 책 열기·독서 종료는 검색 세대를 무효화하고, 위치 이동은 이전 창의 선행 로드를 취소합니다. 폴더 화면으로 앱이 복귀하면 현재 하위 폴더를 다시 조회합니다.

## 공통 읽기 완료 안내

TXT·PDF·이미지의 끝에서 `ReadingEndDialog`로 다음 항목 이름과 **다음 것 보기 / 나가기**를 표시합니다. 세로 읽기는 사용자의 앞으로 스크롤 또는 끝으로 이동 후 화면이 안정되었을 때, 이미지 좌우 읽기는 마지막 묶음에서 추가로 넘겼을 때 안내합니다. PDF·이미지는 보이는 타일의 렌더링까지 기다립니다. 위치 복원만으로 안내하지 않으며, 닫은 안내는 끝을 벗어났다가 돌아오기 전에는 다시 표시하지 않습니다.

다음 항목은 SAF 원본 문서의 부모 경로에서 폴더 화면과 같은 자연 정렬 순서로 찾습니다. 최근 읽기에서 연 책도 현재 브라우저 폴더와 무관하게 원본 경로를 사용합니다. TXT·PDF·압축파일과 직접 이미지가 들어 있는 폴더가 대상입니다. 이미지 폴더는 한 권이므로 그 안의 개별 이미지를 다음 책으로 중복해서 열지 않습니다. 빈 폴더와 지원하지 않는 파일은 건너뛰며 처음 항목으로 순환하지 않습니다. 연결한 루트 자체가 이미지 책이면 접근 권한 밖의 형제 폴더는 조회하지 않습니다.

목록 조회는 백그라운드에서 취소 가능하게 수행하고, 독서 세션 토큰으로 늦은 결과를 무시합니다. 다음 항목이 없거나 조회에 실패해도 나가기는 가능합니다. 다음 책을 열거나 나가기 전에 기존 위치를 저장하며, 나가기는 앱 종료 대신 해당 폴더 화면으로 돌아갑니다. `ReadingEndTest`, `ReadingEndDialogTest`, `ImageViewTest`, `PdfViewTest`가 순서·렌더링 대기·복원·버튼 동작을 검사합니다.

2026-10-14 검증: 단위 테스트 38개 통과, Android 11·16에서 완료 안내/뷰어 테스트 6개씩 통과했습니다. 큰 이미지 간격에서 화면 밖 페이지를 기다리지 않는 검사를 추가한 뒤 Android 16의 이미지 뷰어 테스트 3개도 통과했습니다. 실제 SAF 폴더로 이미지 폴더 → 이미지 폴더 → TXT → PDF → CBZ를 열고, 빈 폴더 건너뛰기·마지막 항목·안내 닫기·재실행 후 최근 읽기의 원본 폴더 탐색을 두 버전에서 확인했습니다. 다섯 문서의 완료 상태 저장과 릴리스 빌드의 TXT → PDF 이동도 확인했습니다. Debug/Release 빌드와 lint는 오류 없이 완료했습니다.

## 디자인 적용

`design/final/`의 색상과 확정 리듬 SVG 경로를 네이티브 리소스로 옮겼습니다. 런처는 adaptive icon과 monochrome을 제공합니다. 앱 테마는 SYSTEM/LIGHT/DARK, TXT 본문은 FOLLOW_APP/LIGHT/SEPIA/DARK로 별도 저장합니다. 본문·헤더·푸터·시스템 바는 실제 본문 색상에 맞추고 보조 시트는 앱 테마를 따릅니다. Android 11의 3버튼 탐색 영역도 앱 배경이 이어지도록 대비용 기본 scrim을 해제했습니다.

설정은 일반 설정과 뷰어 설정 메뉴로 나눕니다. 일반 설정은 앱 테마와 폴더·저장 공간 카테고리로 구성하며, 시작 폴더·캐시 정리·연결 해제를 같은 카드 안에 표시합니다. 뷰어 설정에서 TXT·PDF 메뉴를 선택합니다. 각 읽기 설정은 독서 화면과 설정 메뉴 양쪽에서 전체 화면으로 열고, 뒤로가기로 진입 화면과 읽기 위치를 복원합니다.

실행 버튼은 `PaperButton`의 주요·보조·위험 동작 스타일을 공유합니다. 초기화·캐시 정리·연결 해제는 빨간 테두리, 최종 삭제·해제는 빨간 채움으로 표시합니다. 검색·취소·오류 안내에도 윤곽이 있는 버튼을 사용합니다. 세부 기준은 [Android 버튼 기준](../design/final/README.md#android-버튼-기준)에 있습니다.

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


## PDF 뷰어

`app/pdf`의 `PdfStore`가 SAF 원본을 스트리밍 복사하며 SHA-256을 계산합니다. 준비 중 취소된 파일은 삭제하고 완성된 캐시만 엔진에 전달합니다. 닫힌 PDF 캐시는 512MiB 목표로 오래된 것부터 정리하며 현재 문서와 준비 중 문서는 보호합니다. 원본에 쓰기 권한을 요청하지 않습니다.

`PdfRenderService`는 `isolatedProcess`에서 실행되며 읽기 전용 파일 디스크립터만 받습니다. PdfiumAndroidKt 2.0.0의 네이티브 호출은 단일 작업 스레드에서 순차 실행합니다. 렌더 결과는 Binder에 비트맵 전체를 싣지 않고 읽기 전용 SharedMemory로 넘깁니다. IPC 취소·지연 응답 시에도 공유 메모리를 닫으며 연결 중단·응답 제한 시간 초과·손상·암호 문서는 앱 화면에 오류를 돌려줍니다.

대용량 이미지의 타일마다 페이지를 다시 열지 않도록 네이티브 페이지도 최근 2개만 재사용합니다. 문서 ID와 페이지 번호로 구분하며 퇴출·문서 닫기·서비스 종료 시 페이지를 먼저 닫습니다. 검색과 렌더링은 같은 작업 스레드를 사용합니다. `cachedPagesSurviveEvictionAndOtherDocumentClose`가 캐시 퇴출 후 동일 픽셀, 다른 문서 닫기 후 검색·렌더링, 문서 재열기를 검사합니다. 이 캐시는 페이지 수를 제한하며 PDF 내부 이미지의 최대 디코딩 크기를 보장하는 것은 아닙니다.

`PdfDocumentView`는 512×512 이하 타일과 인접 페이지의 작은 미리보기를 사용합니다. 원본 비트맵 LRU 한도는 앱 힙의 1/8 또는 48MiB 중 작은 값입니다. 전체 문서를 비트맵 목록으로 만들지 않습니다. 핀치 확대는 1~5배, 두 번 탭은 2배/맞춤 전환입니다. 현재 페이지·좌표를 기준으로 폭·간격·화면 크기 변경 후 위치를 복원합니다. 메뉴를 숨겨도 본문 레이아웃은 바꾸지 않습니다.

색상 반전은 페이지 픽셀에만 `R/G/B = 255 - 원래 값`, `A = 원래 값` 행렬을 적용합니다. 이미지·도표도 반전되며 검색·선택 강조와 버튼은 별도 층입니다. 반전 전 비트맵을 재사용하므로 토글 시 원본 재해석이나 파일 수정이 없습니다.

PDF 검색은 페이지별 네이티브 텍스트를 찾고, 결과의 페이지·문자 범위는 12바이트 고정 레코드로 디스크에 기록합니다. 화면에는 100개씩 표시합니다. 각 페이지를 나누어 처리해 취소와 렌더링 요청을 사이에 처리할 수 있습니다. 검색 결과로 이동하면 좌표에 강조 표시하고, 길게 눌러 단어를 선택하거나 같은 페이지 안에서 드래그한 범위를 복사할 수 있습니다. 텍스트가 없는 문서는 검색할 텍스트가 없다고 안내합니다. OCR은 포함하지 않습니다.

Room 1→2 이관은 기존 TXT 레코드를 그대로 두고 `books.format/pageCount`, `pdf_positions`, `pdf_bookmarks`를 추가합니다. PDF도 세션 revision·sequence를 확인해 오래된 저장 요청을 무시하며 300ms 정지/1초 주기/백그라운드 전환에 저장합니다. 소스 해시가 바뀌면 처음부터 또는 이전 페이지로 열지 선택하며 변경 전 기록과 책갈피를 보관합니다.

PDF 설정은 독립된 `pdf_preferences` DataStore입니다. 기본값은 너비 맞춤, 8dp 간격, 반전 끔, 자동 배경입니다. 자동 배경은 앱 테마를 따르며 반전 중에는 어두운 배경을 씁니다. PDF 초기화는 TXT·앱 설정과 독서 기록을 건드리지 않습니다.

검증 문서는 `scripts/generate-pdf-fixtures.py`로 재생성할 수 있는 자체 제작 자료입니다. 텍스트·이미지·도표 혼합, 90도 회전, 스캔, 500페이지, 손상 및 암호 문서를 포함합니다. `PdfEngineTest`는 렌더링·검색·선택 좌표·반전 RGB/alpha·원본 바이트 보존·취소·오류 안내를 검사합니다. `PdfPersistenceTest`는 실제 버전 1 스키마에서 이관과 오래된 저장 방지·중복 책갈피·초기화 독립성을, `PdfViewTest`는 마지막 페이지 이동·확대 제스처·설정 변경 후 위치 보존을 검사합니다.

```sh
python3 scripts/generate-pdf-fixtures.py
scripts/build.sh :core:txt:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease
adb -s <테스트 기기> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <테스트 기기> install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s <테스트 기기> shell am instrument -w -e class com.poyal.yeobaek.PdfEngineTest,com.poyal.yeobaek.PdfPersistenceTest,com.poyal.yeobaek.PdfViewTest com.poyal.yeobaek.debug.test/androidx.test.runner.AndroidJUnitRunner
```

네이티브 의존성은 Maven의 `io.legere:pdfiumandroid:2.0.0`으로 고정했습니다. Apache·PDFium BSD 고지와 구성요소별 라이선스를 APK 자산에 포함했습니다. 64비트 네이티브 라이브러리의 ELF LOAD 정렬은 모두 16KB입니다. 16KB 페이지 크기의 OS 실행 및 실물 기기 성능·TalkBack 인수는 별도 검증이 남아 있습니다.

2026-10-14 검증: TXT 코어 9개와 앱 단위 테스트 19개, Lint, debug/release 빌드가 통과했습니다. ARM64 API 30·36 에뮬레이터에서 위 PDF 테스트 8개와 기존 TXT 저장 테스트 1개(각 9개)가 통과했습니다. 최적화 release APK는 약 40.0MiB이며 16KB ZIP 정렬 검사도 통과했습니다. 기존 TXT APK 약 22.2MiB 대비 네이티브 PDF 엔진으로 약 17.8MiB 증가했습니다.
release 화면에서도 일반/반전 페이지, 검색 강조, 선택·복사, 책갈피, PDF 전체 화면 설정, 마지막 페이지 이동, 프로세스 재시작 후 이어 읽기, TXT/PDF 설정 메뉴 왕복을 확인했습니다.

### 대용량 PDF 부하 검사

`PdfLoadTest`는 위의 500페이지 검사와 별도로 실제 바이트 용량을 검사합니다. `generate-pdf-load-fixtures.py`는 페이지마다 서로 다른 2048×2816 JPEG를 실제 본문에 배치하여 100MiB 이상 혼합 문서와 300MiB 이상 스캔 문서를 만듭니다. 이미지 스트림이 파일의 99% 이상이며, 사용하지 않는 데이터나 같은 이미지 반복 참조로 용량을 부풀리지 않습니다. 생성 파일과 실행 로그는 `.tools/`에만 저장하고 APK·Git에는 포함하지 않습니다.

```sh
python3 -m pip install -r scripts/requirements.txt
python3 scripts/generate-pdf-load-fixtures.py
scripts/build.sh -PpdfLoadTest :app:assembleDebug :app:assembleDebugAndroidTest
python3 scripts/verify-pdf-load.py --serial emulator-5580
```

`-PpdfLoadTest`를 지정한 테스트 APK에만 부하 검사 코드를 넣습니다. 실행기는 `yeobaek-*` 전용 에뮬레이터만 허용하며 debug 앱을 덮어 설치합니다. release 앱과 기존 독서 기록은 보존합니다. 별도 준비한 파일을 debug 앱의 테스트 디렉터리에 전달한 뒤 파일 크기를 검증합니다.

문서별로 앱 캐시를 비운 열기 3회, 복사 취소와 임시 파일 정리, 전체 화면 타일 완성, 1·2·5배 확대/반전/페이지 이동/스와이프 30회, 핀치와 두 번 탭, 검색과 렌더링의 동시 실행, 마지막 페이지 위치 저장, 재열기와 저장 페이지 렌더링 10회, 원본 SHA-256 보존을 확인합니다. 파일 입력 스트림은 테스트 전용 로컬 파일이며, 이후 `PdfStore`, 격리 PDF 렌더러, `PdfDocumentView`, `PdfReaderController`는 실제 앱 코드를 사용합니다. SAF 선택 화면과 공급자 다운로드 속도는 이 검사 범위에 포함하지 않습니다.

테스트 안의 단계별 측정과 별도 호스트 측정을 함께 기록합니다. 메모리 기준은 앱 PSS 384MiB, 렌더러 PSS 512MiB, 합계 768MiB 이하입니다. 화면 타일 캐시는 힙의 1/8 또는 48MiB 중 작은 한도를 넘지 않아야 하며, 초기 안정 구간 대비 마지막 구간 PSS 중앙값 증가는 프로세스별 96MiB, 앱 파일 디스크립터 증가는 24개 이하입니다. 화면 타일 완성 제한은 30초, 복사·해시·메타데이터·첫 타일까지 60초, 전체 검색은 60초입니다. 검색 완료는 UI 조작으로 막히지 않는 별도 디스패처에서 관찰합니다. Android OS 파일 캐시는 비우지 않으므로 완전한 콜드 스타트 수치로 해석하지 않습니다.

실행 결과는 `.tools/verification/<시각>-<serial>-pdf-load/`에 기기·APK·파일 해시, 단계별 지연, 프로세스별 PSS 표본, instrumentation 로그와 최종 판정을 저장합니다. `OK (2 tests)`, 두 문서의 완료 기록, 실제 수집한 두 프로세스의 메모리 기준을 모두 만족해야 통과합니다. 에뮬레이터 안정성 검사이며 실물 기기의 프레임 속도나 모든 PDF 문서에 대한 메모리 상한을 보장하지는 않습니다.

2026-10-14에 실제 106.77MiB·302.54MiB 문서로 API 30·36의 부하 검사와 각 10개 회귀 검사를 통과했습니다. [대용량 PDF 검증 기록](pdf-load-verification.md)에 기기 조건·메모리·시간·원시 증거를 남겼습니다.


## 이미지 뷰어

이미지를 선택하면 같은 폴더의 지원 이미지가 한 권이 되며 선택한 이미지에서 시작합니다. 폴더 자체는 기존 탐색 동작을 유지합니다. ZIP/CBZ·RAR4/5/CBR·7Z/CB7은 내부 경로의 각 구성요소를 자연 정렬해 한 권으로 열고, 하위 폴더별 목차를 제공합니다. 최근 읽기는 폴더 또는 압축파일 단위입니다. 폴더 기록은 문서 URI를 페이지 키로 사용해 파일 추가·순서 변경 후에도 같은 이미지로 돌아갑니다. 파일이 사라지거나 압축 내용이 바뀌면 이전 번호/처음부터/취소를 선택합니다.

`ImageSource`는 폴더와 압축의 페이지 목록·메타데이터·썸네일·타일 인터페이스를 통합합니다. 이미지 메타데이터는 필요한 페이지와 인접 두 페이지부터 읽습니다. 목록의 모든 이미지를 디코딩하지 않습니다. 압축 원본은 SHA-256을 계산하며 스트리밍 복사하고, 재열기는 원본과 캐시의 해시를 검증해 같은 파일을 재사용합니다. 이미지 저장소는 `noBackupFilesDir/reader-cache/images`에서 앱이 직접 관리하여 Android의 `cacheDir` 회수로 읽던 대용량 압축이 사라지지 않도록 합니다. 캐시 파일이 사라지거나 손상되면 원본에서 다시 준비합니다. 문서를 닫으면 비활성 캐시 한도 512MiB를 적용하므로 1GiB 압축은 다음 열기 때 다시 복사합니다. 일반 설정의 읽기 캐시 정리에도 포함됩니다. 압축 해제는 요청한 항목부터 디스크에 저장하며 순차 압축 스트림은 앞에서부터 읽습니다. 현재 파일 다음 항목은 같은 네이티브 핸들을 이어 사용합니다.

`ImageWorkerService`는 문서마다 `bindIsolatedService`로 분리됩니다. 파일 디스크립터로 받은 자료만 읽고 별도 작업 스레드에서 libarchive 1.1.6 바인딩 및 Android 이미지 디코더를 실행합니다. 긴 작업 중에도 메인 메시지 처리기는 취소를 받아 해당 문서의 격리 프로세스를 종료할 수 있습니다. 다른 열린 문서의 프로세스에는 영향을 주지 않습니다. 네이티브 경로 문자셋은 UTF-8을 명시하며, 실제 출력 파일명에는 압축 내부 경로를 사용하지 않습니다.

지원 이미지는 JPG/JPEG·PNG·WebP·BMP·GIF이며 GIF는 첫 프레임입니다. EXIF 방향 8가지를 원본 좌표와 화면 좌표 사이의 행렬로 반영합니다. 영역 디코딩 가능 형식은 512×512 이하의 타일로 읽습니다. BMP/GIF 등 영역 디코딩이 불가능한 형식은 4메가픽셀·한 변 4096px 이내로 샘플링합니다. 이 형식의 아주 큰 이미지 확대는 원본 해상도보다 낮을 수 있습니다. 네이티브 디코더는 최근 3개만 유지합니다.

`ImageDocumentView`는 세로 연속 및 좌우 페이지 배치에서 같은 원본 타일 캐시를 사용합니다. 한도는 앱 힙의 1/8과 48MiB 중 작은 값입니다. 반전은 PDF와 동일한 RGB 색상 행렬을 그리기 단계에서만 적용하고 투명도를 유지합니다. 반전 변경은 재디코딩을 발생시키지 않습니다. 메뉴 색상은 앱 테마를 유지하며 썸네일에도 같은 반전을 적용합니다. 독립적인 `image_preferences`에서 읽기 방식·방향·한 쪽/두 쪽/가로 자동·표지·맞춤·간격·반전·배경을 저장합니다.

Room 2→3은 `image_positions`, `image_bookmarks`를 추가하고 TXT/PDF 테이블을 유지합니다. 이미지 위치는 안정적인 페이지 키·페이지 번호·정규 좌표·확대율·완독 상태를 포함하며 세션 revision/sequence로 오래된 저장을 거절합니다. 앱 종료/백그라운드와 300ms 정지/1초 주기로 저장합니다. 책갈피와 기록 삭제도 이미지에 연결했습니다.

보호 한도는 폴더·압축 항목 20,000개, 개별 압축 해제 이미지 256MiB, 압축 목록의 선언 용량 합계 32GiB, 이미지 한 변 200,000px입니다. 원본 압축파일 자체에는 고정 크기 한도를 두지 않습니다. 파일 복사·해제 중 여유 공간 32MiB 미만이면 중지합니다. 비활성 디스크 캐시는 최근 512MiB를 유지하고 활성 원본 압축파일은 참조 수로 보호합니다. 경로 탈출·링크·macOS 메타데이터 항목을 읽기 목록에서 제외합니다. 암호·분할 압축·중첩 압축 및 지원하지 않는 압축 방식은 안내합니다. 일반 요청 제한은 60초, 압축 목록 배치·항목 해제는 180초입니다.

### 대용량 이미지 부하 검사

자체 제작 JPEG의 실제 데이터로 100MiB·300MiB·1GiB ZIP 및 300MiB RAR5/7Z(일반·솔리드)를 생성합니다. 1440×40000 PNG와 12000×12000 JPEG를 별도 검사합니다. 폴더 검사는 전용 테스트 경로에 작은 JPEG 1,000/10,000장을 생성하여 항목 수 영향을 분리합니다. 대용량 파일·압축 도구는 `.tools/`에만 보관하며 APK에 넣지 않습니다.

```sh
# Pillow와 공식 macOS 7-Zip/RAR 도구 경로를 지정합니다.
python3 scripts/generate-image-fixtures.py --seven <7zz 경로> --rar <rar 경로>
python3 scripts/generate-image-load-fixtures.py --seven <7zz 경로> --rar <rar 경로>
scripts/build.sh -PimageLoadTest :app:assembleDebug :app:assembleDebugAndroidTest
python3 scripts/verify-image-load.py --serial emulator-5580
# 특정 자료만 확인할 때
python3 scripts/verify-image-load.py --serial emulator-5580 --only dimensions.zip folder-10000
```

실행기는 이름이 `yeobaek-*`인 전용 AVD만 허용하고 한 번에 한 파일을 전달합니다. 입력 크기·SHA-256, 30회 위치/확대/배치/반전 변경 및 10회 재열기, 전체 타일 준비·위치 키 복원·캐시 한도를 검사합니다. 별도로 앱·격리 프로세스 PSS를 표본화합니다. 통과 기준은 앱 384MiB, 작업 프로세스 512MiB, 합계 768MiB 이하, 초기 안정 구간 대비 후반 앱 PSS 증가 96MiB와 FD 증가 24개 이하, 화면 타일 완성 90초 이내입니다. 실제 완료 JSON과 `OK (1 test)`를 모두 요구합니다.

큰 파일 입력 스트림과 다수 이미지 폴더는 앱 전용 테스트 경로에서 공급합니다. SAF 폴더 선택·공급자 다운로드 속도는 별도 앱 UI 검사 대상입니다. 에뮬레이터 수치는 실물 기기 프레임 성능이나 모든 압축 코덱 변형에 대한 보증이 아닙니다. 원시 측정은 `.tools/verification/<시각>-<serial>-image-load/`에 남깁니다.

2026-10-14 최종 실행은 API 30·36에서 각 10개 부하 시나리오와 20개 이미지/TXT/PDF 회귀 검사가 통과했습니다. 캐시 복구 수정, 형식별 지연과 메모리 수치, 실행 로그는 [이미지 뷰어 검증 기록](image-load-verification.md)에 있습니다.
