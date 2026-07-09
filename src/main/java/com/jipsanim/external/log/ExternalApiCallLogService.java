package com.jipsanim.external.log;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 외부 호출 이력 기록. 호출부의 트랜잭션과 무관하게 남도록 REQUIRES_NEW 로 분리한다
 * (핵심 요청 실패/롤백과 상관없이 호출 사실은 남긴다).
 */
@Service
public class ExternalApiCallLogService {

    private final ExternalApiCallLogRepository repository;

    public ExternalApiCallLogService(ExternalApiCallLogRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveSuccess(ApiType apiType, String url, String params, Integer status, int elapsedMs) {
        repository.save(ExternalApiCallLog.success(apiType, SecretMasker.mask(url), SecretMasker.mask(params),
                status, elapsedMs));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveFailure(ApiType apiType, String url, String params, String errorMessage, int elapsedMs) {
        repository.save(ExternalApiCallLog.failure(apiType, SecretMasker.mask(url), SecretMasker.mask(params),
                errorMessage, elapsedMs));
    }
}
