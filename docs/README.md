# 여백 사전조사 문서

앱을 사용하려면 [여백 사용자 매뉴얼](../README.md)을 먼저 읽어 주세요. 설치부터 읽기·백업·문제 해결까지 실제 화면과 함께 안내합니다. [전체 화면과 촬영 조건](screenshots/README.md)에서 공개 v0.3.0의 새 촬영본을 볼 수 있습니다.

최신 배포 파일과 변경 사항은 [v0.3.0 릴리즈 안내](releases/v0.3.0.md), 실제 실행 증거는 [0.3.0 검증 기록](releases/0.3.0-verification.md)에 있습니다. [v0.2.0 안내](releases/v0.2.0.md)·[검증](releases/0.2.0-verification.md), [v0.1.0 안내](releases/v0.1.0.md)·[검증](releases/0.1.0-verification.md)도 보존합니다.

**v0.3.0**은 책·폴더별 읽기 설정, 만화 분할·여백 자르기, 폴더 탐색·수동 백업과 TXT 스크롤 위치 보존 개선을 포함합니다. 기존 공개 서명을 유지하고 v0.2.0 덮어 설치의 TXT·PDF·이미지 기록·설정·접근 권한 보존을 확인했습니다. [변경 화면 HTML 보고서](reader-upgrade-report/index.html)와 실제 배포 APK의 검증은 구분하여 기록합니다.

조사 기준일은 2026-10-13입니다. 구현의 범위와 우선순위는 [개발 계획](../plan.md)을 기준으로 합니다.

확정 디자인은 [종이의 여백 × 리듬](../design/final/index.html)입니다. 화면·아이콘·색상·타입·상태 규칙은 `design/final/`의 [확정 디자인 명세](../design/final/README.md)에 있습니다.

| 읽는 순서 | 문서 | 목적 |
| --- | --- | --- |
| 1 | [기능·설정 분리](feature-boundaries.md) | 앱 공통·뷰어 공통·형식별 기능과 옵션을 구분합니다. |
| 2 | [폴더 연결·서재 설계](folder-library-design.md) | OS 폴더·원본 접근·하위 탐색·캐시·재연결을 정합니다. |
| 3 | [시작 폴더·이어 읽기 흐름](startup-resume-flow.md) | 시작 위치 설정과 다음 실행의 독서 확인을 정합니다. |
| 4 | [제품 및 UX 조사](product-research.md) | 읽기 경험과 첫 버전 화면을 구체화합니다. |
| 5 | [TXT 기술 설계](txt-reader-design.md) | 스크롤·텍스트·캐시·위치 복원을 설계합니다. |
| 6 | [검증 계획](validation-plan.md) | 시제품과 MVP의 통과 기준을 정합니다. |
| 7 | [Android 개발 기준](android-development.md) | 빌드·상태·데이터 이전·비동기 작업 기준입니다. |
| 8 | [UI 기준](ui-guidelines.md) | 공통 UI·큰 글씨·시스템 영역을 확인합니다. |
| 9 | [릴리즈 운영](release-workflow.md) | 검증·서명·데이터 보존·동일 산출물 게시를 준비합니다. |
| 10 | [PDF·만화 확장 조사](format-roadmap.md) | 후속 엔진과 전용 옵션을 검토합니다. |
| 11 | [출처 및 조사 한계](references.md) | 사실의 출처와 미검증 내용을 구분합니다. |

문서의 **확인 사실**은 공식 문서·개발사 자료·기존 Android 프로젝트의 소스 검토를 바탕으로 합니다. **설계 제안**과 **목표 수치**는 여백을 위한 판단이며, 구현·실기기 측정으로 확정해야 합니다. 실제 구현은 [구현 문서](implementation.md), 로컬 실행 증거와 실물 기기 미실행 항목은 [검증 기록](releases/0.3.0-verification.md)에 분리했습니다.

조아라·노벨피아는 사용자께서 지정하신 스크롤 경험의 참고 대상입니다. 해당 앱이 사용하는 내부 기술이나 성능을 추정해 사실로 기록하지 않습니다.

기존 프로젝트에서 필요한 개발·UI·검증 경험은 여백 기준으로 고쳐 본문에 직접 옮겼습니다. 다른 프로젝트 폴더나 문서를 열지 않아도 이 문서들로 후속 구현을 진행할 수 있습니다.

## 형식별 구현과 검증

- [빌드·실행·엔진 구조·테스트 명령](implementation.md)
- [대용량 PDF 검증](pdf-load-verification.md) · [대용량 이미지·압축파일 검증](image-load-verification.md)
- [0.3.0 실제 배포 APK 검증](releases/0.3.0-verification.md) · [개발 단계의 변경 화면 보고서](reader-upgrade-report/index.html)
- [확정 디자인 명세와 아이콘](../design/final/README.md) · [라이트·다크 비교 시안](../design/final/comparison.html)
