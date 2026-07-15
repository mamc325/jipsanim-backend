# Implementation Plan: 인기 매물 조회수·캐싱 + 관측성/부하/배포 (6차)

- Branch prefix: `feat/006-p<phase>-*`
- Spec: `./spec.md` (§2 확정 결정)
- Constitution: v1.0.0

## Summary
매물 상세 조회 시 **Redis 카운터(dedup)** 로 조회수를 모아 스케줄러가 **원자 배출(RENAME)** 로 DB `view_count` 에 writeback.
인기 매물은 **Sorted Set 트렌딩 랭킹(일 감쇠)** + **cache-aside**(인기목록·상세)로 조회 부하를 낮춘다.
그 기능을 **Micrometer→Prometheus/Grafana/Sentry** 로 계측하고, **k6** 로 캐시 효과를 실측하며, **docker-compose 전 스택 + GitHub Actions** 로 패키징한다.

## Constitution Check
| 원칙 | 준수 |
| --- | --- |
| II. 멱등/원자성 | dedup `SET NX EX`, writeback `EXISTS`→`RENAME` 원자 배출(유실 0 지향), 감쇠 `ZUNIONSTORE WEIGHTS` 원자 |
| IV. Outbox | **색인만** Outbox(5차 그대로). 랭킹 ZREM/캐시 evict 는 `afterCommit` best-effort(P3 — 캐시는 색인 게이팅과 독립, TTL 로 자연 수렴) |
| V. 정합성 우선 | 카운트/캐시는 부가 계층, 핵심 조회 트랜잭션 불변. Redis 는 경계에서만 |
| VI. 차수 분리 | 캐싱/관측성/배포는 6차 도입. 기존 상세/검색 경로 무변경(필드 추가만) |
| VII. 측정 후 주장 | k6 실측 전 수치 미기재. 캐시 hit-ratio/DB write 감소는 측정값으로 |
| VIII. 상태전이 테스트 | dedup·writeback 유실 0·감쇠·degrade 를 단위/통합 테스트로 고정 |

## 핵심 흐름

### D1. 조회수 카운팅 (Producer, best-effort)
- `ViewCountService.record(propertyId, viewerKey)`:
  - **원자 카운트(P1, 단일 Lua)**: `SET NX EX`(dedup) + `HINCRBY view:pending` + `ZINCRBY property:popular` 를 **하나의 Lua** 로 실행(2차 Lua 패턴 재사용). 부분 성공(dedup 키만 생성 / HINCRBY 만 성공) 원천 차단. Lua 반환 1=counted, 0=중복 → `view.dedup.skip++`.
    - `KEYS`=[dedup, view:pending, property:popular], `ARGV`=[window(s), propertyId].
  - **best-effort**: 전체를 try/catch — Redis 장애가 상세 조회 응답에 전파되지 않음(원칙 I 유추). 실패는 로그+메트릭만.
- `viewerKey`: 인증 시 `u:{userId}`, 비인증 시 `ip:{clientIp}` — **`viewcount.trust-proxy`(기본 false)일 때만 `X-Forwarded-For` 첫 IP, 아니면 `RemoteAddr`**(XFF 스푸핑 조회수 부풀리기 차단, P2).
- 훅 위치·시점(P1·P2): 현 `getDetail` 은 ACTIVE 면 소유자/ADMIN 판별 전에 즉시 반환 → 공개/소유자 조회 구분 불가. **`getDetail` 반환을 `PropertyDetailResult(response, countablePublicAccess)` 로 확장**(또는 반환 `status==ACTIVE` 판정). `countablePublicAccess=true`(ACTIVE 공개표현)일 때만, **cache hit/miss 와 무관하게** record 호출(캐시 hit 여도 카운트 누락 없음). 비공개 표현·404 는 미호출. 컨트롤러가 viewerKey 전달.

