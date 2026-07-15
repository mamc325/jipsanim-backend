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
- [x] T620 `micrometer-registry-prometheus` 추가(actuator 기존) + management 블록에 `prometheus` exposure + **`management.prometheus.metrics.export.enabled: true`**(기본 폴백이 off 로 판정되어 명시 필요 — 이 미설정이 원인이었음). **보안(P5 수정)**: 별도 관리 포트(9090) 시 자식 컨텍스트에 메인 체인 미적용(prometheus 401) 확인 → **actuator 를 앱 포트(8080) 동일**로 두고 `/actuator/prometheus`·`/actuator/health` permitAll(PUBLIC_PATHS). 외부 노출은 compose 포트 미공개 + 프록시 차단으로 제어(리뷰가 제시한 대안 b, 로컬 공개 허용)
- [x] T621 커스텀 메트릭 `PropertyMetrics`(코드명 dot→`*_total`, P6): `cache.requests{cache,result}`·`cache.errors{cache}`·`view.dedup.skip`·`view.flush`·`view.flush.delta` 를 ViewCountService/Writeback·PopularPropertyService·PropertyDetailCache 에 배선. 엔드포인트 지연은 actuator 기본 `http_server_requests`(templated URI=저카디널리티)로 커버(P4 — 커스텀 Timer 미도입)
- [x] T622 Sentry `io.sentry:sentry-spring-boot-starter-jakarta`(`sentry-bom:7.18.0`) + application.yml `sentry.dsn=${SENTRY_DSN:}`(빈 DSN=no-op). DSN 은 사용자가 `application-local.yml`(gitignore) 수동 입력. 코드 게이팅 불요(SDK 가 빈 DSN 시 자동 비활성)
- [x] T623 provisioning: `docker/prometheus/prometheus.yml`(타깃 `app:8080`) + `docker/grafana/provisioning/{datasources,dashboards}` datasource + 대시보드 JSON(캐시 hit-ratio/dedup/writeback delta/엔드포인트 p95)
- [x] T624 [P] 통합(`ActuatorMetricsIntegrationTest`, RANDOM_PORT): **Authorization 없이 `GET /actuator/prometheus` → 200 + `cache_requests_total` 노출**, `/actuator/health` → 200. (관리 포트 분리 미채택으로 `@LocalManagementPort` 대신 `@LocalServerPort`)

## Phase 4. 부하 검증 (k6, 원칙 VII)
- [x] T630 k6 스크립트 3종: `loadtest/k6/property-detail.js`(상세 캐시 hit-ratio·writeback 효과), `popular.js`(인기목록 캐시), `es-search.js`(전문검색 baseline). 기존 `lib/common.js` 재사용
- [~] T631 `docs/load-test-results.md` §6 **측정 계획 + 실행법 + 메트릭 스크레이프법 + TBD 표** 추가. **실측 수치는 미기재(원칙 VII)** — 이 환경엔 k6 미설치 → 스택 기동 후 사용자가 실행해 hit-ratio/DB write 감소/p95 채움. 커스텀 메트릭(`cache_requests_total` 등, Phase 3)으로 산출

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
