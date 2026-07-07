# API Contract: 실거래가 기반 시세 검증 MVP

- Base URL: `/api`
- 인증: `Authorization: Bearer <JWT>` (인증 필요 엔드포인트)
- 공통 응답 래퍼:
```json
{ "success": true, "data": { }, "error": null }
{ "success": false, "data": null, "error": { "code": "STRING", "message": "..." } }
```
- 페이지네이션: `?page=0&size=20&sort=createdAt,desc`, 응답 `data`에 `content/totalElements/totalPages/number`.
- 권한 표기: [USER] [REALTOR] [ADMIN] [PUBLIC]

---

## 인증 / 사용자

### POST /api/auth/signup  [PUBLIC]
```json
// req
{ "email": "a@b.com", "password": "pw", "nickname": "닉", "role": "REALTOR",
  "businessName": "OO공인", "phone": "010-..." }  // businessName/phone: role=REALTOR일 때
// res 201
{ "userId": 1, "role": "REALTOR" }
```
- 400 `EMAIL_DUPLICATED`, 400 `VALIDATION_ERROR`.

### POST /api/auth/login  [PUBLIC]
```json
// req { "email": "a@b.com", "password": "pw" }
// res 200 { "accessToken": "jwt...", "role": "REALTOR", "expiresIn": 3600 }
```
- 401 `INVALID_CREDENTIALS`.

### GET /api/me  [USER|REALTOR|ADMIN]
```json
// res 200 { "userId": 1, "email": "a@b.com", "nickname": "닉", "role": "REALTOR" }
```

---

## 주소 검색

### GET /api/addresses/search?keyword=서울 강남구 역삼동&page=0&size=10  [REALTOR|ADMIN]
```json
// res 200
{ "content": [
    { "roadAddress": "서울특별시 강남구 테헤란로 …",
      "regionCode": "1168010100", "regionName": "서울특별시 강남구 역삼동",
      "sigunguCode": "11680" } ],
  "totalCount": 3 }
```
- 502 `EXTERNAL_ADDRESS_API_ERROR` (외부 API 실패, 서버 오류로 전파 금지 — 502로 명시). 호출 결과는 `ExternalApiCallLog` 기록.

---

## 매물 (중개사)

### POST /api/properties  [REALTOR]  → DRAFT 생성
```json
// req
{ "title":"역삼 오피스텔","description":"...(20자+)","roadAddress":"...","regionCode":"1168010100",
  "regionName":"...","nearStation":"강남역","propertyType":"OFFICETEL","dealType":"MONTHLY_RENT",
  "deposit":10000000,"monthlyRent":700000,"area":33.0,"roomCount":1,
  "images":[{"imageUrl":"https://...","isPrimary":true}] }
// res 201 { "propertyId": 10, "status": "DRAFT" }
```

### PATCH /api/properties/{propertyId}  [REALTOR, owner]
- 부분 수정. DRAFT/PENDING 에서만 허용. res 200 매물 요약. 403 `NOT_OWNER`.

### DELETE /api/properties/{propertyId}  [REALTOR, owner]  → soft delete (DELETED)

### POST /api/properties/{propertyId}/submit  [REALTOR, owner]  → 자동 검증
```json
// res 200
{ "propertyId":10, "status":"PENDING", "verificationStatus":"REVIEW_REQUIRED",
  "riskLevel":"HIGH",
  "reasons":[ {"reasonType":"PRICE_OUT_OF_RANGE","message":"월세가 기준 하한 미만"},
              {"reasonType":"MISSING_IMAGE","message":"대표 이미지 없음"} ] }
```
- 409 `INVALID_STATE`(DRAFT 아님).

### GET /api/properties/{propertyId}  [PUBLIC(ACTIVE) | owner/ADMIN(전체)]

---

## 매물 검색 (사용자, ACTIVE only)

### GET /api/properties  [PUBLIC]
Query: `region, regionCode, dealType, propertyType, minDeposit, maxDeposit, minRent, maxRent, minArea, maxArea, roomCount, sort, page, size`
```json
// res 200 (page)
{ "content":[ {"propertyId":10,"title":"...","regionName":"...","dealType":"MONTHLY_RENT",
   "deposit":10000000,"monthlyRent":700000,"area":33.0,"roomCount":1,"primaryImageUrl":"..."} ],
  "totalElements":42,"totalPages":3,"number":0 }
```
- ACTIVE 이외 상태는 결과에서 제외(FR-051).

---

## 관리자: 매물 검증

### GET /api/admin/property-verifications?status=REVIEW_REQUIRED&riskLevel=HIGH&page=0  [ADMIN]
```json
// res 200 (page) content[]:
{ "verificationId":5,"propertyId":10,"status":"REVIEW_REQUIRED","riskLevel":"HIGH",
  "reasons":["PRICE_OUT_OF_RANGE","MISSING_IMAGE"],"requestedAt":"2026-07-07T.." }
```