### D2. Writeback 스케줄러 (원자 배출, 원칙 II)
- `@Scheduled(fixedDelayString=...)`(기본 60s), 게이팅 `viewcount.writeback-enabled`.
- **전제(P2)**: **단일 app instance** 기준(6차 범위=단일 컨테이너). 다중 인스턴스면 `EXISTS→RENAME→DEL` 이 스케줄러 간 경쟁 → Redis 분산 lock 또는 배출 Lua 화 필요(후순위). 이 전제를 project-status/plan 에 명시.
- 절차(**RENAME 은 source 부재 시 에러 → `EXISTS` 선확인**, P6):
  1. `EXISTS view:flushing` 이면 → 직전 주기 실패 잔존분이므로 **그것부터 처리**(2로).
     아니고 `EXISTS view:pending` 이면 → `RENAME view:pending view:flushing`(원자).
     둘 다 없으면 → no-op 종료.
  2. `HGETALL view:flushing` → (propertyId, delta) 목록.
  3. **단일 트랜잭션**으로 매물별 `UPDATE property SET view_count = view_count + :delta WHERE id=:id`. (테이블명 `property`)
  4. 커밋 성공 후 `DEL view:flushing`.
- **크래시 창(문서화)**: 3 커밋~4 DEL 사이 크래시 시 다음 주기에 중복 가산 가능. 조회수는 **근사 카운터**로 허용(정합성 핵심 도메인 아님). 배출 중 유입분은 새 pending 으로 보존 → **유실 0**.
- 메트릭(코드명 dot, P6): `view.flush`(배치 수), `view.flush.delta`(반영 델타 합) → 노출 `view_flush_total`/`view_flush_delta_total`.

### D3. 트렌딩 랭킹 + 일 감쇠 (원칙 II)
- 증가: D1 의 `ZINCRBY property:popular 1 {id}`.
- **감쇠 스케줄러**: `@Scheduled(cron=...)`(기본 매일 04:00), 게이팅 `popular.decay-enabled`.
  - **채택(원자)**: `ZUNIONSTORE property:popular 1 property:popular WEIGHTS factor`(전체 score×factor 를 단일 명령으로) → `ZREMRANGEBYSCORE property:popular -inf (epsilon)`(임계 미만 제거). factor 기본 0.5, epsilon 기본 1.0.
  - fallback: Lua(`ZRANGE ... WITHSCORES` → `ZADD` 재기록). 우선순위는 ZUNIONSTORE.
- ACTIVE 이탈 시 `ZREM`(D5).

### D4. Cache-aside (인기목록 + 상세)
- **인기목록** `PopularPropertyService.top(limit)`:
  - `GET popular:list`(**단일 키, Top-MAX=50**) → hit 면 역직렬화 후 앱에서 `limit` slice 반환(`cache.requests{cache="popular",result="hit"}++`).
  - miss → **`ZREVRANGE property:popular 0 OVERFETCH-1`(over-fetch, 기본 200, P4)** → DB `WHERE id IN(...) AND status='ACTIVE'` → **`propertyId→response` 맵으로 ZREVRANGE 순서 복원(P5, DB IN 순서 미보장)** → 상위 MAX=50 → `SET popular:list <json> EX 60` → `limit` slice 반환(miss 메트릭).
  - **부족 시(P2)**: over-fetch 내 ACTIVE < 50 이면 **나오는 만큼만 반환**(더 깊은 재조회 없음 — 트렌딩 근사 + 감쇠로 stale 제거되어 실무 충분). over-fetch 크기 `popular.overfetch` 튜닝.
  - **무효화(P7)**: `DEL popular:list` 1회로 끝(다중 limit 키/SCAN 불필요).
- **상세** — 기존 **`PropertyService.getDetail(AuthUser, Long)`**(신규 서비스 분리 아님, P3) 에 cache-aside 결합. `PropertyDetailCache` 는 협력자로 주입.
  - **읽기 정책(P1, 역할별)**: **anonymous·USER = cache-first**(둘 다 ACTIVE 공개표현만 도달 — USER 는 소유 불가·비관리자), **REALTOR·ADMIN = 캐시 read/write 우회 → DB 직조회**(소유자 가능성·권위 최신값·stale 회피). viewerKey: 인증=`u:{userId}`, 비인증=`ip:{clientIp}`.
  - anonymous/USER: hit 면 count 후 반환, miss 면 DB → **ACTIVE 공개표현이면 `SET property:detail:{id} EX 300`(워밍)**. 비공개 표현/404 는 캐시 저장 금지.
  - 카운트: 결과가 `countablePublicAccess=true`(ACTIVE) 이면 모든 역할에서 count(hit/miss 무관, dedup Lua). 캐시 write 는 anonymous/USER miss 경로만.
  - **접근제어 원천 차단**: 비공개 표현 미저장 + REALTOR/ADMIN 캐시 미read → 소유자/ADMIN 은 stale ACTIVE 안 봄. (anonymous/USER public cache hit 만 evict 실패 시 최대 TTL stale — 허용 정책, stale 시 count/랭킹 증가도 근사 오차로 허용)
  - `viewCount` 는 캐시 스냅샷 값(근사).
