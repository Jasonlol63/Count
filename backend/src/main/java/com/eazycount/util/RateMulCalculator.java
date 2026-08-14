package com.eazycount.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.regex.Pattern;

/** RATE Middle-Man「Rate-Mul」解析与佣金计算，对齐前端 transactionSubmitHelpers.js 同名三个函数。 */
public final class RateMulCalculator {

    private static final Pattern SIMPLE_DIVISION = Pattern.compile("^/\\d*\\.?\\d+$");
    private static final Pattern PLAIN_POSITIVE = Pattern.compile("^\\+?\\d*\\.?\\d+$");
    private static final int WORK_SCALE = 20;

    public enum Mode { DIVIDE, MULTIPLY }

    public record ParsedRate(boolean valid, Mode mode, BigDecimal divisor, BigDecimal value) {
        static ParsedRate invalid() {
            return new ParsedRate(false, null, null, null);
        }
    }

    private RateMulCalculator() {
    }

    /* "/1.71" 形式取除数；空白/乘法/复合表达式返回 null。 */
    public static BigDecimal parseSimpleDivisionDivisor(String raw) {
        String normalized = normalize(raw);
        if (normalized == null || !SIMPLE_DIVISION.matcher(normalized).matches()) {
            return null;
        }
        try {
            BigDecimal divisor = new BigDecimal(normalized.substring(1));
            return divisor.compareTo(BigDecimal.ZERO) > 0 ? divisor : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /*
     * Rate-Mul 原始输入，两种互斥形态："/1.55"（除法写法）→ DIVIDE 模式，除数直接用；纯正数（如 0.05）→ MULTIPLY 模式。负数及其他表达式一律无效。
     */
    public static ParsedRate parseMiddlemanRateInput(String raw) {
        String cleaned = normalize(raw);
        if (cleaned == null) {
            return ParsedRate.invalid();
        }

        BigDecimal divisor = parseSimpleDivisionDivisor(cleaned);
        if (divisor != null) {
            return new ParsedRate(true, Mode.DIVIDE, divisor, null);
        }

        if (cleaned.indexOf('*') >= 0 || cleaned.indexOf('/') >= 0) {
            return ParsedRate.invalid();
        }
        if (!PLAIN_POSITIVE.matcher(cleaned).matches()
                || cleaned.equals(".") || cleaned.equals("+") || cleaned.equals("+.")) {
            return ParsedRate.invalid();
        }
        try {
            BigDecimal value = new BigDecimal(cleaned);
            if (value.compareTo(BigDecimal.ZERO) <= 0) {
                return ParsedRate.invalid();
            }
            return new ParsedRate(true, Mode.MULTIPLY, null, value);
        } catch (NumberFormatException e) {
            return ParsedRate.invalid();
        }
    }

    /* 佣金，第二币种，全精度（调用方自行截断入库）。可能为负（倒贴），是否入账由调用方决定。
     * DIVIDE：FX 也是 "/divisor" 时才生效，"from/newDivisor − from/divisor"；
     * MULTIPLY 且 FX 是 "/divisor"：点数，"mul × 1000"；
     * MULTIPLY 且 FX 是乘法写法：新汇率做差，{(原汇率 − mul) × fromAmount}；
     * 模式与 FX 写法不匹配，或乘法分支下 FX 汇率解析失败：返回 0（忽略）。
     */
    public static BigDecimal computeCommission(
            BigDecimal fromAmount, String middlemanRateRaw, String fxRateExpression, BigDecimal fxRateValue) {
        if (fromAmount == null || fromAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        ParsedRate parsed = parseMiddlemanRateInput(middlemanRateRaw);
        if (!parsed.valid()) {
            return BigDecimal.ZERO;
        }

        BigDecimal baseDivisor = parseSimpleDivisionDivisor(fxRateExpression);

        if (parsed.mode() == Mode.DIVIDE) {
            if (baseDivisor == null) {
                return BigDecimal.ZERO;
            }
            BigDecimal base = fromAmount.divide(baseDivisor, WORK_SCALE, RoundingMode.HALF_UP);
            BigDecimal adjusted = fromAmount.divide(parsed.divisor(), WORK_SCALE, RoundingMode.HALF_UP);
            return adjusted.subtract(base);
        }

        // MULTIPLY 模式。
        if (baseDivisor != null) {
            return parsed.value().multiply(BigDecimal.valueOf(1000));
        }
        if (fxRateValue == null || fxRateValue.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal rateDiff = fxRateValue.subtract(parsed.value());
        return fromAmount.multiply(rateDiff);
    }

    private static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.replace('÷', '/').replaceAll("\\s+", "").trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
