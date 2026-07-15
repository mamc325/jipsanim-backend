# Feature Spec: 인기 매물 조회수·캐싱 + 관측성/부하/배포 (6차)

- Feature Branch prefix: `feat/006-p<phase>-*`
- Status: Design Locked (구현 대기)
- Constitution: v1.0.0 (원칙 II 멱등/원자성, V 정합성 우선, VI 세로 슬라이스, VII 측정 후 주장)
- 선행: 1차(매물/상세)·2차(Redis Sorted Set/Lua)·4차(Outbox)·5차(검색) 완료

## 1. 범위
- **기능 중심 통합 세로 슬라이스**(결정 Q1): 조회수 카운팅 + 인기매물 랭킹/캐싱을 **핵심 기능**으로 구현하고, 관측성(Prometheus/Grafana/Sentry)·k6 부하·Docker/CI 는 **그 기능을 계측·검증·패키징하는 수단**으로 같은 차수에 통합한다. 스토리: 기능 → 계측 → 부하 증명 → 배포.
- **조회수**: 매물 상세 조회 시 Redis 카운터 증가(중복조회 dedup) → 주기적 DB writeback(원자적 flush, 유실 0 지향).
- **인기매물**: Redis Sorted Set 실시간 Top-N 랭킹 + 인기목록 **cache-aside**(TTL). 매물 상세도 cache-aside(TTL) 로 조회 부하 완화.
- **관측성**: Micrometer → Prometheus 메트릭 + Grafana 대시보드 + Sentry 에러추적. 캐시 hit/miss·조회수 flush·엔드포인트 레이턴시 계측.
- **부하 검증**: k6 시나리오로 캐시 hit-ratio / DB write 감소를 **실측**(원칙 VII — 수치 선기재 금지).
- **배포**: 앱 Dockerfile(멀티스테이지) + docker-compose 전 스택(app·mysql·redis·es·prometheus·grafana) + GitHub Actions CI(빌드·테스트·이미지 빌드).
- **인기 랭킹 감쇠**(결정): 누적 score 에 **일 1회 시간 감쇠**(score × factor, 기본 0.5)를 적용해 오래된 인기의 영구 고착을 막고 "트렌딩"에 가깝게 만든다. → 랭킹 score 는 **감쇠된 트렌딩 신호**로, 생애 누적 `view_count`(DB)와 의미가 분리된다.
- **Out of scope**: 정밀 롤링 윈도우(시간버킷 단위) 랭킹, 개인화 추천, 실 클라우드 배포(CI 는 이미지 빌드까지), 분산 트레이싱(OpenTelemetry), APM 유료 연동.

## 2. 확정 결정 (검토 대상)

