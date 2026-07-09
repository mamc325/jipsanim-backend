package com.jipsanim.pricestandard.batch;

import com.jipsanim.common.response.ApiResponse;
import com.jipsanim.pricestandard.batch.dto.BatchJobResponse;
import com.jipsanim.pricestandard.batch.dto.BatchRunRequest;
import com.jipsanim.pricestandard.repository.PriceStandardBatchJobRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/price-standard-batch-jobs")
public class PriceStandardBatchController {

    private final PriceStandardBatchService batchService;
    private final PriceStandardBatchJobRepository batchJobRepository;

    public PriceStandardBatchController(PriceStandardBatchService batchService,
                                        PriceStandardBatchJobRepository batchJobRepository) {
        this.batchService = batchService;
        this.batchJobRepository = batchJobRepository;
    }

    /** 배치 잡 생성(=실행). 후보 생성은 Phase 4 에서 연결. */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<BatchJobResponse> run(@RequestBody(required = false) BatchRunRequest request) {
        BatchRunRequest req = request != null ? request : new BatchRunRequest(null, null);
        BatchCollectResult result = batchService.run(req.months(), req.sigunguCodes(), "ADMIN");
        return ApiResponse.success(
                BatchJobResponse.from(batchJobRepository.findById(result.batchJobId()).orElseThrow()));
    }

    @GetMapping
    public ApiResponse<Page<BatchJobResponse>> list(@PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.success(batchJobRepository.findAll(pageable).map(BatchJobResponse::from));
    }
}
