# 집사님 ROADMAP (차수 분리)

Constitution 원칙 VI(스코프 차수 분리)에 따른 기능 차수. 각 차수는 완주 가능한 세로 슬라이스이며,
이전 차수가 동작·테스트된 뒤 다음으로 넘어간다.

| 차수 | 폴더 | 범위 | 상태 |
| --- | --- | --- | --- |
| 1차 MVP | `specs/001-price-verification-mvp` | 회원/권한 → 외부 API 배치 수집 → 시세 기준(p10~p90/IQR) 생성·관리자 승인 → 매물 등록·가격 리스크 검증 → 승인 매물 QueryDSL 조건 검색 | ✅ **구현 완료** |
| 2차 | `specs/002-visit-reservation-queue` | 방문 슬롯, Redis Sorted Set 대기열, TTL 예약권(원자적 발급), Mock 결제 확정, 예약 확정. **슬롯당 1명 확정 구조로 단순화** | ✅ **구현 완료**(k6 동시 500명 검증) |
| 3차 | `specs/003-refund-settlement` | 예약 취소/환불(24h 전 전액·슬롯 재개방), 중개사 월별 정산(배치·수수료 20%·carry_over 이월) | ✅ **구현 완료**(Phase 1~5, E2E 통과) |
| 4차 | `specs/004-outbox-notification` | Outbox Pattern(동일 커밋 적재·폴링 Worker·SKIP LOCKED), Mock 알림 비동기, 지수 백오프 재시도·DEAD 격리·수동 재처리, 이중 멱등 | ✅ **구현 완료**(Phase 1~5, E2E 통과) |
| 5차 | `specs/005-search-elasticsearch` | Elasticsearch + nori 한글 검색(Outbox 색인·전담 검색 엔드포인트·multi_match 부스팅). **latency 아닌 검색 품질/관련도 어필** | ✅ **구현 완료**(Phase 1~5, E2E 통과) |
| 6차 | `specs/006-ops-performance` | Redis 인기 매물 캐싱/조회수 카운팅(원자 Lua·writeback·트렌딩 감쇠), 관측성(Prometheus/Grafana/Sentry), k6 부하, Docker/CI | ✅ **구현 완료**(Phase 1~5) |

## 리뷰 반영 결정 사항 (Locked Decisions)

1. **대기열**: MVP/2차 모두 슬롯당 1명 확정. 상위 K명 동시 예약권 발급은 채택하지 않음. 예약권은 한 번에 한 명에게만 발급.
   - 이력서 표현: "500명 동시 대기열 진입 시 순번 정합성과 중복 예약 방지 검증" (500명 전원 예약 처리로 표현하지 않음).
2. **예약권 원자성**: Redis Lua Script 또는 `ZPOPMIN` + `SET NX`. Worker 다중 인스턴스에서도 슬롯당 active token 1개 보장.
3. **VisitSlot 상태**: DB 는 `OPEN / RESERVED / CLOSED / EXPIRED`. HELD(임시 점유)는 **Redis 예약권(TTL 토큰)**으로 관리하고 DB 상태로 두지 않음 — 결제 확정 시 `OPEN → RESERVED`, 3차 취소 시 `RESERVED → OPEN` 재개방. (전이표는 002/003 spec)
4. **정산**: 1차 MVP 제외. 3차에서 환불 발생 월 차감 + 음수 정산 이월 정책 명시.
5. **시세 계산**: 단순 min/max 미사용. p10~p90 또는 IQR. 최소 표본 미달 시 `INSUFFICIENT_DATA` / 검증은 `REVIEW_REQUIRED`.
6. **Elasticsearch**: 5차 구현 완료. nori analyzer(decompound_mode=mixed) + multi_match 필드 부스팅으로 한글 검색 품질 어필. 색인은 4차 Outbox 재사용(DB↔ES 정합성). latency 아닌 관련도로 표현.
7. **WebClient**: `Flux.flatMap(fn, concurrency=N)` bounded concurrency 를 구현했을 때만 "비동기 병렬 수집" 표현 사용.
8. **성능 수치**: 실측 전 미기재.
