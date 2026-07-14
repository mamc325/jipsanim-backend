# Implementation Plan: Outbox 기반 비동기 알림 (4차)

- Branch prefix: `feat/004-p<phase>-*`
- Spec: `./spec.md` (§2 확정 결정)
- Constitution: v1.0.0

## Summary
도메인 서비스가 상태 변경과 **같은 트랜잭션**에 `OutboxEvent(PENDING)` 를 적재한다. `@Scheduled` 폴링 Worker 가
`next_retry_at <= now` 인 이벤트를 `SKIP LOCKED` 로 선점→발행하고, `NotificationSender`(Mock)가 `notification` 을
생성한다(producer 멱등 event_key UNIQUE / consumer 멱등 outbox_event_id UNIQUE). 실패는 지수 백오프 재시도, `attempts>=6`(5회 재시도 소진) 시 DEAD → 관리자 수동 재처리.

## Constitution Check
| 원칙 | 준수 |
| --- | --- |
| II. 멱등/원자성 | 적재=동일 커밋, 소비=outbox_event_id UNIQUE, 선점=SKIP LOCKED |
| IV. Outbox | 알림을 도메인 트랜잭션에서 분리, 실패 격리(DEAD), Worker 비동기 |
| VI. 차수 분리 | 브로커/실발송/실시간 푸시는 도입 안 함(6차) |
| VIII. 상태전이 테스트 | Outbox PENDING→PROCESSING→PUBLISHED/DEAD, 재시도·재처리 테스트 |

## 핵심 흐름

### D1. 이벤트 적재 (Producer) — **직접 append 로 통일 (리뷰 P1-3)**
- **정본 방식**: 도메인 서비스가 **자신의 `@Transactional` 안에서** `OutboxEventPublisher.append(aggregateType, aggregateId, eventType, eventKey, payload)` 직접 호출. 트랜잭션 경계가 명확하고 단순.
- **기존 코드 정리**: 1차 매물 승인/거절은 현재 `ApplicationEventPublisher`(`PropertyApprovedEvent/RejectedEvent`)로 발행 중 → **직접 append 로 이관**하고 관련 event/listener 정리(tech-stack ADR-004 갱신). `@TransactionalEventListener(AFTER_COMMIT)`는 원자성이 깨지므로 채택 안 함(쓴다면 반드시 **동일 트랜잭션** `@EventListener`).
- **producer 멱등**: `append` 는 native **`INSERT ... ON DUPLICATE KEY UPDATE id=id`(no-op)** 로 저장 → event_key 중복이어도 예외 없이 흡수(도메인 트랜잭션 rollback-only 오염 방지, 리뷰 P1). payload 는 Jackson JSON.
- 도메인 로직에 강결합 금지: 각 서비스는 publisher 만 의존(발송 세부는 모름).

### D2. 폴링 Worker (Consumer)
- `OutboxPollingScheduler` `@Scheduled(fixedDelay=…)` → `OutboxWorker.pollAndPublish()`.
- **① reaper**: `PROCESSING AND processing_started_at < now - RECLAIM_TIMEOUT` → `PENDING` 복구(crash 고착 방지, 리뷰 P0-1).
- **② 선점(claim tx)**: `status='PENDING' AND next_retry_at <= now ORDER BY id LIMIT N FOR UPDATE SKIP LOCKED` → `PROCESSING, processing_started_at=now` 커밋.
- **③ 처리**: 각 이벤트 `NotificationDispatcher.dispatch(event)` → 성공 `PUBLISHED`(published_at) / 실패 `onFailure`.
  - 실패 카운트/백오프 갱신은 **`REQUIRES_NEW` 트랜잭션**으로 커밋 → dispatch 롤백과 무관하게 attempts/next_retry_at 보존.
- 테스트 비활성: `@ConditionalOnProperty(outbox.worker-enabled, matchIfMissing=true)` + 테스트에서 false, 단위 호출로 검증(2차 sweep 패턴 재사용).

### D3. 발행/멱등 (Dispatcher + Sender)
- `NotificationDispatcher`: eventType → 수신자·제목·메시지 렌더링 → `Notification.create(..., outboxEventId)`.
- **멱등**: `notification.outbox_event_id` UNIQUE. 이미 존재하면 skip(중복 배달 흡수) 후 이벤트 PUBLISHED.
- `NotificationSender`(인터페이스) ← `MockNotificationSender`(save + log). 후에 SMTP 어댑터 교체 지점.

