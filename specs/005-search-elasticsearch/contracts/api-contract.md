# API Contract: Elasticsearch nori 한글 검색 (5차)

- Base `/api`, 공통 응답 래퍼 1차와 동일.
- 5차는 **검색 엔드포인트 1개 추가**. 기존 `GET /api/properties`(QueryDSL 조건검색)·색인은 도메인 API 스펙 변경 없음(Outbox 부수효과).

---

## 매물 전문검색 (사용자)

### GET /api/properties/search  [PUBLIC]
전문검색(nori) + 필터 + 관련도 정렬. ACTIVE 매물만.

**query params (모두 optional)**
| param | 예 | 의미 |
| --- | --- | --- |
| q | `강남역 오피스텔` | 전문검색어(title/regionName/nearStation/description, nori) |
| sigunguCode | `11680` | 시군구 필터(term) |
| dealType | `MONTHLY_RENT` | 거래유형 |
| propertyType | `OFFICETEL` | 매물유형 |
| minDeposit / maxDeposit | `0` / `20000000` | 보증금 range |
| minRent / maxRent | `0` / `1000000` | 월세 range |
| minArea / maxArea | `20` / `60` | 면적 range |
| roomCount | `1` | 방 개수 |
| page / size | `0` / `20` | 페이지 |

```json
// res 200 (Page<PropertySummaryResponse> — 1차 요약 DTO 재사용)
{
  "content": [
    { "propertyId": 12, "title": "강남역 5분 풀옵션 오피스텔", "regionName": "강남구",
      "dealType": "MONTHLY_RENT", "propertyType": "OFFICETEL",
      "deposit": 10000000, "rent": 700000, "area": 33.0, "roomCount": 1, "status": "ACTIVE" }
  ],
  "page": 0, "size": 20, "totalElements": 1
}
```
- **랭킹**: `q` 있으면 `multi_match`(title^3, nearStation^2, regionName^2, description) 관련도(`_score`) 정렬. 없으면 `createdAt` desc.
- `q` 는 nori 형태소 분석 → "역세권"(복합어) 은 decompound(mixed)로 매칭.
- 필터는 모두 `filter` 절(스코어 미반영). status=ACTIVE 강제.

---

## 색인(내부, API 아님)
- 매물 승인/수정/비활성 → `OutboxEvent(PROPERTY_INDEX/UNINDEX)` → Worker → ES. **외부 API 없음**(원칙 IV, 부수효과).

## 에러 코드
| code | HTTP | 의미 |
| --- | --- | --- |
| VALIDATION_ERROR | 400 | 잘못된 range/enum 파라미터 |
| (ES 장애) | 503/500 | 검색 인프라 불가 시 |

> 5차는 도메인 API 계약을 바꾸지 않는다 — 검색 엔드포인트 추가 + Outbox 색인(비동기).
