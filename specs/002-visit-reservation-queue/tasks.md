# Tasks: 방문 예약 대기열 (2차)

규칙: `[P]` 병렬 가능. 핵심 상태전이/원자성은 테스트 먼저. 브랜치 `feat/002-p<phase>-*`.

## Phase 0. 인프라
- [ ] T200 `docker-compose.yml` 에 `redis:7` 추가(포트 6379, healthcheck)
- [ ] T201 `spring-boot-starter-data-redis` 의존성 + `spring.data.redis.*` 설정(로컬/Testcontainers)
- [ ] T202 [P] `application.yml`: `reservation.token-ttl-seconds=300`, `sweep-interval-ms=2000`, `fee-amount`
- [ ] T203 [P] Testcontainers 에 Redis 컨테이너 추가(@ServiceConnection)

## Phase 1. 대기열 + 예약권 (Redis)
- [ ] T210 [P] 테스트: Lua `tryIssue` — 토큰 있으면 no-op, 빈 큐 no-op, 선두 발급, 동시성 하 슬롯당 1개
- [ ] T211 `RedisConfig`(StringRedisTemplate, DefaultRedisScript<Lua>)
- [ ] T212 `WaitingQueueService`: enqueue/rank/tryIssue(Lua)/`tryIssueIfSlotOpen`(slot OPEN 가드, P1-3)/hasToken/tokenTtl + **정리 2종**: `releaseToken`(token만, 큐 유지 — 실패/만료) / `cleanupSlot`(token+큐+active-set — 확정/마감) + `waiting:slots` 관리 (Refs: T210, plan D1)

## Phase 2. 방문 슬롯
- [ ] T220 `VisitSlot` 엔티티(status OPEN/RESERVED/CLOSED/EXPIRED) + 리포지토리(UNIQUE property_id+start_time)
- [ ] T221 [P] 테스트: 슬롯 CRUD 소유자/상태 제약(RESERVED 마감 불가)
- [ ] T222 슬롯 컨트롤러/서비스: POST/GET/DELETE(RESERVED 거부 409, OPEN→CLOSED + cleanupSlot + PENDING 정리, P1-4)

## Phase 3. 대기열 API + 발급 트리거
- [ ] T230 [P] 테스트: 진입/순번 조회 시 tryIssue, 큐 중복 진입 409(ALREADY_WAITING), **토큰 보유자 재진입 409(ALREADY_GRANTED)**, 선두 tokenGranted/position=0
- [ ] T231 `POST /visit-slots/{id}/waiting`(진입+tryIssue), `GET .../waiting/me`(순번+tryIssue+TTL)

## Phase 4. 예약 생성
- [ ] T240 [P] 테스트: 토큰 보유자만 예약, slot OPEN 아니면 409, 동일 사용자 반복 호출 시 기존 반환(멱등, P1-1)
- [ ] T241 `Reservation`(+`active_reservation_key` UNIQUE, `expires_at`)/`Payment`(reservation_id UNIQUE) 엔티티 + 리포지토리
- [ ] T242 `POST /visit-slots/{id}/reservations`: 토큰 검증→(멱등) 기존 활성 PENDING 반환 or Reservation(PENDING,expires_at)+Payment(READY) 생성 (plan D2)
- [ ] T243 `GET /me/reservations`

## Phase 5. 결제 확정/실패
- [ ] T250 [P] 테스트: 소유자 검증(403), **멱등 재시도 시 토큰 삭제됐어도 200**(P1-b), 확정 트랜잭션(PAID+CONFIRMED+RESERVED+cleanupSlot), 동시 확정 1건, 만료 시 409
- [ ] T251 `POST /payments/{id}/confirmation`: 잠금→소유자검증→**이미 PAID면 현재상태 반환**→READY면 토큰검증→만료검사→트랜잭션→cleanupSlot (plan D3, P1-b/P2-2/P2-3)
      - **잠금 조회~상태변경~커밋까지 동일 `@Transactional` 범위**(PESSIMISTIC_WRITE 유효 구간 보장)
- [ ] T252 `POST /payments/{id}/failure`: 소유자검증→FAILED+Reservation EXPIRED+**releaseToken(큐 유지)**
      - (선택) releaseToken 직후 `tryIssueIfSlotOpen(slotId)` 즉시 호출로 다음 대기자 발급 응답성↑ (미호출 시 sweep/폴링이 처리)

## Phase 6. 만료 재발급 (sweep)
- [ ] T260 [P] 테스트: 토큰 만료 후 **PENDING 정리(EXPIRED)가 먼저 → 다음 대기자 예약 409 안 남**(P2), slot OPEN 유지
- [ ] T261 `TokenSweepScheduler`(2초) — 순서: ①만료 PENDING→EXPIRED+Payment FAILED ②slot OPEN 확인 ③tryIssueIfSlotOpen ④빈 큐 active-set 제거 (plan D1)

## Phase 7. 통합 + k6 부하
- [ ] T270 통합(Testcontainers MySQL+Redis): 진입→발급→예약→확정→RESERVED, 만료 재발급, 중복예약 방지
- [ ] T271 k6 `loadtest/k6/reservation-queue.js`: 동시 500명 진입 → 순번 정합·확정 1건·중복 0·에러율
- [ ] T272 [P] 인수기준(spec §4) 체크, docs/load-test-results 에 대기열 수치 추가

## 의존성
```
Phase0 인프라 → Phase1 큐(Lua) → Phase2 슬롯 → Phase3 대기열API
                                              → Phase4 예약 → Phase5 결제확정 → Phase6 sweep → Phase7 통합/부하
```
