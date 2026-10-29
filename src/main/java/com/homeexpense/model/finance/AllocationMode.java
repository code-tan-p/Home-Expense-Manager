package com.homeexpense.model.finance;

/** How a payment is allocated when recorded: AUTO (oldest month first), EXPLICIT targets, or KEEP_UNALLOCATED (early payment). */
public enum AllocationMode {
    AUTO,
    EXPLICIT,
    KEEP_UNALLOCATED
}