### 2-1. 조회수 → **Redis INCR + 주기적 writeback**(결정 Q2)
- 매물 상세(`GET /api/properties/{id}`) 처리 시 **중복조회 방지 후** 카운트. **판정 기준(P1 정정)**: "누가 봤나"가 아니라 **서비스가 ACTIVE 공개 표현을 반환했는지**. 현 `getDetail` 은 ACTIVE 면 소유자/ADMIN 판별 전에 바로 반환하므로 공개/소유자 조회를 구분하지 못함 → **`getDetail` 이 공개표현 여부를 알려주는 결과 타입 필요**: `PropertyDetailResult(response, countablePublicAccess)` (또는 반환 `status==ACTIVE` 로 판정). `countablePublicAccess=true`(=ACTIVE 표현)일 때만 카운트·캐시. 비공개 표현(DRAFT/PENDING/REJECTED/CLOSED/HIDDEN — 소유자/ADMIN 만 도달)·404 는 미집계·캐시 우회. (ACTIVE 매물은 소유자/ADMIN 이 봐도 같은 공개표현이라 카운트됨 — dedup 윈도우로 남용 제한.)
- **cache hit/miss 와 무관하게** ACTIVE 공개표현이면 record 호출(P2 — 캐시 hit 시 조회수 누락 방지).
  - **원자적 dedup+카운트(P1, 단일 Lua)**: dedup·writeback 델타·랭킹을 **하나의 Lua 스크립트**로 원자 실행(2차 Redis Lua 패턴 재사용) — 부분 성공(dedup 키만 생성, 또는 HINCRBY 만 성공) 방지.
    ```
    -- KEYS[1]=view:dedup:{id}:{viewerKey}, KEYS[2]=view:pending, KEYS[3]=property:popular
    -- ARGV[1]=window(s), ARGV[2]=propertyId
    if redis.call('SET', KEYS[1], '1', 'NX', 'EX', ARGV[1]) then
      redis.call('HINCRBY', KEYS[2], ARGV[2], 1)   -- writeback 대기 델타
      redis.call('ZINCRBY', KEYS[3], 1, ARGV[2])   -- 트렌딩 랭킹
      return 1                                      -- counted
    else
      return 0                                      -- dedup 중복 → skip
    end
    ```
    `viewerKey` = 인증 시 `userId`. 비인증 시 클라이언트 IP — **XFF 스푸핑 방지 정책(P2)**: `viewcount.trust-proxy`(기본 `false`)일 때만 `X-Forwarded-For` 첫 IP 신뢰, `false` 면 `RemoteAddr` 사용. (신뢰 가능한 리버스 프록시 뒤에서만 XFF 켜기 — 헤더 조작으로 조회수 부풀리기 차단). 윈도우 기본 30분. 반환 0 이면 `view.dedup.skip` 계측.
  - **best-effort(원칙 I 유추)**: 조회수 증가는 상세 조회 성공/응답시간에 영향 주면 안 됨 → Redis 장애 시 try/catch 로 삼켜 상세 조회는 정상 반환.
- **writeback 스케줄러**(기본 1분): pending 델타를 **원자적으로 배출**해 DB `property.view_count += delta`.
  - **원자적 배출(원칙 II)**: 배출은 고정 키 `view:flushing` 사용. **`EXISTS view:flushing`(직전 실패 잔존) → 있으면 그것부터 처리** → 없으면 `EXISTS view:pending` → 있으면 `RENAME view:pending view:flushing`(원자). 배출 중 유입 증가분은 새 `view:pending` 로 쌓여 **유실 0**. (RENAME 은 source 부재 시 에러 → EXISTS 선확인 필수)
  - DB 반영은 매물별 `UPDATE ... SET view_count = view_count + :delta`(멱등 아님 → **flushing 배치를 단일 트랜잭션으로 반영 + 커밋 성공 후에만 `DEL view:flushing`**). 처리→DEL 사이 크래시 시 재처리로 중복 가산 가능 → 조회수는 **근사 카운터**로 허용, 크래시 창은 문서화.
  - **단일 app instance 전제(P2)**: writeback 의 `EXISTS→RENAME→HGETALL→DEL` 흐름은 **인스턴스 1개** 기준(6차 범위 = 단일 컨테이너). 다중 인스턴스면 스케줄러 동시 실행으로 RENAME/DEL 경쟁 → Redis 분산 lock(`SET NX` lock) 또는 배출 전체를 Lua 로 감싸야 함(후순위). 감쇠/카운트 Lua 는 다중 인스턴스에서도 원자.