- **degrade**: Redis 예외 시 cache-aside 는 **캐시 우회 → DB 직조회**(핵심 조회 항상 응답, 원칙 V). 인기목록은 DB `view_count` desc 폴백. 메트릭에 error 카운트.

### D5. ACTIVE 이탈/진입 시 랭킹·캐시 정리 (afterCommit best-effort, P3 확정)
- 승인/삭제/반려는 도메인 트랜잭션에서 상태를 바꾼다. 그 **커밋 후(`TransactionSynchronization.afterCommit`)** 에 랭킹/캐시를 정리한다(2차 Redis `afterCommit` 정리 패턴 재사용).
  - **ACTIVE 이탈**(삭제/반려): `ZREM property:popular {id}` + `DEL property:detail:{id}` + `DEL popular:list`.
  - **ACTIVE 진입**(승인): `DEL property:detail:{id}`(다음 조회에 최신 반영) — 인기 랭킹은 조회로 자연 유입.
- **왜 Outbox 아님(P3)**: 캐시/랭킹은 `search.elasticsearch.enabled`(색인 게이팅)와 **독립**이어야 하고, TTL 이 짧아 정리 실패도 자연 수렴한다. Outbox 다중 핸들러/Worker 라우팅 확장 없이 결합도를 낮춘다. **색인만 5차 Outbox 그대로**, 랭킹/캐시는 afterCommit best-effort. 정리 자체가 자연 멱등(ZREM/DEL).

### D6. 관측성
- 의존성: actuator 기존 → **`micrometer-registry-prometheus` 만 추가**(Boot BOM 관리) + management 블록에 `prometheus` exposure + **`management.prometheus.metrics.export.enabled: true`**(폴백 off 판정 → 명시 필요). **Sentry `io.sentry:sentry-spring-boot-starter-jakarta` + `sentry-bom:7.18.0`**.
- **포트/보안(구현 확정, P5)**: 관리 포트 9090 분리 시도 → **자식 관리 컨텍스트에 메인 SecurityFilterChain 미적용**(prometheus 401, `@Order(0)`/`EndpointRequest` 매처도 관리 포트에 안 맞음) → **actuator 를 앱 포트(8080) 동일**로 두고 `/actuator/prometheus`·`/actuator/health` 를 **PUBLIC_PATHS 평문 경로 permitAll**. 외부 노출은 compose 미공개 + 프록시 차단(리뷰 대안 b).
- **커스텀 메트릭(P6 — 코드명 dot, 노출명 `*_total`)**: `Counter` `cache.requests`(cache,result)·`cache.errors`(cache)·`view.dedup.skip`·`view.flush`·`view.flush.delta`, `Timer` 상세/인기/검색(`@Timed` 또는 수동). Micrometer 가 export 시 `_total` 자동 부착 → 코드명에 붙이지 않음.
  - **Timer 태그 low-cardinality(P4)**: `endpoint` 태그는 **고정값만**(`property_detail`/`popular`/`search`). 실제 `propertyId` 등 가변값은 태그 금지(Prometheus cardinality 폭발 방지). URI 템플릿 기반이면 `{id}` 유지(actuator 기본 `http_server_requests` 도 templated uri).
- Sentry: DSN 은 `application-local.yml`(gitignore). 미설정(빈 DSN) 시 자동 비활성. 샘플링/환경 태그 설정.
- Grafana: docker-compose provisioning(`docker/grafana/` datasource + dashboard json), Prometheus datasource.