### PATCH /api/admin/property-verifications/{verificationId}/approve  [ADMIN]
```json
// res 200 { "verificationId":5,"propertyId":10,"propertyStatus":"ACTIVE","verificationStatus":"APPROVED" }
```
- 409 `ALREADY_REVIEWED` (멱등: 이미 처리된 건 재처리 금지).

### PATCH /api/admin/property-verifications/{verificationId}/reject  [ADMIN]
```json
// req { "rejectedReason":"허위 가격 의심" }
// res 200 { "verificationStatus":"REJECTED","propertyStatus":"REJECTED" }
```

---

## 관리자: 시세 기준 배치 / 후보 / 기준

### POST /api/admin/price-standards/batch/run  [ADMIN]
```json
// req(optional) { "months": 3, "regionCodes": ["11680","11650"] }  // 미지정 시 전체 대상
// res 202 { "batchJobId": 3, "status": "RUNNING", "jobMonth":"2026-07" }
```

### GET /api/admin/price-standard-batch-jobs?page=0  [ADMIN]
```json
// content[]:
{ "batchJobId":3,"jobMonth":"2026-07","status":"PARTIAL_FAILED",
  "totalRequestCount":25,"successCount":23,"failCount":2,
  "startedAt":"..","finishedAt":"..","triggeredBy":"ADMIN" }
```

### GET /api/admin/price-standard-candidates?status=PENDING&page=0  [ADMIN]
```json
// content[]:
{ "candidateId":7,"regionCode":"1168010100","regionName":"강남구 역삼동",
  "propertyType":"OFFICETEL","dealType":"MONTHLY_RENT","calcMethod":"IQR",
  "calcMinDeposit":5000000,"calcMaxDeposit":50000000,
  "calcMinMonthlyRent":550000,"calcMaxMonthlyRent":1800000,
  "sampleCount":18,"dataStatus":"INSUFFICIENT_DATA","calculatedMonth":"2026-06","status":"PENDING" }
```

### PATCH /api/admin/price-standard-candidates/{candidateId}/approve  [ADMIN]
- 기존 ACTIVE→EXPIRED, 신규 ACTIVE, History 생성 (트랜잭션).
```json
// res 200 { "candidateId":7,"priceStandardId":42,"status":"APPROVED","activated":true }
```
- 409 `ALREADY_REVIEWED`. 422 `INSUFFICIENT_DATA_APPROVAL_BLOCKED`(정책상 소표본 승인 차단 시).

### PATCH /api/admin/price-standard-candidates/{candidateId}/reject  [ADMIN]
```json
// req { "reason":"표본 부족" }  // res 200 { "candidateId":7,"status":"REJECTED" }
```

### GET /api/admin/price-standards?status=ACTIVE&regionCode=11680&page=0  [ADMIN]
```json
// content[]:
{ "id":42,"regionCode":"1168010100","propertyType":"OFFICETEL","dealType":"MONTHLY_RENT",
  "minDeposit":5000000,"maxDeposit":50000000,"minMonthlyRent":550000,"maxMonthlyRent":1800000,
  "sampleCount":120,"source":"MOLIT_OFFICETEL_RENT","status":"ACTIVE",
  "effectiveFrom":"..","effectiveTo":null }
```

### GET /api/admin/external-api-call-logs?apiType=REAL_ESTATE_OFFICETEL_RENT&success=false&page=0  [ADMIN]
```json
// content[]:
{ "id":100,"apiType":"REAL_ESTATE_OFFICETEL_RENT","requestUrl":"...","responseStatus":null,
  "success":false,"errorMessage":"timeout","elapsedTimeMs":5000,"batchJobId":3,"calledAt":".." }
```

---

## 에러 코드 요약
| code | HTTP | 의미 |
| --- | --- | --- |
| VALIDATION_ERROR | 400 | 요청 검증 실패 |
| EMAIL_DUPLICATED | 400 | 이메일 중복 |
| INVALID_CREDENTIALS | 401 | 로그인 실패 |
| FORBIDDEN / NOT_OWNER | 403 | 권한/소유자 아님 |
| NOT_FOUND | 404 | 리소스 없음 |
| INVALID_STATE | 409 | 상태 전이 불가 |
| ALREADY_REVIEWED | 409 | 이미 처리(멱등) |
| INSUFFICIENT_DATA_APPROVAL_BLOCKED | 422 | 소표본 후보 승인 차단 |
| EXTERNAL_ADDRESS_API_ERROR | 502 | 외부 주소 API 실패 |
