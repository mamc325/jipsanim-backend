# Feature Spec: Outbox 기반 비동기 알림 (4차)

- Feature Branch prefix: `feat/004-p<phase>-*`
- Status: Design Locked (구현 대기)
- Constitution: v1.0.0 (원칙 IV Outbox, II 멱등/원자성)
- 선행: 1차(매물 검증)·2차(예약/대기열)·3차(취소/정산) 완료

## 1. 범위
- 도메인 트랜잭션과 **같은 커밋**에 `OutboxEvent` 적재 → **폴링 Worker** 가 비동기 발행
- 발행 = `NotificationSender`(Mock: `notification` 저장 + 로그). 실발송 인프라 없음(포트 분리)
- **at-least-once + 멱등 소비**(중복 알림 0), 실패 시 **지수 백오프 재시도**, 초과 시 **DEAD 격리 + 수동 재처리**
- Out of scope: 실제 이메일/SMS/푸시, WebSocket 실시간 푸시, 메시지 브로커(6차)

## 2. 확정 결정 (2026-07-14)

### 2-1. Worker → **DB 폴링 스케줄러**
- 모놀리스에 브로커(Kafka/Redis Stream)는 과함. Outbox 핵심(트랜잭션 정합·재처리)에 집중.
- `@Scheduled` Worker 가 발행 대상은 **`status='PENDING' AND next_retry_at <= now`** 로 조회한다. (PROCESSING 고착 복구는 별도 reaper — §3/§4)
- 중복 처리 방지: 조회 시 **`FOR UPDATE SKIP LOCKED`** 로 선점(다중 워커 안전). Handler 는 **멱등**.

### 2-2. 전송 → **Mock (`NotificationSender` 인터페이스 + `MockNotificationSender`)**
- SMTP 인증/보안/발송실패는 포트폴리오 핵심과 거리가 멂. 포트만 분리해 후에 SMTP 어댑터 교체 가능.
- 발행 결과는 `notification` 테이블 저장 + 로그.

### 2-3. 대상 이벤트 → **전부 포함(7종)**
| eventType | 발생 지점 | 수신자(recipient = user_id) |
| --- | --- | --- |
| `VISIT_RESERVATION_CONFIRMED` | 결제 확정(PaymentService.confirm) | 예약자 |
| `VISIT_RESERVATION_CANCELLED` | 취소(CancellationService.cancel) | 예약자 |
| `REFUND_COMPLETED` | 취소=환불 완료(CancellationService.cancel) | 예약자 |
| `SETTLEMENT_PAID` | 정산 지급(SettlementService.payout) | 중개사(realtor→user) |
| `PROPERTY_APPROVED` | 매물 승인(1차 검증) | 중개사 |
| `PROPERTY_REJECTED` | 매물 거절(1차 검증) | 중개사 |
| `WAITING_QUEUE_INVITATION_GRANTED` | 예약권 발급(대기열 tryIssue) | 발급 대상자 |

### 2-4. 재시도 → **5회 재시도 + 지수 백오프 → DEAD**
- 백오프(각 실패 후 다음 재시도 대기): `attempts=1→1m, 2→5m, 3→15m, 4→1h, 5→6h`.
- **`attempts >= 6`(5회 재시도까지 모두 실패) 이면 `DEAD`.** (spec/plan 동일 기준 — 리뷰 Minor)
- `DEAD` 는 관리자 **수동 재처리 API** 로 복구(attempts=0, next_retry_at=now, PENDING 전환).

### 2-5. 멱등 → **producer 키(event_key) + consumer 키(outbox_event_id) 이중 방어** (리뷰 P0-2)
- **Producer 멱등**: `outbox_event.event_key` **UNIQUE** — 같은 도메인 사건에 대해 OutboxEvent 자체가 2건 생기지 않음.
  - 형식: `{EVENT_TYPE}:{도메인식별자}` 예) `VISIT_RESERVATION_CONFIRMED:{reservationId}`, `REFUND_COMPLETED:{paymentId}`, `SETTLEMENT_PAID:{settlementId}`, `PROPERTY_APPROVED:{propertyId}`.
    - `REFUND_COMPLETED` 는 **paymentId** 사용(리뷰 P2): `refund.payment_id` 가 UNIQUE 이고 취소 시점에 이미 로드된 값 → DB 생성값 refundId(save·flush 순서 의존) 회피.
  - 예약권 발급은 Redis 발급 시 INCR 로 부여하는 **invitation 식별자**를 사용: `WAITING_QUEUE_INVITATION_GRANTED:{slotId}:{userId}:{invitationSeq}` (§4, plan D5).
  - `append()` 는 **DB 레벨 no-op** 으로 적재 — **`INSERT ... ON DUPLICATE KEY UPDATE id=id` 하나로 고정**. event_key 중복이어도 **예외를 던지지 않아** 도메인 트랜잭션이 rollback-only 로 오염되지 않음(리뷰 P1). `INSERT IGNORE` 는 NOT NULL 위반·데이터 절삭 오류까지 경고로 숨기므로 **사용 금지**. JPA `save()` 후 `DataIntegrityViolation` catch 방식도 트랜잭션 오염 위험이라 **지양**.
- **Consumer 멱등**: `notification.outbox_event_id` UNIQUE — 같은 이벤트가 2회 발행돼도 알림 1건.

