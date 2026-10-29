package com.homeexpense.dto;

import java.util.List;

import com.homeexpense.model.earnings.EarningLine;
import com.homeexpense.model.earnings.EarningRecordTreatment;
import com.homeexpense.model.earnings.EarningRun;

/** A saved earnings calculation with everything needed to explain it. */
public record EarningsResult(EarningRun run, List<EarningLine> lines, List<EarningRecordTreatment> treatments,
                             List<String> warnings, List<String> blockers, List<UnrecordedActivity> unrecorded) {

    public long totalPaise() {
        return run.getTotalPaise();
    }

    public boolean canFinalize() {
        return blockers.isEmpty();
    }
}