### 2-2. 인기매물 → **Sorted Set 트렌딩 랭킹(일 감쇠) + cache-aside**(결정 Q3)
- `property:popular`(ZSET, score=**감쇠된 트렌딩 점수**, member=propertyId): 조회수 증가 시 `ZINCRBY +1`.
- **일 1회 감쇠 스케줄러**(결정): 하루 한 번 모든 member score 에 `factor`(설정, 기본 0.5) 곱. **`ZUNIONSTORE property:popular 1 property:popular WEIGHTS factor`**(전체 score×factor 를 단일 원자 명령으로) → `ZREMRANGEBYSCORE property:popular -inf (epsilon)`(임계 미만 제거, 집합 유한). Lua 는 fallback. 스케줄러는 프로퍼티 게이팅.
- **트렌딩 vs 누적 분리**: 감쇠로 랭킹 score 는 생애 누적 `view_count`(DB)와 **의도적으로 분리**. 랭킹은 "최근 인기", `view_count` 는 "총 조회수".
- `GET /api/properties/popular?limit=N` [PUBLIC]: **cache-aside(단일 키)** — `popular:list`(Top-`MAX`=50 을 1건 캐시, TTL 기본 60s). miss 시 조립: **`ZREVRANGE property:popular 0 OVERFETCH-1`(over-fetch, 기본 200) → DB 에서 ACTIVE 만 필터 → Redis 순서 복원 → 상위 `MAX`=50 만 캐시 저장**(P4-보강). 응답은 캐시에서 `limit` 만큼 **앱 slice**. 무효화 `DEL popular:list` 1회(P7).
  - **부족 시 정책(P2 확정)**: over-fetch 200 내 ACTIVE 가 50개 미만이면 **나오는 만큼만 반환**(더 깊은 재조회 없음). 인기 목록은 트렌딩 근사이고 감쇠로 stale member 는 임계 미만 제거되므로 실무상 충분. over-fetch 크기(`popular.overfetch`)로 조정.
  - **Redis 순서 보존(P5-보강)**: DB `IN` 조회는 순서를 보장하지 않으므로 **`propertyId → response` 맵으로 재정렬**해 ZREVRANGE 순서(트렌딩 desc)를 복원.
- **정합성 = 2계층(P1 정정)**:
  - **API 결과 = 권위(DB ACTIVE 필터)**: miss 조립 시 `WHERE status='ACTIVE'` 로 걸러 **ZSET 에 stale member 가 있어도 응답에는 안 나옴**. 이것이 "ACTIVE 이탈 제외"의 실제 보장 지점.
  - **`popular:list` cache hit = 최대 60s stale 허용(P1-A)**: hit 시엔 재필터 안 함 → ACTIVE 이탈 후 `DEL popular:list`(afterCommit best-effort) 실패하면 **최대 TTL(60s) 동안 비활성 매물이 목록에 남을 수 있음**. → **정책: 60s stale 허용**(hit 마다 DB 재검증은 캐시 무의미화 → 미채택).
  - **ZSET stale member 는 허용(P1-B)**: `ZREM` 이 best-effort 이고, **stale detail cache hit 의 count Lua 가 `ZINCRBY` 로 비활성 member 를 재유입**할 수도 있음 → ZSET 은 "제외 보장" 대상이 **아님**. stale member 는 **일 감쇠(`ZUNIONSTORE`×factor + `ZREMRANGEBYSCORE`)와 조회 시 DB ACTIVE 필터로 자연 정리**. API 정확성은 DB 필터가 담당.
- **ACTIVE 이탈 정리(afterCommit best-effort)**: `ZREM property:popular {id}` + `DEL property:detail:{id}` + `DEL popular:list`. Outbox 미사용(파생 설계 P3). 실패해도 위 2계층으로 수렴.
- **회복력**: 랭킹은 감쇠로 이미 휘발적 신호 → Redis 유실 시 **콜드 스타트 후 조회가 쌓이며 자가 회복**(DB 완전 재구축 불요). `view_count` 자체는 DB 영속.

