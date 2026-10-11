package com.homeexpense.model.earnings;

/** A draft is recalculated freely; a finalized run is permanent and locks its dates. */
public enum EarningRunStatus {
    DRAFT,
    FINALIZED
}
