# 5차 검색 벤치마크 계획 (Before/After)

> QueryDSL 조건 검색(Before) → Elasticsearch nori 전문검색(After) 전후 비교. 5차 구현과 함께 채운다.
> **원칙(Constitution VII)**: 핵심 근거는 **검색 품질/관련도**. latency는 보조 지표이며 "개선"을 대표 문구로 삼지 않는다(소량 데이터에서 ES가 불리할 수 있음).

## 0. 측정 대상

- **Before**: `GET /api/properties` (QueryDSL 조건 검색, MySQL)
- **After**: `GET /api/properties/search?q=` (ES nori 전문검색 + 필터 + 관련도)
- 두 엔드포인트는 5차에서 **공존** → 동일 데이터/질의로 언제든 재현 가능.

## 측정 환경 (기입)

| 항목 | 값 |
| --- | --- |
| 실행일 | TBD |
| 머신(CPU/RAM) | TBD |
| MySQL 버전/설정 | TBD |
| ES 버전 / nori | TBD (예: 8.13.4 + analysis-nori) |
| 데이터 생성 방식 | TBD (시드 스크립트) |
| 부하 도구 | TBD (k6 / JUnit 가상스레드) |

---

## 1. 검색 품질 (핵심 지표)

**지표**: Precision@5, MRR@10, Top-3 Hit(기대 매물이 상위 3위 내 노출 여부)

### 1-1. Golden Query Set (질의 + 기대 매물)

> 시드 데이터에 대해 "이 질의에 나와야 하는 매물"을 사전 라벨링. 구현 전 확정.

| # | 질의(q) | 의도 | 기대 관련 매물(정답 라벨) |
| --- | --- | --- | --- |
| Q1 | 강남역 오피스텔 | 역명 + 유형 | TBD |
| Q2 | 역세권 풀옵션 | 복합어(decompound) + 설명 매칭 | TBD |
| Q3 | 월세 70 강남 | 금액 + 지역(필터+텍스트) | TBD |
| Q4 | 테헤란로 원룸 | 도로명 + 방 개수 | TBD |
| Q5 | 강남구 전세 오피스텔 | 지역 + 거래유형 + 유형 | TBD |
| Q6 | TBD | TBD | TBD |

### 1-2. 품질 결과표

| Query | Endpoint | Precision@5 | MRR@10 | Top-3 Hit | 비고 |
| --- | --- | --- | --- | --- | --- |
| Q1 | QueryDSL(Before) | TBD | TBD | TBD | 자연어 전문검색 불가(정확일치/필터만) |
| Q1 | ES nori(After) | TBD | TBD | TBD | nori 형태소 + 부스팅 |
| Q2 | QueryDSL(Before) | TBD | TBD | TBD | 복합어 매칭 불가 |
| Q2 | ES nori(After) | TBD | TBD | TBD | decompound 매칭 |
| … | … | | | | |

> 정직한 프레이밍: Before는 "자연어 질의 매칭 0" 인 경우가 많음 → "0건 → 관련 매물 상위 노출"이 핵심 스토리.

---

## 2. 응답 시간 (보조 지표)

**데이터 규모별** × **동시 요청별**로 p50/p95/p99. **주의: latency를 대표 성과로 박지 않는다.**

| Dataset | Endpoint | Query | Concurrency | p50(ms) | p95(ms) | p99(ms) | avg(ms) | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 10k | QueryDSL | 강남역 오피스텔 | 1 | TBD | TBD | TBD | TBD | before |
| 10k | ES nori | 강남역 오피스텔 | 1 | TBD | TBD | TBD | TBD | after |
| 10k | QueryDSL | 강남역 오피스텔 | 30 | TBD | TBD | TBD | TBD | |
| 10k | ES nori | 강남역 오피스텔 | 30 | TBD | TBD | TBD | TBD | |
| 100k | QueryDSL | 강남역 오피스텔 | 1/10/30 | TBD | TBD | TBD | TBD | |
| 100k | ES nori | 강남역 오피스텔 | 1/10/30 | TBD | TBD | TBD | TBD | |
| 300k~1M | QueryDSL | … | … | TBD | | | | 가능 시 |
| 300k~1M | ES nori | … | … | TBD | | | | 가능 시 |

- 동시 요청: 1 / 10 / 30 기준.
- 관측 포인트: 데이터가 커질수록(≥100k, LIKE/정렬 부하) Before가 불리해지는 구간을 확인.

---

## 3. DB 부하 (아키텍처 근거)

**ES 검색은 MySQL을 치지 않는다**는 것을 정량화.

| 항목 | Before(QueryDSL) | After(ES) |
| --- | --- | --- |
| 검색 1회당 MySQL query 수 | TBD | **0 (기대)** |
| MySQL CPU(부하 중) | TBD | TBD |
| slow query 발생 | TBD | TBD |
| ES 검색 시 DB 조회 여부 | - | 0 확인(로그/쿼리 카운터) |

- 측정: `datasource-proxy`/Hibernate statistics 또는 MySQL `general_log`로 검색 API 1회당 쿼리 수 카운트.

---

## 4. 색인 동기화 지연 (Outbox 색인 근거)

매물 승인 → 검색 노출까지의 지연을 단계별로.

| 구간 | 의미 | 평균 | p95 | 비고 |
| --- | --- | --- | --- | --- |
| approvedAt → outbox publishedAt | 적재→Worker 발행 | TBD | TBD | 폴링 주기 영향 |
| approvedAt → ES searchable | 승인→실검색 노출 | TBD | TBD | end-to-end |

| 신뢰성 지표 | 값 |
| --- | --- |
| DEAD 이벤트 수 | TBD (기대 0) |
| 평균 retry 횟수 | TBD |
| 색인 유실 건수 | TBD (기대 0) |

- 측정: OutboxEvent(created_at/published_at) + 승인 시각. 실검색 노출은 승인 후 폴링으로 `/search` 결과에 등장하는 시점.

---

## 5. 정합성 (MySQL ↔ ES)

| 검증 | 방법 | 결과 |
| --- | --- | --- |
| ACTIVE 매물 수 = ES 문서 수 | `COUNT(property WHERE status=ACTIVE)` vs ES `_count` | TBD |
| 비활성/삭제 후 ES에서 제거 | softDelete/reject 후 ES 조회 | TBD |
| 중복 이벤트 처리 후 문서 1건 | 같은 사건 2회 발행 → ES upsert | TBD (1건 유지) |

---

## 6. 이력서/문서 반영 문구 (측정 후 확정)

- ✅ 권장: "nori 형태소 분석 기반 한글 전문검색 도입 — 자연어 질의 Precision@5 **TBD**, 기존 조건검색 대비 관련 매물 상위 노출(0→상위 3위 내 **TBD%**)"
- ✅ 권장: "Outbox 기반 비동기 색인 — MySQL 검색 부하 제거(검색 1회당 DB 쿼리 0), 승인→검색 노출 p95 **TBD**s, 색인 유실 0"
- ⚠️ 지양: "ES로 검색 속도 X배 개선" (소량 데이터에서 부정확·오해 소지, Constitution VII 위배)

---

> 표의 TBD는 5차 Phase 4~5 구현·측정 시 채운다. 시드 데이터/질의 세트는 Phase 4 통합 테스트와 공유.
