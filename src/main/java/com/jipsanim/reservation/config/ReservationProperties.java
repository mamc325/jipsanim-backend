package com.jipsanim.reservation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "reservation")
public record ReservationProperties(int tokenTtlSeconds, long sweepIntervalMs, long feeAmount) {

    public long tokenTtlMillis() {
        return tokenTtlSeconds * 1000L;
    }
}