### 2-3. 매물 상세 캐시 → **ACTIVE 공개 상세만 cache-aside(TTL) + 상태전이 evict**
- `property:detail:{id}`(TTL 기본 300s): ACTIVE 매물은 현 정책상 수정 불가(DRAFT/PENDING 만 수정) → **상세가 안정적**이라 캐시 적합.
- **읽기 정책 = 역할별 확정(P1)**: 비공개 표현을 절대 도달할 수 없는 역할만 cache-first, 소유/관리 권한이 있는 역할은 캐시 우회.
  | 역할 | 읽기 | viewerKey(dedup) | 근거 |
  | --- | --- | --- | --- |
  | anonymous | **cache-first** | `ip:{clientIp}` | ACTIVE 공개표현만 도달 |
  | USER | **cache-first** | `u:{userId}` | 소유 불가·비관리자 → anonymous 와 동일하게 ACTIVE 만 |
  | REALTOR | **캐시 read 우회 → DB 직조회** | `u:{userId}` | 소유자일 수 있어 비공개 표현 도달 가능 → 권위 최신값 |
  | ADMIN | **캐시 read 우회 → DB 직조회** | `u:{userId}` | 비공개 표현 도달 가능 → 권위 최신값 |
  ```
  if role in {REALTOR, ADMIN}:  result = getDetail(authUser, id)     # DB 직조회(캐시 read/write 우회)
  else (anonymous|USER):        hit = GET property:detail:{id}
                                if hit: record(count, viewerKey); return hit
                                result = getDetail(authUser, id)      # miss → DB
                                if result.countablePublicAccess: SET property:detail:{id} EX 300  # 워밍
  if result.countablePublicAccess(=ACTIVE 공개표현):  record(count, viewerKey)   # dedup Lua (hit/miss 무관, P2)
  ```
  → **비공개 표현(DRAFT/PENDING/REJECTED/CLOSED/HIDDEN)·404 는 캐시 저장 금지**. 카운트는 결과가 ACTIVE 공개표현일 때만(모든 역할, dedup 로 남용 제한). 캐시 write 는 anonymous/USER miss 경로만(REALTOR/ADMIN 은 read·write 모두 우회).
- **두 개념 분리(P2 정정)**:
  1) **접근제어 = 원천 차단**: 비공개 표현은 애초에 저장되지 않고, REALTOR/ADMIN 은 캐시를 아예 안 읽음 → 소유자/ADMIN 은 stale ACTIVE 를 받지 않음(확정).
  2) **비활성 후 stale = 최대 TTL 허용(anonymous·USER public cache hit 한정)**: ACTIVE→DELETED/REJECTED 전이 시 evict 는 afterCommit **best-effort** → 실패하면 **anonymous/USER 공개 cache hit** 만 이미 캐시된 ACTIVE 상세를 **최대 TTL(300s) 동안 stale** 로 받을 수 있음(REALTOR/ADMIN 은 DB 직조회라 무관). → **정책 확정: 최대 TTL stale 허용**. **stale cache hit 시 조회수/랭킹 증가도 근사 오차로 허용**(비활성 매물에 카운트가 조금 더 붙을 수 있음 — 근사 카운터 성격상 수용). 근거: TTL 짧고(300s) 정합성 핵심 도메인 아님.
- **무효화**: 승인/삭제/반려 등 상태 전이 시 evict. 조회수 필드는 캐시된 상세에 실시간 반영하지 않음(근사).

### 2-4. 관측성 → **Micrometer/Prometheus + Grafana + Sentry**(결정 Q4)
- **`spring-boot-starter-actuator` 는 기존 존재**(build.gradle.kts:23), `application.yml` 에 `management:`(`include: health,info`)도 존재 → **`micrometer-registry-prometheus` 만 추가**(P3, 중복 금지) + management 블록 보강.
- **보안/포트(구현 확정, P5)**: 관리 포트 9090 분리를 시도했으나 **별도 관리 컨텍스트(child)에는 메인 SecurityFilterChain 이 적용되지 않아**(`ManagementWebSecurityAutoConfiguration` 가 health 만 허용, prometheus 401) 그리고 자식 컨텍스트 보안 커스터마이즈가 복잡 → **actuator 를 앱 포트(8080) 동일**로 두고 `/actuator/prometheus`·`/actuator/health` 를 **PUBLIC_PATHS permitAll**(무인증 스크레이프)로 확정(리뷰 대안 b, 로컬 공개 허용). 외부 노출은 **compose 앱 포트 미공개 + 리버스 프록시에서 `/actuator/*` 차단**으로 제어.
  - **prometheus export 명시 필수**: `management.prometheus.metrics.export.enabled: true` — `management.defaults.metrics.export.enabled` 폴백이 off 로 판정되어 미설정 시 PrometheusMeterRegistry/엔드포인트가 생성되지 않음(발견).
