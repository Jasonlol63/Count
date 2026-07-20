package com.eazycount.util;

import com.eazycount.common.BusinessException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

public final class TransactionMoneyFormat {

    private TransactionMoneyFormat() {
    }

    public static String formatMoney(BigDecimal value) {
        BigDecimal scaled = (value != null ? value : BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        return scaled.toPlainString();
    }

    public static BigDecimal nz(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    public static BigDecimal add(BigDecimal a, BigDecimal b) {
        return nz(a).add(nz(b));
    }
}
