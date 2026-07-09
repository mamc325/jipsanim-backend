# MVP 인수기준 체크 · 키 발급 · 시드

## 1. 인수기준 (spec §7) — 자동 테스트 매핑

| 인수기준 | 검증 테스트 | 상태 |
| --- | --- | --- |
| 주소 검색 → 표준주소/지역코드(bjdong·sigungu) 반환 | `AddressClientTest` | ✅ |
| 매물 DRAFT 등록·수정·삭제, 소유자/권한 제어 | `PropertyIntegrationTest` | ✅ |
| submit 시 riskLevel/사유 저장, 상태 PENDING | `PropertyVerificationIntegrationTest`, `FullScenarioIntegrationTest` | ✅ |
| 실거래가 XML 파싱·만원→원·전월세 분류 | `RealEstateClientTest` | ✅ |
| 배치 일부 실패 시 PARTIAL_FAILED, CallLog 기록 | `PriceStandardBatchServiceTest` | ✅ |
| 시세 범위 p10~p90/IQR, 이상치 배제 | `RangeCalculatorTest` | ✅ |
| 후보 승인 시 기존 ACTIVE→EXPIRED, 신규 ACTIVE 1건(active_key), History | `PriceStandardApprovalTest` | ✅ |
| 소표본 승인 허용 + dataStatus 상속, 재승인 멱등 | `PriceStandardApprovalTest` | ✅ |
| 기준 없는 지역 매물은 가격으로 HIGH 판정 안 함(REVIEW_REQUIRED) | `PriceOutOfRangeRuleTest`, `RiskScorerTest` | ✅ |
| ACTIVE 기준 이탈 매물 → PRICE_OUT_OF_RANGE(HIGH) | `FullScenarioIntegrationTest` | ✅ |
| 관리자 승인 시에만 ACTIVE, 검색은 ACTIVE만 | `FullScenarioIntegrationTest`, `PropertySearchIntegrationTest` | ✅ |
| 인증/권한(USER/REALTOR/ADMIN), 401/403 | `AuthIntegrationTest` | ✅ |

## 2. 외부 API 키 발급 (무료, 개발계정 자동 승인)

### 행정안전부 도로명주소 검색 API
1. https://business.juso.go.kr 회원가입/로그인
2. API 신청 → 도로명주소 → **검색 API**
3. 신청기관 유형: 민간기관 / 서비스망: 인터넷 망 / **서비스 용도: 개발(본인인증없이 발급)**
4. 승인키(`devU01TX...`) → `application-local.yml` 의 `external.address.api-key`

### 국토교통부 오피스텔 전월세 실거래가 API
1. https://www.data.go.kr 회원가입/로그인
2. "오피스텔 전월세 실거래가" 검색 → **활용신청**(자동승인)
3. 마이페이지 → 오픈API → 개발계정 → 일반 인증키(serviceKey)
4. → `application-local.yml` 의 `external.molit.api-key`
5. ⚠️ 호출 시 **User-Agent 헤더 필수** (없으면 WAF가 400 Request Blocked). `RealEstateClient` 에 설정됨.

## 3. 수집 대상 시군구 시드 (LAWD_CD 앞 5자리)
배치 미지정 시 기본 대상 (`PriceStandardBatchService.DEFAULT_SIGUNGU`):
| 코드 | 지역 |
| --- | --- |
| 11680 | 서울 강남구 |
| 11650 | 서울 서초구 |
| 11440 | 서울 마포구 |
| 11710 | 서울 송파구 |

관리자 수동 실행 시 `sigunguCodes` 로 대상 지정 가능:
```
POST /api/admin/price-standard-batch-jobs
{ "months": 3, "sigunguCodes": ["11680","11650"] }
```

## 4. 남은 [NEEDS CLARIFICATION] (후속)
- 국토부 API 페이지네이션 상한(MAX_PAGES=20) — 대규모 지역 검증 필요
- 매물 유형 확장(APARTMENT 등) 시 실거래가 API 추가 (ROADMAP 확장 후보)