## 3. Outbox 이벤트 상태 전이
```
PENDING --(worker 선점, processing_started_at=now)--> PROCESSING --(발행 성공)--> PUBLISHED
                              |
                              +--(발행 실패, attempts<6)--> PENDING (next_retry_at = now + backoff)
                              +--(발행 실패, attempts>=6)--> DEAD
PROCESSING --(processing_started_at 타임아웃 = 처리 중 crash 복구)--> PENDING   ← 리뷰 P0-1
DEAD --(관리자 재처리)--> PENDING (attempts=0, next_retry_at=now)
```

## 4. 원자성/멱등 (구현 전 확정)
- **적재 원자성**: OutboxEvent 저장은 도메인 상태 변경과 **동일 `@Transactional`** — 롤백 시 이벤트도 소멸(유령 알림 0), 커밋 시 반드시 발행 보장(원칙 IV).
- **Producer 멱등**: `event_key` UNIQUE → 같은 사건에 대해 이벤트 2건 방지(리뷰 P0-2, §2-5).
- **소비 멱등**: `notification.outbox_event_id` UNIQUE. 중복 배달 시 이미 존재 → 알림 생성 skip, 이벤트만 PUBLISHED 로 종결.
- **선점 원자성**: batch 조회에 `SKIP LOCKED` — 다중 워커에서도 한 이벤트를 한 워커만 처리.
- **PROCESSING crash 복구(리뷰 P0-1)**: 선점 시 `processing_started_at` 기록. Worker 는 `PROCESSING` 이면서 `processing_started_at < now - RECLAIM_TIMEOUT`(기본 `outbox.reclaim-timeout-seconds=300`) 인 이벤트를 `PENDING` 으로 되돌려 재처리(처리 중 앱 종료로 인한 영구 고착 방지). 실패 카운트 갱신은 별도 트랜잭션(REQUIRES_NEW)으로 커밋(plan D2/D4).
- **대기열 발급 이벤트 = best-effort (명시적 결정, 2026-07-14)**: 예약권 발급은 Redis(Lua) 상태 변경이라 DB 트랜잭션과 같은 커밋이 아님.
  - **중복 방지 O**: 발급 감지 지점에서 짧은 DB 트랜잭션으로 적재하되 **Redis INCR invitation 식별자를 event_key 에 포함** → producer 멱등(event_key UNIQUE).
  - **유실 방지 X(허용)**: Redis 발급 성공 ~ DB append 사이에 프로세스가 죽으면 이 알림은 유실될 수 있음. 사용자는 **대기열 재입장/다음 발급**으로 자연 복구되므로 4차에서는 best-effort 로 둔다.
  - 완전 유실 방지가 필요해지면 `waiting_invitation` 영속 테이블(발급과 같은 트랜잭션에 append) 또는 Redis Stream 을 도입한다(범위 밖). plan D5 참조.
- **보장 수준 요약**: 나머지 6종은 도메인 DB 커밋과 동일 트랜잭션 → **유실 0**. 발급 알림 1종만 **best-effort(중복 0·유실 가능)**.

## 5. 엔티티 (상세 data-model)
- **OutboxEvent**(신규): aggregate_type, aggregate_id, event_type, **event_key UNIQUE**, payload(JSON), status, attempts, next_retry_at, **processing_started_at**, last_error, published_at.
- **Notification**(신규): recipient_user_id, type, title, message, is_read, outbox_event_id UNIQUE, created_at.

## 6. API (상세 contracts)
- `GET /api/me/notifications?unread=` [USER/REALTOR/ADMIN] — 본인 알림 목록
- `PATCH /api/notifications/{id}` [본인] — 읽음 처리(부수효과 없는 플래그 → PATCH)
- `GET /api/admin/outbox-events?status=` [ADMIN] — Outbox 모니터링
- `POST /api/admin/outbox-events/{id}/reprocess` [ADMIN] — DEAD 수동 재처리(상태전이 → POST)

## 7. 상태 전이 (도메인)
```
OutboxEvent : PENDING → PROCESSING → PUBLISHED | (재시도)PENDING | DEAD → (재처리)PENDING
Notification: (생성=발행 결과) is_read false → true
```

## 8. 인수 기준
- [ ] 도메인 커밋 시 OutboxEvent 동일 커밋 적재, 롤백 시 이벤트 미생성(원자성).
- [ ] **Producer 멱등**: 같은 사건에 append 2회 호출돼도 OutboxEvent 1건(event_key UNIQUE).
- [ ] Worker 폴링 → 발행 → Notification 생성 + Outbox PUBLISHED.
- [ ] **소비 멱등**: 같은 이벤트 2회 처리돼도 Notification 1건(outbox_event_id UNIQUE).
- [ ] 발행 실패 → attempts 증가·next_retry_at 지수 백오프, `attempts>=6` DEAD.
- [ ] **PROCESSING 고착 복구**: processing_started_at 타임아웃 초과 이벤트가 PENDING 으로 복귀.
- [ ] DEAD 재처리 → PENDING 복귀 후 재발행.
- [ ] 7종 이벤트가 각 도메인 지점에서 적재되고 수신자에게 알림 생성.
- [ ] 알림 실패가 도메인 트랜잭션(예약/정산/승인) 실패로 전파되지 않음.

## 9. 다음 산출물
`plan` → `data-model` → `contracts` → `tasks`. (신규 인프라 없음 — 기존 MySQL 스택 재사용)
