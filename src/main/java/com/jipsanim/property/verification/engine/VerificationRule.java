package com.jipsanim.property.verification.engine;

import java.util.Optional;

/** 매물 자동 검증 규칙. 위반 시 사유(finding)를 반환한다. 새 규칙은 @Component 로 추가만 하면 된다. */
public interface VerificationRule {

    Optional<VerificationFinding> evaluate(VerificationContext context);
}
