package com.homeexpense.model.converter;

import java.time.LocalDate;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Stores every {@link LocalDate} as ISO-8601 TEXT ('YYYY-MM-DD').
 *
 * <p>SQLite has no date type. Fixed-width ISO text sorts chronologically, so date-range
 * comparisons in SQL (including overlap triggers) work as plain string comparisons.
 */
@Converter(autoApply = true)
public class LocalDateConverter implements AttributeConverter<LocalDate, String> {

    @Override
    public String convertToDatabaseColumn(LocalDate date) {
        return date == null ? null : date.toString();
    }

    @Override
    public LocalDate convertToEntityAttribute(String text) {
        return text == null ? null : LocalDate.parse(text);
    }
}
