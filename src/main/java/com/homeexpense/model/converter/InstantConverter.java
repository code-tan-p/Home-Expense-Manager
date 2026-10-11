package com.homeexpense.model.converter;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Stores every {@link Instant} as fixed-width UTC TEXT, for example
 * '2026-10-10T11:15:00.123Z', truncated to milliseconds so values sort correctly.
 */
@Converter(autoApply = true)
public class InstantConverter implements AttributeConverter<Instant, String> {

    private static final DateTimeFormatter UTC_MILLIS =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    @Override
    public String convertToDatabaseColumn(Instant instant) {
        return instant == null ? null : UTC_MILLIS.format(instant.truncatedTo(ChronoUnit.MILLIS));
    }

    @Override
    public Instant convertToEntityAttribute(String text) {
        return text == null ? null : Instant.parse(text);
    }
}
