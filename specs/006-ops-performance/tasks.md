# Tasks: 인기 매물 조회수·캐싱 + 관측성/부하/배포 (6차)

규칙: `[P]` 병렬 가능. 정합성 로직(dedup/writeback 유실 0/감쇠)은 테스트 먼저. 브랜치 `feat/006-p<phase>-*`.

## Phase 1. 조회수 카운팅 (Redis INCR + writeback)
- [ ] T600 DDL **`ALTER TABLE property ADD COLUMN view_count BIGINT NOT NULL DEFAULT 0`**(테이블명 `property`) + Property 엔티티 `viewCount`. **DTO(P2): 신규 `PopularPropertyResponse`(요약+viewCount) + `PropertyDetailResponse` 에 viewCount 추가. `PropertySummaryResponse`(검색 공유)는 불변**
- [ ] T601 `ViewCountService.record(propertyId, viewerKey)`: **단일 Lua** 로 `SET NX EX`(dedup, window 30m)+`HINCRBY view:pending`+`ZINCRBY property:popular` 원자 실행(부분성공 방지, P1). Lua 반환 0=중복→`view.dedup.skip`. **best-effort try/catch**(Redis 장애가 조회에 전파 X). `viewerKey`=인증 `u:{userId}`/비인증 `ip:{clientIp}`(`viewcount.trust-proxy` true 일 때만 XFF, 아니면 RemoteAddr — P2)
- [ ] T602 **`getDetail` 반환을 `PropertyDetailResult(response, countablePublicAccess)` 로 확장**(P1 — 현 코드는 ACTIVE 면 소유자/ADMIN 판별 전 즉시 반환이라 공개/소유자 구분 불가). `countablePublicAccess=true`(ACTIVE 공개표현)일 때만 record 훅 — **cache hit/miss 무관 호출**(P2). 비공개 표현·404 미집계. 컨트롤러가 viewerKey 전달 + 프로퍼티(`viewcount.window`, `viewcount.trust-proxy`, `viewcount.writeback-enabled`, `viewcount.writeback-interval`)
- [ ] T603 `ViewCountWriteback`(@Scheduled, 게이팅): **`EXISTS view:flushing`(잔존 선처리)→아니면 `EXISTS view:pending`→`RENAME view:pending view:flushing`(원자) → HGETALL → 단일 트랜잭션 `UPDATE property SET view_count += delta` → 커밋 후 DEL**(P6). 메트릭 훅 자리
- [ ] T604 [P] 단위: dedup 신규/중복, viewerKey 도출, **writeback 유실 0**(배출 중 HINCRBY → 새 pending 보존), 크래시 창 문서화 검증
- [ ] T605 [P] 통합(Testcontainers Redis+MySQL): 상세 조회→카운트→writeback→`view_count` 반영, 동일 viewerKey 윈도우 내 미가산, Redis 장애 시 조회 정상(degrade). **집계 게이트(P1)**: 비공개 상태(DRAFT/PENDING/REJECTED/CLOSED/HIDDEN)에 대한 소유자/ADMIN 접근 → `countable=false` 미집계; **ACTIVE 는 소유자/ADMIN 이 조회해도 `countable=true` 가산**(공개표현); 404 미집계

