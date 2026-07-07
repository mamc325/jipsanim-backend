package com.jipsanim.common.response;

import com.jipsanim.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiResponseTest {

    @Test
    @DisplayName("success 는 data 를 담고 error 는 null 이다")
    void success() {
        ApiResponse<String> res = ApiResponse.success("ok");

        assertThat(res.success()).isTrue();
        assertThat(res.data()).isEqualTo("ok");
        assertThat(res.error()).isNull();
    }

    @Test
    @DisplayName("error 는 ErrorCode 이름과 메시지를 담고 data 는 null 이다")
    void error() {
        ApiResponse<Void> res = ApiResponse.error(ErrorCode.NOT_FOUND);

        assertThat(res.success()).isFalse();
        assertThat(res.data()).isNull();
        assertThat(res.error().code()).isEqualTo("NOT_FOUND");
        assertThat(res.error().message()).isEqualTo(ErrorCode.NOT_FOUND.defaultMessage());
    }
}
