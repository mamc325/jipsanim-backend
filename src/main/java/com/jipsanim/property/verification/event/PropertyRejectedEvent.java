package com.jipsanim.property.verification.event;

public record PropertyRejectedEvent(Long propertyId, String reason) {
}
