package com.clinzo.domain;

/**
 * Status of a materialized slot.
 */
public enum SlotStatus {
    AVAILABLE,
    HELD,
    BOOKED,
    CANCELLED,
    EXPIRED_HOLD
}
