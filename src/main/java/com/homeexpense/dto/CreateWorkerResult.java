package com.homeexpense.dto;

import java.util.List;

import com.homeexpense.model.EmploymentPeriod;
import com.homeexpense.model.Worker;

/** The created worker plus any warnings to show the user (for example, a possible duplicate). */
public record CreateWorkerResult(Worker worker, EmploymentPeriod employmentPeriod, List<String> warnings) {
}
