package com.jipsanim.property.dto;

import com.jipsanim.property.domain.Property;
import com.jipsanim.property.domain.PropertyStatus;

public record PropertyMutationResponse(Long propertyId, PropertyStatus status) {

    public static PropertyMutationResponse from(Property property) {
        return new PropertyMutationResponse(property.getId(), property.getStatus());
    }
}