### D4. 재시도/DEAD (spec §2-4 와 동일 기준)
- `onFailure(event, ex)`(REQUIRES_NEW): attempts++ , last_error 기록.
  - `attempts < 6` → `PENDING`, `next_retry_at = now + BACKOFF[attempts]`, `BACKOFF={1:1m,2:5m,3:15m,4:1h,5:6h}`.
  - `attempts >= 6`(5회 재시도 모두 실패) → `DEAD`.
- 재처리: `POST /admin/outbox-events/{id}/reprocess` → DEAD→PENDING, attempts=0, next_retry_at=now.

### D5. 도메인 연결 지점
| eventType | 적재 위치(기존 서비스 수정) |
| --- | --- |
| VISIT_RESERVATION_CONFIRMED | `PaymentService.confirm()` (확정 트랜잭션) |
| VISIT_RESERVATION_CANCELLED / REFUND_COMPLETED | `CancellationService.cancel()` (취소 트랜잭션, 2건 적재) |
| SETTLEMENT_PAID | `SettlementService.payout()` (지급 트랜잭션) |
| PROPERTY_APPROVED / PROPERTY_REJECTED | 1차 매물 검증 승인/거절 서비스 |
| WAITING_QUEUE_INVITATION_GRANTED | 예약권 발급 감지 지점(대기열 tryIssue 성공/ sweep 승계) |
- **주의(§4, 리뷰 P0-2 / best-effort 결정)**: 발급 이벤트는 Redis 상태 변경이라 도메인 DB 커밋과 별개 → 발급을 감지한 직후 짧은 트랜잭션으로 append.
  - tryIssue Lua 가 토큰 발급 시 **`INCR invitation:{slotId}` 로 invitationSeq** 를 함께 반환 → `event_key = WAITING_QUEUE_INVITATION_GRANTED:{slotId}:{userId}:{invitationSeq}`.
  - 폴링/재감지로 append 가 중복 호출돼도 **event_key UNIQUE 로 흡수**(중복 방지 O).
  - **유실은 허용(best-effort)**: Redis 발급 ~ DB append 사이 crash 시 이 알림만 유실 가능 → 재입장/다음 발급으로 복구. 완전 방지는 `waiting_invitation` 테이블/Redis Stream(범위 밖).

## 설정값 (application.yml `outbox.*`, 리뷰 P2)
| key | 기본값 | 용도 |
| --- | --- | --- |
| `outbox.worker-enabled` | true | Worker 활성(테스트 false) |
| `outbox.worker-delay-ms` | 2000 | 폴링 스케줄러 `fixedDelay` |
| `outbox.batch-size` | 50 | 선점 조회 `LIMIT N` |
| `outbox.reclaim-timeout-seconds` | 300 | PROCESSING 고착 복구 임계(reaper) |
`OutboxProperties`(@ConfigurationProperties) 로 바인딩(2차 `ReservationProperties` 패턴 재사용).

## Architecture / Package
```
com.jipsanim.outbox
├─ domain      (OutboxEvent + OutboxStatus)
├─ repository  (OutboxEventRepository: SKIP LOCKED 조회)
├─ publisher   (OutboxEventPublisher.append)
├─ worker      (OutboxWorker + OutboxPollingScheduler)
└─ controller  (OutboxAdminController: 조회/재처리)

com.jipsanim.notification
├─ domain      (Notification)
├─ repository  (NotificationRepository)
├─ dispatch    (NotificationDispatcher, NotificationSender, MockNotificationSender)
└─ controller  (NotificationController: /me/notifications, 읽음)
```

## Testing Strategy
- 단위: 백오프 계산(attempts→delay, ≥6 DEAD), Dispatcher 렌더링/소비 멱등(중복 outbox_event_id → 1건).
- 통합(Testcontainers): 적재 원자성(롤백 시 미생성), **producer 멱등(append 2회 → 이벤트 1건)**, Worker 폴링 발행, 실패→재시도→DEAD, **PROCESSING reaper 복구**, DEAD 재처리, 7종 이벤트 e2e.
- 실패 주입: `NotificationSender` 스텁이 예외 던지도록 해서 재시도/DEAD 경로 검증.

## Phasing
1. OutboxEvent/Notification 엔티티 + 상태 + 리포지토리(SKIP LOCKED)
2. Publisher(append) + Dispatcher + MockSender + 멱등
3. Worker + 폴링 스케줄러 + 백오프/DEAD
4. 도메인 7종 연결(기존 서비스 append) + 조회/재처리 API
5. 통합 E2E + docs 마감

## Complexity Tracking
- 없음(기존 MySQL/JPA/스케줄러 패턴 재사용, 브로커 미도입).
