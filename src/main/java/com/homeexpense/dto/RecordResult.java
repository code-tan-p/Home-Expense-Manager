package com.homeexpense.dto;

import java.util.List;

import com.homeexpense.model.WorkRecord;

/**
 * The outcome of recording or correcting work. When {@code record} is null nothing was saved,
 * because the warnings need the user's confirmation first (for example, a likely duplicate).
 */
public record RecordResult(WorkRecord record, List<String> warnings) {

    public boolean saved() {
        return record != null;
    }
}
