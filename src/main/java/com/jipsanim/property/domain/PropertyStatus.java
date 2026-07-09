package com.jipsanim.property.domain;

public enum PropertyStatus {
    DRAFT,
    PENDING,
    ACTIVE,
    REJECTED,
    CLOSED,
    HIDDEN,
    DELETED;

    public boolean isEditable() {
        return this == DRAFT || this == PENDING;
    }
}
