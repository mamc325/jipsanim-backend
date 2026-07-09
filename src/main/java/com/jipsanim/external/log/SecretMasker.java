package com.jipsanim.external.log;

import java.util.regex.Pattern;

/**
 * 외부 호출 URL/파라미터에서 인증키(serviceKey/confmKey/apiKey) 값을 마스킹한다.
 * ExternalApiCallLog 저장 전 반드시 통과시킨다. (Constitution I, spec Clarifications)
 */
public final class SecretMasker {

    private static final Pattern KEY_PATTERN =
            Pattern.compile("(?i)(confmKey|serviceKey|api[_-]?key)=([^&]*)");

    private SecretMasker() {
    }

    public static String mask(String value) {
        if (value == null) {
            return null;
        }
        return KEY_PATTERN.matcher(value).replaceAll("$1=***");
    }
}
