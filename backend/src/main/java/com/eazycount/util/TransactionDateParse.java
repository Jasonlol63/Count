package com.eazycount.util;

import com.eazycount.common.BusinessException;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

public final class TransactionDateParse {

    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("d/M/uuuu", Locale.ROOT);
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;

    private TransactionDateParse() {
    }

    public static LocalDate parseRequired(String raw, String label) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(label + " is required");
        }
        String trimmed = raw.trim();
        try {
            if (trimmed.contains("/")) {
                return LocalDate.parse(trimmed, DMY);
            }
            return LocalDate.parse(trimmed, ISO);
        } catch (DateTimeParseException ex) {
            throw new BusinessException("Invalid " + label + ": " + raw);
        }
    }
}
