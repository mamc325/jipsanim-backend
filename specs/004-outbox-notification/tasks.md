# Tasks: Outbox 기반 비동기 알림 (4차)

규칙: `[P]` 병렬 가능. 상태전이/멱등/백오프는 테스트 먼저. 브랜치 `feat/004-p<phase>-*`.

## Phase 1. 엔티티/상태/리포지토리
- [x] T400 `OutboxEvent` 엔티티(+OutboxStatus, **event_key UNIQUE**, attempts, next_retry_at, **processing_started_at**, payload JSON) + 상태전이(markProcessing/markPublished/markFailed(백오프·DEAD)/reset)
- [x] T401 `Notification` 엔티티(+NotificationType, outbox_event_id UNIQUE, is_read) + markRead
- [x] T402 `OutboxEventRepository` — 선점 `SKIP LOCKED`(PENDING, next_retry_at<=now, LIMIT), **reaper 쿼리**(PROCESSING & processing_started_at<타임아웃 → PENDING), status 필터 페이지; `NotificationRepository`(recipient/unread)
- [x] T403 [P] 테스트: 상태전이(PENDING→PROCESSING→PUBLISHED/DEAD, 재시도 PENDING), 백오프 계산(attempts→delay, ≥6 DEAD)

## Phase 2. Producer + Dispatcher + Sender(멱등)
- [x] T410 `OutboxEventPublisher.append(type, aggId, eventType, **eventKey**, payload)` — 도메인 트랜잭션 내 적재. **native `INSERT ... ON DUPLICATE KEY UPDATE id=id`(no-op) 로 고정** — event_key 중복을 예외 없이 흡수(트랜잭션 오염 방지, 리뷰 P1). `INSERT IGNORE`(오류 은폐)·JPA save+catch 지양
- [x] T411 `NotificationSender`(인터페이스) + `MockNotificationSender`(notification 저장 + 로그)
- [x] T412 `NotificationDispatcher` — eventType→수신자/제목/메시지 렌더링, **소비 멱등**(outbox_event_id UNIQUE → 중복 skip)
- [x] T413 [P] 테스트: append 2회(같은 event_key) → 이벤트 1건(producer 멱등); 같은 outbox_event 2회 dispatch → Notification 1건(소비 멱등)

## Phase 3. Worker + 폴링 + 백오프/DEAD
- [x] T420 `OutboxWorker.pollAndPublish()` — ①reaper(PROCESSING 타임아웃→PENDING) ②SKIP LOCKED 선점→PROCESSING(processing_started_at) ③dispatch→PUBLISHED/onFailure
- [x] T421 백오프/DEAD(onFailure **REQUIRES_NEW**): attempts<6 → PENDING+next_retry_at(`{1:1m,2:5m,3:15m,4:1h,5:6h}`), ≥6 → DEAD
- [x] T422 `OutboxPollingScheduler`(@Scheduled(fixedDelay=`outbox.worker-delay-ms`), `@ConditionalOnProperty(outbox.worker-enabled)` 테스트 비활성) + `OutboxProperties`(worker-delay-ms=2000, batch-size=50, reclaim-timeout-seconds=300)
- [x] T423 [P] 통합: 폴링 발행→Notification 생성+PUBLISHED, 발행 실패→재시도→DEAD, **PROCESSING reaper 복구**

## Phase 4. 도메인 연결 + API
- [x] T430 도메인 적재(직접 append, event_key): confirm / cancel(2건) / payout / **매물 승인·거절(ApplicationEventPublisher→직접 append 이관, 기존 event/listener 정리)**
- [x] T431 예약권 발급 `WAITING_QUEUE_INVITATION_GRANTED` 적재(best-effort):
  - `try_issue.lua`: 발급 성공 시 `INCR invitation:{slotId}` → **`{userId, invitationSeq}` 반환**(현재 userId 단일 반환 변경)
  - `WaitingQueueService.tryIssue()` 반환 `Long` → **`IssuedInvitation(userId, invitationSeq)`** 값 객체로 변경
  - 발급 감지 지점에서 짧은 트랜잭션 append, `event_key=WAITING_QUEUE_INVITATION_GRANTED:{slotId}:{userId}:{invitationSeq}`
- [x] T431b **기존 대기열 코드/테스트 수정**: `tryIssue` 호출부(WaitingService 등) + `WaitingQueueIntegrationTest`/`SweepIntegrationTest` 등 반환타입 변경 반영
- [x] T432 `GET /me/notifications`(unread 필터) + `PATCH /notifications/{id}`(읽음, 본인 검증)
- [x] T433 `GET /admin/outbox-events`(status 필터) + `POST /admin/outbox-events/{id}/reprocess`(DEAD→PENDING, 아니면 409)
- [x] T434 [P] 테스트: 도메인 커밋→이벤트 적재, 롤백→미적재(원자성); 읽음/재처리 상태전이·권한(403)

## Phase 5. 마감
- [x] T440 통합 E2E: 예약 확정→OutboxEvent→Worker 발행→Notification→/me 조회→읽음
- [x] T441 [P] docs/api-design·erd·ROADMAP 4차 상태, project-status 갱신, 인수기준 체크

## 의존성
```
Phase1 → Phase2(멱등 소비) → Phase3(Worker/재시도) → Phase4(도메인 연결/API) → Phase5
```
