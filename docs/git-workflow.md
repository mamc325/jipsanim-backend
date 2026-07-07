# Git 워크플로 & 커밋 전략 (집사님)

솔로 개발 + 차수(spec) 기반 프로젝트에 맞춘 경량 GitHub Flow. `main` 은 항상 빌드/테스트 통과 상태를 유지한다.

## 1. 브랜치 전략 (GitHub Flow, 차수 정렬)

```
main  ──────●────────●────────●──────────●─────►  (항상 green, 배포 가능)
             \        \                    \
   feat/001-p0-setup   feat/001-p2-property  feat/002-...
```

- **`main`**: 보호 브랜치. 직접 push 금지, PR 로만 병합. 각 병합 시점이 "동작하는 세로 슬라이스".
- **feature 브랜치**: spec 차수 + tasks Phase 단위로 짧게 유지.
  - 네이밍: `feat/<specNo>-p<phase>-<슬러그>`
    - 예) `feat/001-p1-auth`, `feat/001-p3-molit-batch`, `feat/002-reservation-queue`
  - 한 Phase 가 크면 `feat/001-p4a-range-calc`, `feat/001-p4b-candidate-approve` 로 분할.
- **기타 prefix**: `fix/`, `refactor/`, `chore/`, `docs/`, `test/`.
- Phase 브랜치는 **머지 후 삭제**. 브랜치 수명은 짧게(1~3일 목표).

### 왜 Git Flow(develop/release/hotfix) 를 안 쓰나
- 솔로 + 릴리스 게이트 불필요. develop/release 계층은 오버헤드만 큼.
- 차수(spec)가 사실상 릴리스 단위 역할을 하므로 `main` + short-lived feature 로 충분.

## 2. 커밋 규칙 (Conventional Commits)

형식:
```
<type>(<scope>): <제목, 명령형, 72자 이내>

<본문: 무엇을/왜. 어떻게는 코드가 설명>

Refs: T0xx        # tasks.md 태스크 번호
```

- **type**: `feat` | `fix` | `refactor` | `test` | `chore` | `docs` | `perf` | `build`
- **scope**: 패키지/도메인 (`auth`, `property`, `pricestandard`, `external`, `verification`, `search`, `build`)
- 예:
  - `feat(auth): JWT 발급/검증 필터 추가`
  - `test(pricestandard): RangeCalculator IQR 경계 케이스`
  - `feat(external): 실거래가 bounded concurrency 수집`
  - `chore(build): QueryDSL·springdoc 의존성 추가`

## 3. 커밋 단위 (granularity)

원칙: **"하나의 커밋 = 하나의 논리적 변경, 그 자체로 컴파일/테스트 통과"**.

- 대략 **tasks.md 의 태스크 1개 ≈ 커밋 1개**. 아주 작은 태스크는 묶고, 큰 태스크는 쪼갠다.
- TDD 커밋 순서 권장: `test(...)` (실패 테스트) → `feat(...)` (통과 구현). 커밋 메시지에 `Refs: T0xx`.
- 엔티티/리포지토리처럼 함께 움직이는 건 한 커밋. 컨트롤러+서비스+DTO 도 한 슬라이스면 한 커밋 가능.
- **금지**: "wip", "수정", 여러 도메인 뒤섞인 대형 커밋, 빌드 깨진 채 커밋.

## 4. PR 전략

- **PR 단위 = tasks Phase 1개** (또는 분할 Phase). 리뷰 가능한 크기(대략 <400 줄 변경 목표).
- PR 제목: `feat(001-p1): 인증/권한` 처럼 차수-Phase 명시.
- PR 본문 체크리스트:
  - [ ] 관련 tasks 항목 체크 (T0xx)
  - [ ] 테스트 추가/통과 (`./gradlew test`)
  - [ ] constitution 원칙 위반 없음 (있으면 사유)
  - [ ] spec 인수기준 해당 항목 반영
- 병합 방식: **Squash merge** (feature 브랜치 내 잡음 커밋 정리). `main` 히스토리는 Phase 단위로 깔끔하게.
- 셀프 리뷰라도 PR 을 거쳐 diff 를 눈으로 확인(포트폴리오상 협업 흔적도 됨).

## 5. main 보호/품질 게이트

- 6차(CI) 도입 전까지: PR 병합 전 로컬에서 `./gradlew build` (test 포함) 통과 필수.
- 6차 이후: GitHub Actions 로 `build + test` 자동 실행, 통과해야 병합.

## 6. 초기 커밋 계획 (지금 상태 → 첫 커밋들)

현재 `main` 에 스캐폴드+문서가 uncommitted 상태. 아래 순서로 정리:

| # | 브랜치 | 커밋 | 내용 |
| --- | --- | --- | --- |
| 1 | `chore/scaffold` | `chore: Spring Boot 3.5.16 프로젝트 스캐폴딩` | Initializr 결과(build.gradle.kts, gradle wrapper, src 골격) |
| 2 | 〃 | `chore(build): QueryDSL·springdoc·jjwt·MockWebServer 의존성 추가` | build.gradle.kts 보강 |
| 3 | 〃 | `docs: constitution·roadmap·tech-stack·specs(001/002) 추가` | 설계 문서 세트 |
| 4 | 〃 | `chore: .gitignore, .idea 정리` | ignore 규칙 |
|   | → PR `chore(scaffold): 프로젝트 초기 세팅` squash merge to main | | |

이후 `feat/001-p1-auth` 부터 tasks Phase 순서대로 진행.

## 7. tasks ↔ 브랜치/PR 매핑 (001 MVP)

| tasks Phase | 브랜치 | PR 제목 |
| --- | --- | --- |
| Phase 0 | `feat/001-p0-setup` | `feat(001-p0): 공통·설정 스캐폴딩` |
| Phase 1 | `feat/001-p1-auth` | `feat(001-p1): 인증/권한` |
| Phase 2 | `feat/001-p2-property` | `feat(001-p2): 주소 API·매물 CRUD` |
| Phase 3 | `feat/001-p3-batch` | `feat(001-p3): 실거래가 배치 수집` |
| Phase 4 | `feat/001-p4-standard` | `feat(001-p4): 시세 기준 계산·승인` |
| Phase 5 | `feat/001-p5-verify` | `feat(001-p5): 매물 자동 검증·승인` |
| Phase 6 | `feat/001-p6-search` | `feat(001-p6): 조건 검색` |
| Phase 7 | `feat/001-p7-it` | `test(001-p7): 통합 시나리오` |