- **커스텀 메트릭(P6 — 코드명은 dot, Prometheus 노출명은 `*_total`)**:
  | 코드명(Micrometer) | Prometheus 노출명 | 태그 |
  | --- | --- | --- |
  | `cache.requests`(Counter) | `cache_requests_total` | cache, result |
  | `cache.errors`(Counter) | `cache_errors_total` | cache |
  | `view.dedup.skip`(Counter) | `view_dedup_skip_total` | - |
  | `view.flush`(Counter) | `view_flush_total` | - |
  | `view.flush.delta`(Counter) | `view_flush_delta_total` | - |

  \+ 상세/인기/검색 엔드포인트 `Timer`. (Micrometer 가 Counter export 시 `_total` 을 자동 부착하므로 코드명에 직접 붙이지 않음)
- **Grafana**: docker-compose 서비스 + provisioning(Prometheus datasource + 대시보드 JSON).
- **Sentry**: Boot 3 기준 **`io.sentry:sentry-spring-boot-starter-jakarta` 로 통일**. **Sentry 는 Spring Boot BOM 관리 대상이 아님** → `io.sentry:sentry-bom` platform 또는 **명시 버전** 필수(버전 없이 Gradle resolve 실패). **DSN 은 sentry.io 계정/프로젝트 발급값이라 사용자가 수동 입력**(자동화 불가) → `application-local.yml`(gitignore)에 붙여넣음, 커밋 금지(보안 제약). **DSN 미설정 시 비활성(빈 DSN no-op)** → 통합 코드·의존성·예외 캡처는 모두 구현하되 CI/테스트/기본 기동은 Sentry off. 실제 라이브 확인만 DSN 붙여넣기 필요.

### 2-5. 부하/배포
- **k6**: 조회수 부하(캐시/writeback 효과), 인기목록 캐시 hit-ratio, 검색 baseline. 결과는 `docs/load-test-results.md`/`docs/search-benchmark.md` 갱신(원칙 VII).
- **Docker**: 앱 멀티스테이지 Dockerfile(gradle build → JRE 런타임). docker-compose 전 스택.
- **CI**: GitHub Actions — `./gradlew build`(Testcontainers 통합 테스트 포함) + 이미지 빌드. 실 배포 제외.

## 3. 파생 설계 (결정에서 도출)
- **조회수 증가 위치**: 기존 `GET /api/properties/{id}`(5차까지 무변경) 처리 경로에 `ViewCountService.record(propertyId, viewerKey)` 훅 추가(best-effort). 신규 카운팅 엔드포인트 없음.
- **ACTIVE 이탈 정리 방식(P3 확정)**: 5차 `PropertyIndexEventRecorder`(승인/삭제/반려)와 **같은 지점**에서 랭킹 ZREM/캐시 evict 가 필요하나, **Outbox 핸들러가 아니라 `TransactionSynchronization.afterCommit` best-effort 동기 처리**로 확정. 이유: 캐시/랭킹은 `search.elasticsearch.enabled`(색인 게이팅)와 **독립**이어야 하고, TTL 이 짧아 정리 실패도 자연 수렴 → 5차 Worker 라우팅을 건드리지 않음(결합도↓). **색인만 Outbox(5차 그대로)**, **랭킹/캐시 정리는 afterCommit**.
- **캐시 추상화**: Spring `CacheManager`(RedisCache) 사용 vs 직접 `RedisTemplate` cache-aside. 인기목록/상세는 조립 로직·부분 무효화·**ACTIVE 조건부 저장**(P4)이 있어 **직접 cache-aside**가 명시적(메트릭 계측도 용이). `@Cacheable` 은 단순 케이스 한정 검토(plan).
- **인기 응답 DTO 분리(P2)**: 인기 목록은 **신규 `PopularPropertyResponse`**(요약 필드 + `viewCount`)로 반환. 기존 `PropertySummaryResponse`(QueryDSL 목록·5차 ES 검색이 공유)는 **건드리지 않음**. 상세는 `PropertyDetailResponse` 에 `viewCount` 추가(검색과 비공유라 안전).
- **스키마 변경**: `property.view_count BIGINT NOT NULL DEFAULT 0` 1개 컬럼 추가(6차 유일 DDL). (테이블명 `property`)
- **게이팅**: writeback 스케줄러/Sentry/Prometheus 는 프로퍼티로 on/off(테스트=off, 로컬/운영=on). 기존 `reservation.sweep-enabled`/`outbox.worker-enabled` 패턴 답습.
- **인프라(원칙 VI, 6차 신규)**: prometheus/grafana docker-compose 서비스. Testcontainers 는 Redis 재사용(별도 모니터링 컨테이너 불필요 — 메트릭은 단위/슬라이스 테스트로 검증).

