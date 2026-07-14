# Data Model: Elasticsearch nori 한글 검색 (5차)

MySQL 스키마 변경 없음. 신규는 ES `property` 인덱스 + OutboxEvent event_type 확장(4차 테이블 재사용).

## 1. ES 인덱스: property

### settings (analyzer) — POS 는 named filter 로 정의(리뷰 P2)
```json
{
  "analysis": {
    "tokenizer": {
      "nori_mixed": { "type": "nori_tokenizer", "decompound_mode": "mixed" }
    },
    "filter": {
      "korean_pos_filter": {
        "type": "nori_part_of_speech",
        "stoptags": ["E", "IC", "J", "MAG", "MAJ", "MM", "SP", "SSC", "SSO", "SC", "SE", "XPN", "XSA", "XSN", "XSV", "UNA", "NA", "VSV"]
      }
    },
    "analyzer": {
      "korean_nori": {
        "type": "custom",
        "tokenizer": "nori_mixed",
        "filter": ["korean_pos_filter", "lowercase"]
      }
    }
  }
}
```

### mappings
| field | type | analyzer/note |
| --- | --- | --- |
| id (`_id`) | keyword | 문서 id = propertyId 문자열(upsert 멱등) |
| propertyId | long | **정렬/tie-breaker용 숫자 필드**(keyword id 문자열 정렬 회피, 리뷰 P1) |
| title | text | korean_nori (검색 부스팅 ^3) |
| description | text | korean_nori (^1) |
| roadAddress | text | korean_nori |
| regionName | text | korean_nori (^2) |
| nearStation | text | korean_nori (^2) |
| sigunguCode | keyword | 필터(term) |
| dealType | keyword | 필터 |
| propertyType | keyword | 필터 |
| status | keyword | 필터(항상 ACTIVE) |
| deposit | long | 필터(range) |
| monthlyRent | long | 필터(range) (도메인 필드명 일치) |
| area | double | 필터(range) |
| roomCount | integer | 필터(term) |
| realtorId | long | |
| primaryImageUrl | keyword | 목록 응답용(승인 시점 이미지 고정 — ACTIVE 수정 재색인 범위 밖이라 정책상 정합, 리뷰 P1) |
| createdAt | date | q 없을 때 정렬 |

- 문서 id = propertyId → 색인 upsert 자연 멱등(중복 이벤트 처리돼도 1건).
- `PropertySummaryResponse` 재사용을 위해 `primaryImageUrl` 을 문서에 포함(핸들러가 승인 매물의 대표 이미지로 채움).

## 2. OutboxEvent event_type 확장 (4차 테이블 재사용)
```
event_type 추가: PROPERTY_INDEX, PROPERTY_UNINDEX   (aggregate_type = PROPERTY)
event_key      : PROPERTY_INDEX:{propertyId}:{gen}
                 PROPERTY_UNINDEX:{propertyId}:{gen}
payload        : { "propertyId": <id> }   (핸들러가 최신 매물 재조회)
```
- **generation `{gen}` = 전이당 고유값(UUID, append 시점 생성, 리뷰 P1)**. 상태값만 키로 쓰면 `event_key` UNIQUE 때문에 **반복 전이**(ACTIVE→HIDDEN→ACTIVE→HIDDEN)의 두 번째 `PROPERTY_UNINDEX`가 no-op 되어 유실된다 → 전이마다 새 이벤트가 되도록 UUID 부착.
  - `updatedAtMillis`(Auditing flush 시점) 대신 UUID: 같은 트랜잭션에서 즉시 안정적으로 가용, 초 단위 충돌 없음.
  - trade-off: 색인 이벤트는 producer dedup 대상이 아님(각 append 유일) — 그러나 **ES upsert/delete(id=propertyId) 가 멱등**이라 최종 상태는 정확히 1건.
- **판정은 ACTIVE 진입/이탈 전이 기준**: `prev != ACTIVE && new == ACTIVE`→INDEX, `prev == ACTIVE && new != ACTIVE`→UNINDEX(HIDDEN 포함). enum 목록 나열 안 함.
- 색인 이벤트는 알림과 달리 `notification` 을 만들지 않고 ES 문서를 만든다 → Worker 핸들러 라우팅으로 분기(plan D1).

## 정합성 포인트
- 적재: 매물 상태 변경과 동일 트랜잭션(원칙 IV, 유실 0).
- 소비 멱등: ES upsert(id=propertyId) — 중복/재시도 안전. 삭제도 멱등.
- 검색 노출: status=ACTIVE 필터로 비활성 매물 제외(색인 삭제와 이중 방어).

## 관계
```
property(MySQL) 1—0..1 property(ES document)   ← Outbox Worker 가 동기화
outbox_event(PROPERTY_INDEX/UNINDEX) → PropertyIndexOutboxHandler → ES
```
