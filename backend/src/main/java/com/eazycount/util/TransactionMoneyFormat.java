package com.eazycount.util;

import com.eazycount.common.BusinessException;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Transaction amount helpers: store/return true precision; UI rounding is frontend-only.
 * <ul>
 *   <li>Normal amounts (PAYMENT / Bank Process / Domain / …): max {@link #NORMAL_AMOUNT_SCALE} dp</li>
 *   <li>RATE amounts (legs / middleman portions): max {@link #RATE_AMOUNT_SCALE} dp</li>
 *   <li>Exchange / middleman rates: max {@link #RATE_AMOUNT_SCALE} dp</li>
 * </ul>
 */
public final class TransactionMoneyFormat {

    /** Max fractional digits for normal transaction amounts. */
    public static final int NORMAL_AMOUNT_SCALE = 6;
    /** Max fractional digits for RATE amounts and exchange rates. */
    public static final int RATE_AMOUNT_SCALE = 8;

    private TransactionMoneyFormat() {
    }

    /**
     * API / storage serialization: high-precision plain string (no round-to-2).
     */
    public static String formatMoney(BigDecimal value) {
        return toPlain(nz(value));
    }

    public static String toPlain(BigDecimal value) {
        if (value == null) {
            return "0";
        }
        return strip(value).toPlainString();
    }

    public static BigDecimal nz(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    public static BigDecimal add(BigDecimal a, BigDecimal b) {
        return nz(a).add(nz(b));
    }

    /** Strip trailing zeros without changing numeric value. */
    public static BigDecimal strip(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal stripped = value.stripTrailingZeros();
        // Avoid scientific notation for integer values (scale negative).
        if (stripped.scale() < 0) {
            return stripped.setScale(0);
        }
        return stripped;
    }

    /**
     * Fractional digit count after trailing-zero strip (0 for integers).
     */
    public static int decimalPlaces(BigDecimal value) {
        return Math.max(strip(value).scale(), 0);
    }

    /**
     * User / client input: keep exact value; reject when fractional digits exceed {@code maxScale}.
     */
    public static BigDecimal requireMaxScale(BigDecimal raw, int maxScale, String label) {
        if (raw == null) {
            throw new BusinessException(label + " is required");
        }
        BigDecimal amount = strip(raw);
        if (decimalPlaces(amount) > maxScale) {
            throw new BusinessException(label + " cannot have more than " + maxScale + " decimal places");
        }
        return amount;
    }

    public static BigDecimal requireNormalAmount(BigDecimal raw, String label) {
        return requireMaxScale(raw, NORMAL_AMOUNT_SCALE, label);
    }

    public static BigDecimal requireRateAmount(BigDecimal raw, String label) {
        return requireMaxScale(raw, RATE_AMOUNT_SCALE, label);
    }

    /**
     * System-computed amounts (Accounting Due / Domain fee splits): do not round to 2.
     * If math yields more than {@code maxScale} fraction digits, half-up only to that max.
     */
    public static BigDecimal normalizeComputed(BigDecimal value, int maxScale) {
        BigDecimal amount = strip(nz(value));
        if (decimalPlaces(amount) > maxScale) {
            return strip(amount.setScale(maxScale, RoundingMode.HALF_UP));
        }
        return amount;
    }

    public static BigDecimal normalizeComputedNormal(BigDecimal value) {
        return normalizeComputed(value, NORMAL_AMOUNT_SCALE);
    }

    public static BigDecimal normalizeComputedRate(BigDecimal value) {
        return normalizeComputed(value, RATE_AMOUNT_SCALE);
    }
}