## 4. 조회수/랭킹 동작 (요약)
| 시점 | 연산 | 비고 |
| --- | --- | --- |
| 상세 조회(ACTIVE 공개 성공) | **단일 Lua**: `SET NX EX` + `HINCRBY view:pending +1` + `ZINCRBY property:popular +1`(원자) | best-effort, cache hit/miss 무관 record. Redis 장애 시 조회는 정상 |
| 상세 조회(dedup 중복) | Lua 반환 0 → skip + `view.dedup.skip++` | 윈도우 내 재조회 |
| writeback(주기) | `EXISTS flushing?`→`RENAME pending→flushing`→`HGETALL`→`UPDATE view_count += delta`(1 tx)→커밋 후 `DEL` | 원자 배출, 유실 0 지향 |
| 감쇠(일 1회) | `ZUNIONSTORE key 1 key WEIGHTS factor` + `ZREMRANGEBYSCORE -inf (epsilon)` | 트렌딩 유지, 집합 크기 유한 |
| ACTIVE 이탈 | `ZREM property:popular` + `DEL property:detail` + `DEL popular:list` | afterCommit best-effort. 실패해도 **조회 시 DB ACTIVE 필터**가 제외 보장, ZSET stale 은 감쇠 정리 |

## 5. 엔티티/스키마 (상세 data-model)
- **DDL**: `ALTER TABLE property ADD COLUMN view_count BIGINT NOT NULL DEFAULT 0`. (테이블명 `property`)
- **Redis 키**: `view:dedup:{id}:{viewerKey}`(str, EX), `view:pending`(hash), `view:flushing`(hash, 배출 중 고정 키), `property:popular`(zset), `popular:list`(str/json, Top-50 단일 키, EX), `property:detail:{id}`(str/json, ACTIVE 만, EX).
- Property 엔티티에 `viewCount` 필드. **인기 목록 = 신규 `PopularPropertyResponse`(요약+viewCount)**, 상세 = `PropertyDetailResponse` 에 `viewCount` 추가. 기존 `PropertySummaryResponse`(검색 공유)는 불변(P2).

## 6. API (상세 contracts)
- `GET /api/properties/popular?limit=` [PUBLIC] — 인기 매물 Top-N(cache-aside, 단일 키 slice). `limit` 기본 10, 1~50. 응답 `List<PopularPropertyResponse>`.
- `GET /api/properties/{id}` [PUBLIC, 기존] — `PropertyDetailResponse` 에 `viewCount` 추가 + ACTIVE 성공 응답 시 카운트 훅(부수효과, best-effort). 캐시는 ACTIVE 만.
- `GET /actuator/prometheus` — 메트릭 스크레이프(앱 포트 8080, permitAll 무인증). 외부 노출은 compose/프록시에서 차단.

