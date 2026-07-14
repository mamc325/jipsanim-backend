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
// res 200 (Page<PropertySummaryResponse> — 1차 목록 DTO 그대로 재사용)
{
  "content": [
    { "propertyId": 12, "title": "강남역 5분 풀옵션 오피스텔", "regionName": "강남구",
      "dealType": "MONTHLY_RENT", "deposit": 10000000, "monthlyRent": 700000,
      "area": 33.0, "roomCount": 1, "primaryImageUrl": "https://..." }
  ],
  "page": 0, "size": 20, "totalElements": 1
}
```
- 응답 DTO 는 기존 `PropertySummaryResponse`(propertyId·title·regionName·dealType·deposit·monthlyRent·area·roomCount·primaryImageUrl) **그대로** 재사용(목록과 일관, 리뷰 P1). propertyType/status/`_score` 노출이 필요하면 별도 `PropertySearchResponse` 정의(현재 범위 아님).
- **랭킹/정렬(tie-breaker 포함, 리뷰 P2)**: `q` 있으면 `_score desc, createdAt desc, propertyId desc`. `q` 없으면 `createdAt desc, propertyId desc`. (숫자 `propertyId` 로 tie-break — 문자열 정렬 회피, 리뷰 P1)
- **파라미터 검증(리뷰 P2)**: `minDeposit<=maxDeposit`, `minRent<=maxRent`, `minArea<=maxArea`, `page>=0`, `1<=size<=100`. 위반 시 400.
- `q` 는 nori 형태소 분석 → "역세권"(복합어) 은 decompound(mixed)로 매칭.
- 필터는 모두 `filter` 절(스코어 미반영). status=ACTIVE 강제.
- **페이지네이션(리뷰 P2)**: `track_total_hits=true` 로 정확한 totalElements 반환. deep pagination 방지로 ES `from + size = page*size + size = (page+1)*size` 가 **max_result_window(10,000) 초과 시 400**(그 이상은 search_after 로 후속 차수).

---

## 색인(내부, API 아님)
- 매물 **승인/비활성(ACTIVE 진입/이탈)** → `OutboxEvent(PROPERTY_INDEX/UNINDEX)` → Worker → ES. **외부 API 없음**(원칙 IV, 부수효과). 승인 알림(`PROPERTY_APPROVED`)은 4차 그대로 유지.

## 에러 코드
| code | HTTP | 의미 |
| --- | --- | --- |
| VALIDATION_ERROR | 400 | min>max(deposit/rent/area), page<0, size 범위(1~100) 위반, `(page+1)*size > 10000` |
| **SEARCH_UNAVAILABLE** | 503 | ES 장애/타임아웃 (신규 ErrorCode 추가, 리뷰 P2) |

> 5차는 도메인 API 계약을 바꾸지 않는다 — 검색 엔드포인트 추가 + Outbox 색인(비동기).
