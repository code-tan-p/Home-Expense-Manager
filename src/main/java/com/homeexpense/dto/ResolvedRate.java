package com.homeexpense.dto;

import com.homeexpense.model.PayRate;

/** The rate that applies, and whether it was task/shift-specific (true) or the standard rate (false). */
public record ResolvedRate(PayRate rate, boolean specific) {
}
