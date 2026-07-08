package com.jipsanim.user.domain;

public enum Role {
    USER,
    REALTOR,
    ADMIN;

    /** Spring Security 권한 문자열 (ROLE_ 접두) */
    public String authority() {
        return "ROLE_" + name();
    }
}
