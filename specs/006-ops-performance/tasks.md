# Tasks: 인기 매물 조회수·캐싱 + 관측성/부하/배포 (6차)

규칙: `[P]` 병렬 가능. 정합성 로직(dedup/writeback 유실 0/감쇠)은 테스트 먼저. 브랜치 `feat/006-p<phase>-*`.

## Phase 1. 조회수 카운팅 (Redis INCR + writeback)
- [x] T600 `property.view_count`(엔티티 `viewCount`, `columnDefinition` 기본 0; ddl-auto update) + `PropertyDetailResponse.viewCount`. `PropertySummaryResponse` 불변. (`PopularPropertyResponse` 는 사용처인 **Phase 2 로 이연** — 미사용 DTO 선생성 회피)
- [x] T601 `ViewCountService.record`: **단일 Lua**(`lua/view_count.lua`) `SET NX EX`+`HINCRBY view:pending`+`ZINCRBY property:popular` 원자. best-effort try/catch. `ViewerKeyResolver`(인증 `u:{userId}`/비인증 `ip:{clientIp}`, `viewcount.trust-proxy` 시만 XFF)
- [x] T602 `getDetail` → `PropertyDetailResult(response, countablePublicAccess)`. 컨트롤러가 ACTIVE 공개표현일 때만 record 훅 호출(viewerKey 전달). 프로퍼티 `ViewCountProperties`(window/trust-proxy) + yml writeback-enabled/interval
- [x] T603 `ViewCountWriteback.flush()`(항상 빈) `EXISTS flushing→아니면 EXISTS pending→RENAME→HGETALL→단일 tx UPDATE→커밋 후 DEL`(P6) + `ViewCountWritebackStore`(@Transactional 분리) + `ViewCountWritebackScheduler`(게이팅). 메트릭 훅 자리(Phase 3)
- [x] T604·T605 통합(`ViewCountIntegrationTest`, Testcontainers Redis+MySQL): dedup 1회, 다른 viewerKey 각각 집계+ZINCRBY, **writeback 유실 0**(flushing 중 유입→새 pending 보존), getDetail 게이트(ACTIVE=true/소유자 DRAFT=false), 빈 pending no-op — 5/5 통과

## Phase 2. 인기 랭킹 + cache-aside
- [x] T610 `PopularPropertyService.top(limit)` cache-aside 단일 키 `popular:list`(Top-50, TTL 60s): miss 시 `ZREVRANGE 0 199`(over-fetch) → DB ACTIVE 필터 → `propertyId→response` 맵 순서 복원 → 상위 50 캐시 → `limit` slice. limit 1~50 검증(400)
- [x] T611 `GET /api/properties/popular?limit=`(기본 10) [PUBLIC] `PopularPropertyController` + SecurityConfig permitAll + `List<PopularPropertyResponse>`. Redis 장애 시 `findTopActiveByViewCount` DB 폴백(degrade)
- [x] T612 `PropertyDetailCache`(ACTIVE 공개표현만 저장, TTL 300s) + 컨트롤러 역할별 읽기: anonymous·USER=cache-first, REALTOR·ADMIN=우회→DB. 캐시 write 는 anonymous/USER miss 경로만
- [x] T613 `PopularCacheEvictor`(afterCommit best-effort): 삭제/반려(ACTIVE 이탈) → `ZREM`+`DEL detail`+`DEL popular:list`; 승인 → `DEL detail`. `PropertyService.delete`·`PropertyVerificationAdminService.approve/reject` 연결
- [x] T614 `PopularRankingDecay.decay()`(항상 빈) `ZUNIONSTORE WEIGHTS factor`(unionAndStore) + `removeRangeByScore(-inf, epsilon)` + `PopularRankingDecayScheduler`(게이팅, cron 04:00)
- [x] T615·T616 통합(`PopularPropertyIntegrationTest`): 트렌딩 Top-N 순서, **stale ZSET member(비ACTIVE/미존재) DB 필터 제외(P1 권위)**, cache hit, 감쇠(×0.5+임계 제거), limit 검증 400 — 5/5
- [x] T617·T618 통합(`PropertyDetailCacheIntegrationTest`): anonymous cache-first+dedup(동일 IP 1회), USER viewerKey 독립(다른 userId 각각), **REALTOR·ADMIN 캐시 우회→DB(stale 안 받음)** — 3/3
  - (evict 실패 시 최대 TTL stale 은 정책 문서화 — 엄격 테스트 대상 아님)

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
