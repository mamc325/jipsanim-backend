package com.jipsanim.pricestandard.batch.dto;

import java.util.List;

/** 배치 수동 실행 요청. 둘 다 optional. */
public record BatchRunRequest(Integer months, List<String> sigunguCodes) {
}
