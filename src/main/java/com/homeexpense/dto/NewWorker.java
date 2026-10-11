package com.homeexpense.dto;

import java.time.LocalDate;

/** Details for adding a worker together with their first employment period. */
public record NewWorker(String name, Long workerTypeId, String phone, String notes, LocalDate employmentStart) {
}
