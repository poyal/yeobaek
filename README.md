# 여백 · yeobaek

Android에서 기기의 TXT 파일을 연속 스크롤로 읽는 앱입니다. **종이의 여백** 화면과 **리듬** 아이콘을 라이트·다크 모드로 구현했습니다. Android 11 이상을 지원하는 개인용 TXT MVP입니다. 과거 에뮬레이터 프레임 목표는 미달이며 실물 성능·TalkBack 인수는 남아 있습니다.

**[최신 APK 다운로드](https://github.com/poyal/yeobaek/releases/latest)** · [릴리즈 안내](docs/releases/v0.1.0.md) · [문의·오류 신고](https://github.com/poyal/yeobaek/issues)

## 사용하기

1. 기기에 책 폴더를 만들고 TXT를 넣습니다. 처음 실행에서 **시작 폴더 선택**을 누르고 Android 폴더 선택기에서 해당 폴더를 연결합니다.
2. 폴더와 하위 폴더에서 TXT를 누르면 읽기를 준비합니다. 파일은 원래 위치에 유지되며 앱은 원본을 수정하거나 삭제하지 않습니다.
3. 본문을 위아래로 스크롤합니다. 본문을 짧게 누르면 상단·하단 메뉴가 함께 숨겨지고, 다시 누르면 나타납니다.
4. 상단에서 진행률과 책갈피를 확인합니다. 하단 **이동**에서 목표 위치를 고른 뒤 적용합니다. 취소하면 기존 위치가 유지됩니다.
5. **읽기 설정**에서 명조/고딕, 크기, 행간, 여백, 본문 색상을 바꿉니다. 앱의 라이트·다크 설정과 TXT 본문 색상은 별도로 저장됩니다.
6. **검색**으로 문장을 찾고 이전/다음 결과나 원래 위치로 이동합니다. **책갈피**는 문장 위치를 저장하고 목록에서 이동·삭제할 수 있습니다.
7. 독서 중 앱을 닫았다가 새로 실행하면 이어 읽기를 묻습니다. **폴더 보기**를 선택하거나 독서 화면의 뒤로 가기로 나오면 다음 실행은 설정한 시작 폴더에서 시작합니다.
8. 글자가 깨지면 **책 정보 → 글자가 깨져 보이나요?**에서 인코딩을 미리 보고 적용합니다. 파일을 옮겨 접근할 수 없으면 **파일 다시 찾기**로 기록을 재연결합니다.

앱 설정의 **폴더와 저장 공간**에서는 시작 폴더 변경·연결 해제·읽기 캐시 정리를 제공합니다. 최근 읽기의 **독서 기록 관리**는 위치와 책갈피만 삭제합니다. 원본 파일은 유지됩니다.

**설정 → 앱 정보·문의**에서 제작자, 이메일, GitHub, 오류 신고와 변경 내역을 확인할 수 있습니다. **설정 → 업데이트**에서는 최신 공개 버전을 확인하고 APK를 Download 폴더에 저장합니다. 새 실행의 자동 확인은 하루 한 번이며 수동 확인도 가능합니다. 공개 릴리즈가 없거나 연결에 실패하면 그 상태를 안내합니다.

다운로드한 APK는 크기·SHA-256을 확인하고, 설치 직전에 앱 ID·버전·서명을 검증합니다. **업데이트 설치**에서 Android의 ‘이 출처의 앱 설치 허용’을 켠 다음 설치 버튼을 다시 누릅니다. 앱을 삭제하지 않고 같은 서명의 새 APK로 업데이트해야 독서 기록이 유지됩니다. 설치가 취소되거나 끝나도 다운로드한 APK는 자동 삭제하지 않습니다. 업데이트 통신에 본문·책갈피·읽기 기록은 포함하지 않습니다.

## 지원 범위

- UTF-8, UTF-16 LE/BE, CP949, EUC-KR. BOM 없는 UTF-16 등 자동 판정이 어려운 파일은 수동 인코딩을 선택합니다.
- 원본 100MiB 안전 한도, 정규 본문 256MiB, 검색 결과 인덱스 32MiB. 대용량은 본문 전체를 메모리에 올리지 않습니다.
- PDF·EPUB·이미지·ZIP/CBZ, 페이지 넘김, TTS, 온라인 서점·동기화는 후속 범위입니다.
- 원본 접근 권한이 유지되어야 다시 열 수 있습니다. 앱 캐시는 영구 오프라인 사본이 아닙니다.
- 강제 종료 직전 마지막 약 1초의 미저장 위치, 공급자 다운로드 지연, 아직 방문하지 않은 매우 긴 문단의 배치 시간은 제한으로 남습니다.

## 빌드와 검증

JDK 17, Android SDK 36, Build Tools 35.0.0이 필요합니다. `JAVA_HOME`과 `ANDROID_HOME`을 지정하거나 Android Studio에서 SDK를 설정합니다.

```sh
scripts/build.sh :core:txt:test :app:lintDebug :app:assembleDebug
scripts/create-signing.sh
scripts/build.sh :app:assembleRelease
```

Windows PowerShell에서는 다음 명령을 사용합니다. 개인 JDK·SDK 경로를 `.tools/env.ps1`에 두면 빌드 스크립트가 읽습니다.

```powershell
.\scripts\build.ps1 :core:txt:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
.\scripts\create-signing.ps1
.\scripts\build.ps1 :app:assembleRelease
```

산출물은 `app/build/outputs/apk/debug/app-debug.apk`와 `app/build/outputs/apk/release/app-release.apk`입니다. 서명 키와 비밀번호는 Git에 포함하지 않습니다. 같은 앱을 업데이트하려면 생성한 키를 보존해야 합니다.

다른 PC에서 새로 만든 키는 기존 APK의 서명과 다릅니다. 기존 설치를 업데이트하려면 원래 키와 `signing.properties`를 먼저 복원합니다. 키 없이 release를 빌드하면 설치할 수 없는 `app-release-unsigned.apk`가 생성됩니다.

전용 AVD의 E2E, 30분 연속 스크롤, 100회 재열기, 1/10/50/100MiB 부하, Macrobenchmark 명령은 [구현·실행 문서](docs/implementation.md)에 있습니다. 실제 화면과 실행 결과는 [0.1.0 검증 기록](docs/releases/0.1.0-verification.md)을 확인해 주세요. 에뮬레이터 측정과 실물 기기의 성능 인수는 구분합니다.

## 문서와 디자인

- [개발 계획](plan.md) · [조사·설계 문서](docs/README.md)
- [확정 디자인](design/final/index.html) · [라이트·다크 비교](design/final/comparison.html)
- [리듬 아이콘](design/final/brand.html) · [디자인 명세](design/final/README.md)
- [이전 시안 보관](design/archive/index.html)

명조 글꼴은 Noto Serif CJK KR이며 [SIL Open Font License](app/src/main/assets/licenses/NotoSerifCJK-OFL.txt)를 APK에 함께 포함합니다.
