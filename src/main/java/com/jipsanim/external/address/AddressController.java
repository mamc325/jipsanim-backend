package com.jipsanim.external.address;

import com.jipsanim.common.response.ApiResponse;
import com.jipsanim.external.address.dto.AddressSearchResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AddressController {

    private final AddressClient addressClient;

    public AddressController(AddressClient addressClient) {
        this.addressClient = addressClient;
    }

    @GetMapping("/api/addresses")
    @PreAuthorize("hasAnyRole('REALTOR','ADMIN')")
    public ApiResponse<AddressSearchResponse> search(
            @RequestParam String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        // juso currentPage 는 1-based → page(0-based)+1
        return ApiResponse.success(addressClient.search(keyword, page + 1, size));
    }
}
