# 출처 및 조사 한계

확인일: **2026-10-13**. 기술 근거는 Android·Unicode 공식 문서와 프로젝트 공식 저장소를 우선했습니다. 검색 결과의 제3자 리뷰는 제품·기술 결론의 근거로 채택하지 않았습니다.

## 1. 제품 참고 자료

| ID | 출처 | 확인에 사용한 내용 |
| --- | --- | --- |
| R01 | [아라 Android 앱 소개](https://play.google.com/store/apps/details?id=noad.geulga&hl=ko) | 로컬 형식, 인코딩 수정, 읽기 설정·탐색 기능 |
| R02 | [리디 기본 이용 안내](https://ridihelp.ridibooks.com/support/solutions/articles/154000207726) | 서비스·앱 열람·독서노트 개요 |
| R03 | [리디 독서노트 및 PDF 제한](https://ridihelp.ridibooks.com/support/solutions/articles/154000207702) | 파일 형식·플랫폼에 따른 기능 차이 |
| R04 | [조아라 Android 앱 소개](https://play.google.com/store/apps/details?id=com.joara.mobile&hl=ko) | 웹소설 뷰어와 읽기 환경 설정 |
| R05 | [노벨피아 Android 앱 소개](https://play.google.com/store/apps/details?id=com.novelpia.android.me&hl=ko) | 웹소설·다운로드·TTS 소개 |

## 2. TXT와 Android 구조

| ID | 출처 | 확인에 사용한 내용 |
| --- | --- | --- |
| R06 | [SAF 파일 접근](https://developer.android.com/training/data-storage/shared/documents-files) | 폴더 선택·영속 읽기 권한·선택 제한, 원본 이동·삭제, 미확정 크기 |
| R07 | [DocumentsProvider](https://developer.android.com/reference/android/provider/DocumentsProvider) | 공급자가 스트림·파이프를 반환할 수 있는 계약 |
| R08 | [Compose Lazy 목록](https://developer.android.com/develop/ui/compose/lists) | 항목 단위 구성·측정, key, placeholder 크기, 스크롤 중첩 |
| R09 | [Compose 성능 권고](https://developer.android.com/develop/ui/compose/performance/bestpractices) | 상태 읽기 범위, derivedStateOf, 재구성 제어 |
| R10 | [TextMeasurer](https://developer.android.com/reference/kotlin/androidx/compose/ui/text/TextMeasurer?authuser=8) | 측정 캐시, 레이아웃에 영향을 주는 설정 |
| R11 | [StaticLayout.Builder](https://developer.android.com/reference/android/text/StaticLayout.Builder) | 줄바꿈·하이픈·패딩과 측정 옵션 |
| R12 | [SelectionContainer](https://developer.android.com/reference/kotlin/androidx/compose/foundation/text/selection/SelectionContainer.composable) | 미구성 Lazy 항목의 선택·복사 제약 |
| R13 | [CharsetDecoder](https://developer.android.com/reference/java/nio/charset/CharsetDecoder) | REPORT, 버퍼 상태, EOF·flush 처리 |
| R14 | [Charset](https://developer.android.com/reference/java/nio/charset/Charset) | 이름·별칭·지원 조회 |
| R15 | [AOSP charset 매핑의 특정 커밋](https://android.googlesource.com/platform/libcore/+/71f2c75111e87091616f0f3b86bea6c4d345dad1/make/data/charsetmapping/charsets) | EUC-KR·x-windows-949 매핑 참고. 현재 모든 기기의 보장은 아님 |
| R16 | [Unicode UAX #29](https://www.unicode.org/reports/tr29/) | 결합문자·grapheme cluster와 단순 코드포인트 분할의 차이 |
| R17 | [Android 아키텍처 권고](https://developer.android.com/topic/architecture/recommendations) | UI·데이터 계층, ViewModel, 생성자 주입, 작은 앱의 수동 DI |
| R18 | [Room](https://developer.android.com/training/data-storage/room) | 로컬 관계형 데이터, SQL 검증, 마이그레이션 |
| R19 | [DataStore](https://developer.android.com/topic/libraries/architecture/datastore) | 작은 설정 데이터, 관계형·부분 갱신에는 Room 고려 |
| R20 | [Compose WindowInsets](https://developer.android.com/develop/ui/compose/system/insets) | 시스템 바·키보드·edge-to-edge의 실제 콘텐츠 영역 |
| R21 | [Compose semantics](https://developer.android.com/develop/ui/compose/accessibility/semantics) | 텍스트와 조작의 접근성 의미 |
| R22 | [Android Auto Backup](https://developer.android.com/identity/data/autobackup) | 백업 포함·제외와 noBackupFilesDir |

## 3. 성능과 후속 형식

| ID | 출처 | 확인에 사용한 내용 |
| --- | --- | --- |
| R23 | [Macrobenchmark metrics](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-metrics) | FrameTimingMetric, P95/P99, API 31+ frameOverrunMs |
| R24 | [JankStats](https://developer.android.com/topic/performance/jankstats) | 화면 상태와 프레임 지연 관측 |
| R25 | [Baseline Profiles](https://developer.android.com/topic/performance/baselineprofiles/overview) | 주요 경로 사전 컴파일과 측정 조건 |
| R26 | [AndroidX PDF 릴리스](https://developer.android.com/jetpack/androidx/releases/pdf) | 조사 시 beta01, API 28 읽기·렌더링 확대, experimental API |
| R27 | [PdfRenderer](https://developer.android.com/reference/android/graphics/pdf/PdfRenderer) | seek 가능한 입력, API별 생성자, 리소스 수명 |
| R28 | [Readium Kotlin](https://github.com/readium/kotlin-toolkit) | 형식·기능 지원표와 저장소 라이선스 안내 |
| R29 | [큰 Bitmap 로드](https://developer.android.com/topic/performance/graphics/load-bitmap) | 크기 사전 확인·다운샘플링·메모리 비용 |
| R30 | [ZIP 경로 이탈](https://developer.android.com/privacy-and-security/risks/zip-path-traversal) | 아카이브 경로 검증 |

폴더 모델 추가 조사: [DocumentsContract.Document](https://developer.android.com/reference/android/provider/DocumentsContract.Document)에서 document ID의 불투명성·공급자 내 유일성·장기 유지 계약과 크기·수정 시각의 미확정 가능성을 확인했습니다. 폴더 접근·시작 흐름의 제품 정책은 이 계약 위에 정한 여백의 설계입니다.

## 4. 로컬 프로젝트 근거

기존 Android 앱 perilog의 소스·빌드·주요 테스트·운영 문서를 읽기 전용으로 조사했습니다. 기준 커밋은 `7c9cfdd6a7ce25db47dd045603501acad3d4ac12`입니다. 필요한 내용은 [Android 개발 기준](android-development.md), [UI 기준](ui-guidelines.md), [릴리즈 운영](release-workflow.md)에 여백용으로 재작성했습니다. 원본 프로젝트 복사본이나 외부 저장소 링크에 의존하지 않습니다.

사전조사 착수 당시 여백은 README만 있던 새 프로젝트였습니다. 이후의 구현·빌드·검증 결과는 [구현 문서](implementation.md)와 [버전별 검증 기록](releases/0.1.0-verification.md)에 남깁니다.

## 5. 무엇이 사실이고 무엇이 제안인가

- **확인한 사실:** 공개 문서의 API 계약·지원표, 로컬 `perilog` 코드와 설정, 그 저장소 문서에 기록된 과거 검사 내용.
- **설계 판단:** Compose 우선, OS 폴더 연결·원본 읽기·파생 캐시, 시작 폴더·이어 읽기 확인, 문자 앵커, 블록 기반 로드, 형식별 엔진 분리, 최소 OS 제안.
- **검증 전 가설:** 화면 블록 크기·프리페치 거리·캐시 예산, M0에서 결정할 긴 문단 분할 방식.
- **목표:** 폴더 조회·본문 준비·재열기 지연, 프레임 백분위, 메모리 예산, 일정 추정.
- **수행하지 않은 일:** 참고 앱 설치·벤치마크, 기존 프로젝트 테스트 재실행·배포, 사용자의 개인 파일 인코딩 통계 수집, 실물 기기의 성능 인수.

라이브러리 문서는 최신 개발 API와 안정 API가 함께 표시될 수 있습니다. 문서에 함수가 보이는 것만으로 여백이 선택할 Compose BOM에서 사용할 수 있다고 단정하지 않습니다. 구현 시작 시 버전을 잠그고 실제 컴파일과 지원 OS 실행으로 확인합니다.

APK 배포 방식·사용할 네이티브 엔진·상용 폰트·스토어 정책은 아직 확정하지 않았습니다. 관련 단계에서 필요한 범위만 추가 조사합니다.
