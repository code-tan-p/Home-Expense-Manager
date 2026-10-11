package com.homeexpense.model.converter;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Stores every {@link LocalTime} as TEXT 'HH:MM' (minute precision, 24-hour clock). */
@Converter(autoApply = true)
public class LocalTimeConverter implements AttributeConverter<LocalTime, String> {

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    @Override
    public String convertToDatabaseColumn(LocalTime time) {
        if (time == null) {
            return null;
        }
        if (time.getSecond() != 0 || time.getNano() != 0) {
            throw new IllegalArgumentException("Times are stored to the minute; got " + time);
        }
        return time.format(HH_MM);
    }

    @Override
    public LocalTime convertToEntityAttribute(String text) {
        return text == null ? null : LocalTime.parse(text, HH_MM);
    }
}
