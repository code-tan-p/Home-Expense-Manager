package com.homeexpense.dto;

import java.util.List;

import com.homeexpense.model.WorkRecord;

/** Records created by marking a whole day, plus plain-language notes about what was skipped. */
public record WholeDayResult(List<WorkRecord> records, List<String> messages) {
}
