# Data Model: Elasticsearch nori 한글 검색 (5차)

MySQL 스키마 변경 없음. 신규는 ES `property` 인덱스 + OutboxEvent event_type 확장(4차 테이블 재사용).

## 1. ES 인덱스: property

### settings (analyzer)
```json
{
  "analysis": {
    "analyzer": {
      "korean_nori": {
        "type": "custom",
        "tokenizer": "nori_mixed",
        "filter": ["nori_part_of_speech", "lowercase"]
      }
    },
    "tokenizer": {
      "nori_mixed": { "type": "nori_tokenizer", "decompound_mode": "mixed" }
    }
  }
}
```

### mappings
| field | type | analyzer/note |
| --- | --- | --- |
| id | keyword | = property.id |
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
| rent | long | 필터(range) |
| area | double | 필터(range) |
| roomCount | integer | 필터(term) |
| realtorId | long | |
| createdAt | date | q 없을 때 정렬 |

- 문서 id = propertyId → 색인 upsert 자연 멱등(중복 이벤트 처리돼도 1건).

## 2. OutboxEvent event_type 확장 (4차 테이블 재사용)
```
event_type 추가: PROPERTY_INDEX, PROPERTY_UNINDEX   (aggregate_type = PROPERTY)
event_key      : PROPERTY_INDEX:{propertyId}:{updatedAtMillis}
                 PROPERTY_UNINDEX:{propertyId}:{updatedAtMillis}
payload        : { "propertyId": <id> }   (핸들러가 최신 매물 재조회)
```
- **updatedAtMillis 포함 이유**: 매 수정이 새 이벤트가 되도록(단일 `:{propertyId}` 로 dedup 하면 후속 수정이 색인 안 됨).
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