## 7. 정합성/멱등 (구현 전 확정)
- **조회수 유실 방지(원칙 II)**: writeback 은 `EXISTS` 선확인 후 `RENAME` 원자 배출 → 배출 중 증가분 신규 `view:pending` 로 보존. flushing 처리는 단일 트랜잭션, 커밋 성공 후 `DEL`. (처리 후~DEL 전 크래시 시 다음 주기 중복 가산 가능 — 저빈도·근사 카운터로 허용, 문서화.)
- **카운트 원자성(P1)**: dedup+델타+랭킹 **단일 Lua** 전체가 원자 경계(`SET NX EX`+`HINCRBY`+`ZINCRBY`). 부분 성공(dedup 키만/HINCRBY 만) 불가.
- **랭킹 정합(2계층, P1)**: 카운트와 `ZINCRBY` 는 ACTIVE 공개표현 분기에서만 수행(단, stale detail cache hit 는 비활성 member 를 재유입할 수 있음). **API "이탈 제외" 보장은 조회 시 DB ACTIVE 필터**가 담당(권위). **ZSET 은 stale member 를 허용**하고 `ZREM`(best-effort)·일 감쇠(`ZUNIONSTORE WEIGHTS`+`ZREMRANGEBYSCORE`)로 자연 정리. `popular:list` cache hit 은 최대 60s stale 허용. 랭킹은 감쇠된 트렌딩 신호라 DB `view_count` 와 분리 — Redis 유실 시 콜드 스타트로 자가 회복(영속 대상 아님).
- **캐시 무효화**: 상태 전이(승인/삭제/반려) 시 상세/인기 캐시 evict. Stale TTL 은 짧게(인기 60s, 상세 300s).
- **degrade(원칙 I 유추)**: Redis 장애 시 — 조회수/랭킹은 best-effort skip, 상세/인기는 cache-aside fallthrough 로 DB 직조회. 핵심 조회는 항상 응답.
- **관측성 무영향**: Sentry/Prometheus 실패가 요청 경로를 막지 않음(비동기/샘플링·미설정 시 no-op).

## 8. 인수 기준
- [ ] **ACTIVE 공개표현 반환 시에만**(`countablePublicAccess`) Redis 카운트(dedup 적용) → writeback → DB `view_count` 반영. 동일 viewerKey 윈도우 내 재조회 미가산. 비공개 표현(소유자/ADMIN 만 도달)·404 는 미집계.
- [ ] writeback 원자 배출로 배출 중 유입 증가분 **유실 0**(EXISTS→RENAME 기반) — 통합 테스트로 고정(원칙 VIII).
- [ ] `GET /api/properties/popular` 인기 Top-N 반환(`PopularPropertyResponse`), cache-aside 단일 키(hit/miss 메트릭). **ACTIVE 이탈 매물 제외는 조회 시 DB ACTIVE 필터가 보장(권위)** — `popular:list` cache hit 은 evict 실패 시 최대 60s stale 허용, ZSET stale member 는 감쇠/필터로 자연 정리(P1).
- [ ] 일 감쇠 스케줄러가 `ZUNIONSTORE WEIGHTS` 로 전체 score×factor + 임계 미만 제거(트렌딩 유지) — 단위 테스트로 고정.
- [ ] 매물 상세 cache-aside 는 **ACTIVE 공개표현만 저장/조회**(비공개 표현은 애초 미저장 → 접근제어 원천 차단), 상태 전이 시 evict(afterCommit best-effort → 비활성 후 **최대 TTL 300s stale 허용** 정책).
- [ ] Redis 장애 시 상세/인기 조회가 **degrade 로 정상 응답**(조회수만 skip).
- [ ] `/actuator/prometheus` 노출 + 캐시/조회수 커스텀 메트릭 존재. Grafana 대시보드 provisioning. Sentry DSN 설정 시 예외 캡처(미설정 시 비활성).
- [ ] k6 시나리오로 캐시 hit-ratio·DB write 감소 **실측치** 기록(원칙 VII, 선기재 금지).
- [ ] docker-compose 전 스택 기동 + GitHub Actions CI(빌드·테스트·이미지) 그린.
- [ ] 6차 기능이 기존 상세/검색/예약 경로 정합성에 영향 없음(회귀 그린).

## 9. 다음 산출물
`plan` → `data-model` → `contracts` → `tasks`. (신규 인프라 = Prometheus/Grafana/Sentry + docker-compose 전 스택 + GitHub Actions)