## Phase 2. 인기 랭킹 + cache-aside
- [ ] T610 `PopularPropertyService.top(limit)` cache-aside **단일 키**: `popular:list`(Top-50, TTL 60s) hit/miss → miss 시 **`ZREVRANGE property:popular 0 199`(over-fetch, P4) → DB ACTIVE 필터 → `propertyId→response` 맵으로 순서 복원(P5) → 상위 50 캐시** → 앱에서 `limit` slice. limit 1~50 검증(400). 무효화 `DEL popular:list` 1회(P7). over-fetch 크기는 프로퍼티(`popular.overfetch`)
- [ ] T611 `GET /api/properties/popular?limit=` [PUBLIC] 컨트롤러 + SecurityConfig permitAll(`/api/properties/popular`) + **`List<PopularPropertyResponse>`** 반환(P2). Redis 장애 시 DB `view_count` desc 폴백(원칙 V)
- [ ] T612 `PropertyDetailCache` cache-aside(`property:detail:{id}` TTL 300s) — **`countablePublicAccess=true`(ACTIVE 공개표현)만** 저장/조회(카운트와 동일 게이트, P1·P4). **읽기 정책(P1, 역할별)**: anonymous·**USER**=cache-first, **REALTOR·ADMIN**=캐시 read/write 우회→DB 직조회. 캐시 write 는 anonymous/USER miss 경로만. 비공개 표현·404 저장 금지. 상태 전이 evict 지점 연결
- [ ] T613 ACTIVE 이탈(삭제/반려) **afterCommit best-effort** 정리: `ZREM property:popular {id}` + `DEL property:detail:{id}` + `DEL popular:list`(단일 키)(2차 TransactionSynchronization 패턴 재사용). 승인 시 상세 캐시 evict
- [ ] T614 `PopularRankingDecay`(@Scheduled cron 기본 04:00, 게이팅 `popular.decay-enabled`): **`ZUNIONSTORE key 1 key WEIGHTS factor`**(전체 score×factor, 원자) + `ZREMRANGEBYSCORE -inf (epsilon)`. factor 0.5/epsilon 1.0 프로퍼티
- [ ] T615 [P] 단위: 감쇠(score×factor + 임계 제거), cache-aside hit/miss 분기
- [ ] T616 [P] 통합: 인기목록 반환·캐시 hit. **ACTIVE 이탈 제외(P1, 2계층)**: (a) evict 성공+캐시 miss 재조립 시 **DB ACTIVE 필터로 제외**, (b) **ZSET 에 stale member 를 남겨도(ZREM 미실행) miss 조립 응답엔 미포함** 확인(DB 필터 권위), (c) evict 실패 시 `popular:list` cache hit 최대 60s stale 은 **정책 문서화**(엄격 테스트 아님). **상세 캐시**: evict 성공=즉시 제외 / REALTOR·ADMIN=캐시 우회→DB / evict 실패=최대 TTL stale 정책 문서화
- [ ] T617 [P] 통합(**핵심 버그 방지, P2**): 상세 **첫 조회 miss → 둘째 조회 cache hit** 상황에서도 조회수 record 가 호출되는지 — 같은 viewerKey 는 dedup 윈도우로 1회만, 다른 viewerKey 는 hit 여도 가산됨을 검증
- [ ] T618 [P] 통합(**역할별 캐시 읽기 + viewerKey dedup, #5**):
  - **anonymous**: cache-first, viewerKey=`ip` — 첫 조회 miss→캐시 저장, 재조회 hit, 동일 IP dedup 1회
  - **USER**: cache-first, viewerKey=`u:{userId}` — anonymous 와 동일하게 hit, USER 별 dedup 독립(다른 userId 는 각각 가산)
  - **REALTOR**: 캐시 read 우회 → DB 직조회(캐시에 stale ACTIVE 있어도 DB 값 반환), 자기 비공개 매물은 캐시 미저장
  - **ADMIN**: 캐시 read 우회 → DB 직조회, 비공개 표현 미집계·미저장
  - ACTIVE 매물은 REALTOR/ADMIN 조회도 `countable=true` 가산(공개표현)

## Phase 3. 관측성 (Prometheus/Grafana/Sentry)
- [ ] T620 **`micrometer-registry-prometheus` 만 추가**(actuator 는 이미 build.gradle.kts:23 존재, application.yml `management:` 도 존재 — P3, 중복 추가 금지) + 기존 management 블록에 `prometheus` exposure·**`management.server.port=9090`** 보강. compose 에서 **9090 호스트 미매핑(내부망 전용)**(P3·P5). **actuator 전용 `@Order(0)` SecurityFilterChain 분리**(`securityMatcher(EndpointRequest.to("prometheus","health"))`+permitAll+csrf off), 기존 API 체인 불변(P2). healthcheck 는 내부 `:9090/actuator/health`
- [ ] T621 커스텀 메트릭(**코드명 dot, 노출명 `*_total` 자동, P6**): `cache.requests{cache,result}`, `cache.errors{cache}`, `view.dedup.skip`, `view.flush`, `view.flush.delta` + 상세/인기/검색 Timer. **Timer `endpoint` 태그는 고정값(`property_detail`/`popular`/`search`)만 — propertyId 등 가변값 태그 금지(cardinality, P4)**
- [ ] T622 Sentry(**`io.sentry:sentry-spring-boot-starter-jakarta`, Boot 3 — Boot BOM 비관리라 `sentry-bom` 또는 명시 버전 필수**): 통합 코드/예외 캡처 구현 + **DSN 미설정 시 no-op 게이팅**(빈 DSN=off). DSN 은 sentry.io 발급값 → 사용자가 **`application-local.yml`(gitignore, 커밋 금지)** 에 수동 입력(자동화 불가). 환경/샘플링 태그. 테스트/CI 는 Sentry off
- [ ] T623 Grafana provisioning(`docker/grafana/`): Prometheus datasource + 대시보드 JSON(캐시 hit-ratio, dedup 비율, writeback 델타, 엔드포인트 p95). prometheus 스크레이프 설정(`docker/prometheus/prometheus.yml`, **타깃 `app:9090`**)
- [ ] T624 [P] 슬라이스 테스트: 커스텀 메트릭 노출 확인 + **`management.server.port=0` + `@LocalManagementPort`(운영 9090 고정 아님) 로 관리 포트 조회 → `GET {mgmtPort}/actuator/prometheus` (Authorization 없이) → 200**(전용 `@Order(0)` permit 체인 401 리스크 고정, P3-테스트). 테스트 프로파일 Sentry/스케줄러 off

## Phase 4. 부하 검증 (k6, 원칙 VII)
- [ ] T630 k6 스크립트: ① 상세 조회 부하(캐시 hit-ratio·writeback 배치 효과) ② 인기목록 부하 ③ 검색 baseline
- [ ] T631 실측 → `docs/load-test-results.md` 갱신(캐시 전/후 DB write 감소, hit-ratio, p95). **수치 선기재 금지** — 측정값만 기록

## Phase 5. 배포 + 마감
- [ ] T640 앱 멀티스테이지 `Dockerfile`(gradle jdk21 build → temurin 21-jre) + `.dockerignore`
- [ ] T641 docker-compose 전 스택(app+mysql+redis+es-nori+prometheus+grafana), 모니터링 스택 profile
- [ ] T642 GitHub Actions `.github/workflows/build.yml`: JDK21 + `./gradlew build`(Testcontainers) + 앱 이미지 빌드(배포 제외)
- [ ] T643 [P] docs 마감: project-status/ROADMAP 6차 완료, tech-stack ADR(캐시·관측성·배포), api-design·api-specification(인기 엔드포인트·viewCount), 인수기준(spec §8) 체크
- [ ] T644 회귀: 기존 상세/검색/예약/정산/알림 경로 그린(테스트 프로파일 writeback/decay/Sentry off)

## 의존성
```
Phase1(조회수) → Phase2(랭킹/캐시) → Phase3(관측성) → Phase4(부하) → Phase5(배포/마감)
```
- Phase 3 는 Phase 1·2 의 메트릭 훅 지점이 있어야 계측 대상이 생김.
- Phase 4 는 Phase 2(캐시)·3(메트릭) 이후에 효과 실측 의미.
