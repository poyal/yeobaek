# 여백 작업 규칙

사용자에게 항상 한국어 존댓말로 응답합니다.

- 구현·검사 전에 [개발 안내](docs/development.md), 배포 전에 [릴리즈 안내](docs/release.md)를 읽습니다.
- 루트 README는 설치·폴더 연결·읽기·저장 결과·백업·업데이트를 설명하는 사용자 매뉴얼로 유지합니다. 실제 UI와 대조하고 문서만 바꾸면 링크·이미지만 확인합니다.
- Android 공통 UI는 `PaperButton`과 [UI 기준](docs/ui-guidelines.md)을 따릅니다. 형식별 상태·설정·위치는 [구현 안내](docs/architecture.md)의 경계를 유지합니다.
- 검사는 전용 AVD와 합성 자료를 사용합니다. 개인 기기·수동 테스트 AVD를 초기화하지 않습니다. 실행한 OS·ABI·release/debug·실제 PID 복원·누락·생략을 구분합니다.
- 기존 `.tools/signing/`과 `signing.properties`를 보호합니다. 새 서명 키로 교체하지 않고 키·백업·사용자 기록을 Git에 넣지 않습니다.
- 문서와 생성물의 보관·삭제는 [공통 정리 규칙](docs/development.md#문서와-산출물-정리)을 따릅니다.