### D7. 부하(k6) + 배포
- k6 스크립트(`k6/` 또는 `docs/`): ① 상세 조회 부하(캐시 hit-ratio·writeback 배치 효과) ② 인기목록 부하 ③ 검색 baseline. 결과 → `docs/load-test-results.md`.
- 앱 Dockerfile 멀티스테이지(gradle:jdk21 build → eclipse-temurin:21-jre run). `.dockerignore`.
- docker-compose 전 스택: app + mysql + redis + elasticsearch-nori + prometheus + grafana. 프로파일로 모니터링 스택 optional.
- GitHub Actions: `build.yml` — checkout → JDK21 → `./gradlew build`(Testcontainers) → docker build(앱 이미지). 실 push/deploy 제외.

## Architecture / Package
```
com.jipsanim.property.view
├─ ViewCountService        (record: dedup + HINCRBY + ZINCRBY, best-effort)
├─ ViewCountWriteback      (@Scheduled RENAME 배출 → DB update)
└─ ViewCountProperties     (window/writeback-enabled/interval)

com.jipsanim.property.popular
├─ PopularPropertyService  (top(limit) cache-aside 단일 키 slice, ZREVRANGE)
├─ PopularRankingDecay      (@Scheduled 일 감쇠, ZUNIONSTORE WEIGHTS)
├─ PopularPropertyResponse (신규 DTO: 요약+viewCount, P2 — PropertySummaryResponse 불변)
└─ PopularPropertyController(GET /api/properties/popular)

com.jipsanim.property.cache
├─ PropertyDetailCache     (cache-aside detail, **ACTIVE 공개만** 저장/조회, evict on 전이)
└─ (afterCommit 훅) ACTIVE 이탈 시 ZREM property:popular + DEL detail + DEL popular:list

com.jipsanim.common.metrics
└─ (Micrometer Counter/Timer 등록), CacheMetrics 헬퍼

infra
├─ Dockerfile, .dockerignore, docker-compose(모니터링 스택)
├─ docker/prometheus/prometheus.yml
├─ docker/grafana/provisioning(datasource, dashboard.json)
└─ .github/workflows/build.yml
```

## Testing Strategy (원칙 VIII)
- 단위: dedup(신규/중복), writeback 원자 배출 **유실 0**(배출 중 HINCRBY → 새 pending 보존), 감쇠(score×factor + 임계 제거), viewerKey 도출.
- 통합(Testcontainers Redis+MySQL): 상세 조회→카운트→writeback→`view_count` 반영, 동일 viewerKey 미가산, 인기목록 cache-aside hit/miss, ACTIVE 이탈 후 랭킹/캐시 제외, Redis 장애 degrade(조회 정상).
- 관측성: `/actuator/prometheus` 에 커스텀 메트릭 노출 확인(슬라이스 테스트).
- 회귀: 기존 상세/검색/예약 경로 그린. 테스트 프로파일은 writeback/decay/Sentry off.

## Phasing
1. **조회수 카운팅**: `view_count` DDL + ViewCountService(dedup+INCR, best-effort) + Writeback 스케줄러(원자 배출) + 상세 응답 `viewCount` 노출
2. **인기매물 랭킹/캐시**: ZINCRBY 연동 + `GET /popular` cache-aside + 상세 cache-aside + 일 감쇠 스케줄러 + ACTIVE 이탈 정리(afterCommit)
3. **관측성**: actuator/prometheus + 커스텀 메트릭(cache hit/miss·dedup·flush·Timer) + Sentry(게이팅) + Grafana provisioning
4. **부하(k6)**: 시나리오 3종 실측 → `docs/load-test-results.md` 갱신(원칙 VII)
5. **배포/마감**: 앱 Dockerfile + docker-compose 전 스택 + GitHub Actions CI + docs(project-status/ROADMAP/tech-stack ADR) 마감·인수기준 체크

## Complexity Tracking
- 캐시/카운터/관측성/배포가 한 차수에 모임 — 기능(조회수·인기)을 축으로 나머지를 그 검증/계측 수단으로 종속시켜 세로 슬라이스 유지(결정 Q1, 원칙 VI).
- 랭킹/캐시 정리를 Outbox 다중 핸들러 대신 **afterCommit best-effort** 로 둬 5차 Worker 라우팅을 건드리지 않음(결합도↓, D5).
- 조회수는 **근사 카운터**(원칙 II 는 유실 0 지향, 중복 가산 크래시 창은 허용·문서화) — 정합성 핵심 도메인(예약/정산)과 구분.
