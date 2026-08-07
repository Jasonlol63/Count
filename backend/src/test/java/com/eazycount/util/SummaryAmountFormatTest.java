package com.eazycount.util;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Data Capture Summary amount algorithm — truncate (ROUND_DOWN) rule, option B. */
class SummaryAmountFormatTest {

    private static void assertPlain(String expected, BigDecimal actual) {
        assertEquals(expected, actual.toPlainString());
    }

    // ---- TransactionMoneyFormat truncation primitives ----

    @Test
    void truncateNormalAmountDropsBeyondSixTowardZero() {
        assertPlain("0.123456", TransactionMoneyFormat.truncateNormalAmount(new BigDecimal("0.12345678")));
        assertPlain("0.999999", TransactionMoneyFormat.truncateNormalAmount(new BigDecimal("0.9999999")));
        // Toward zero for negatives (not floor).
        assertPlain("-0.123456", TransactionMoneyFormat.truncateNormalAmount(new BigDecimal("-0.12345678")));
    }

    @Test
    void truncateKeepsValuesWithinScaleUntouched() {
        assertPlain("10.123456", TransactionMoneyFormat.truncateNormalAmount(new BigDecimal("10.123456")));
        assertPlain("1.5", TransactionMoneyFormat.truncateNormalAmount(new BigDecimal("1.500000")));
        assertPlain("0", TransactionMoneyFormat.truncateNormalAmount(null));
    }

    @Test
    void truncateRateAmountDropsBeyondEight() {
        assertPlain("0.12345678", TransactionMoneyFormat.truncateRateAmount(new BigDecimal("0.123456789")));
        assertPlain("-0.12345678", TransactionMoneyFormat.truncateRateAmount(new BigDecimal("-0.123456789")));
    }

    // ---- Rate expression application (8-dp truncate) ----

    @Test
    void applyRateMultiplyForms() {
        assertPlain("30", SummaryAmountFormat.applyRateExpression(new BigDecimal("10"), "*3"));
        assertPlain("30", SummaryAmountFormat.applyRateExpression(new BigDecimal("10"), "3"));
        assertPlain("15.5", SummaryAmountFormat.applyRateExpression(new BigDecimal("10"), "*1.55"));
    }

    @Test
    void applyRateDivideTruncatesToEightDown() {
        // 1/3 = 0.333333333… → 0.33333333 (DOWN)
        assertPlain("0.33333333", SummaryAmountFormat.applyRateExpression(BigDecimal.ONE, "/3"));
        // 2/3 = 0.666666666… → 0.66666666 (DOWN, not HALF_UP 0.66666667)
        assertPlain("0.66666666", SummaryAmountFormat.applyRateExpression(new BigDecimal("2"), "/3"));
        assertPlain("-0.66666666", SummaryAmountFormat.applyRateExpression(new BigDecimal("-2"), "/3"));
    }

    @Test
    void applyRateMultiplyResultTruncatesToEight() {
        // 0.12345678 × 1.1 = 0.135802458 → 0.13580245 (DOWN)
        assertPlain("0.13580245",
                SummaryAmountFormat.applyRateExpression(new BigDecimal("0.12345678"), "*1.1"));
    }

    @Test
    void blankZeroOrInvalidRateLeavesBaseUnchanged() {
        BigDecimal base = new BigDecimal("12.345678");
        assertPlain("12.345678", SummaryAmountFormat.applyRateExpression(base, null));
        assertPlain("12.345678", SummaryAmountFormat.applyRateExpression(base, ""));
        assertPlain("12.345678", SummaryAmountFormat.applyRateExpression(base, "*0"));
        assertPlain("12.345678", SummaryAmountFormat.applyRateExpression(base, "/0"));
        assertPlain("12.345678", SummaryAmountFormat.applyRateExpression(base, "abc"));
    }

    @Test
    void parseRateOperandForms() {
        assertPlain("3", SummaryAmountFormat.parseRateOperand("*3"));
        assertPlain("3", SummaryAmountFormat.parseRateOperand("/3"));
        assertPlain("1.55", SummaryAmountFormat.parseRateOperand("1.55"));
        assertNull(SummaryAmountFormat.parseRateOperand("*0"));
        assertNull(SummaryAmountFormat.parseRateOperand(""));
        assertNull(SummaryAmountFormat.parseRateOperand("abc"));
    }

    // ---- Full pipeline: base → rate 8-dp → final 6-dp ----

    @Test
    void computeProcessedAmountRatePathEightThenSix() {
        // 0.1 / 3 = 0.03333333 (8-dp DOWN) → 0.033333 (6-dp DOWN)
        assertPlain("0.033333", SummaryAmountFormat.computeProcessedAmount(new BigDecimal("0.1"), "/3"));
        // 0.12345678 × 1.1 = 0.13580245 (8) → 0.135802 (6)
        assertPlain("0.135802",
                SummaryAmountFormat.computeProcessedAmount(new BigDecimal("0.12345678"), "*1.1"));
    }

    @Test
    void computeProcessedAmountWithoutRateTruncatesBaseToSix() {
        assertPlain("1.234567", SummaryAmountFormat.computeProcessedAmount(new BigDecimal("1.23456789"), null));
        assertPlain("10.123456", SummaryAmountFormat.computeProcessedAmount(new BigDecimal("10.123456"), ""));
    }

    // ---- Total + submit tolerance (HALF_UP 2, ±0.05) ----

    @Test
    void sumProcessedAmountsIsHighPrecision() {
        BigDecimal total = SummaryAmountFormat.sumProcessedAmounts(List.of(
                new BigDecimal("0.033333"),
                new BigDecimal("-0.02"),
                new BigDecimal("0.000001")));
        assertPlain("0.013334", total);
    }

    @Test
    void submitToleranceHalfUpTwoWithinPlusMinusFiveCents() {
        assertTrue(SummaryAmountFormat.isTotalWithinSubmitTolerance(new BigDecimal("0")));
        assertTrue(SummaryAmountFormat.isTotalWithinSubmitTolerance(new BigDecimal("0.05")));
        assertTrue(SummaryAmountFormat.isTotalWithinSubmitTolerance(new BigDecimal("-0.05")));
        // 0.054 → HALF_UP 2 → 0.05 → pass
        assertTrue(SummaryAmountFormat.isTotalWithinSubmitTolerance(new BigDecimal("0.054")));
        assertTrue(SummaryAmountFormat.isTotalWithinSubmitTolerance(new BigDecimal("-0.054")));
        // 0.055 → HALF_UP 2 → 0.06 → fail
        assertFalse(SummaryAmountFormat.isTotalWithinSubmitTolerance(new BigDecimal("0.055")));
        assertFalse(SummaryAmountFormat.isTotalWithinSubmitTolerance(new BigDecimal("-0.055")));
        assertFalse(SummaryAmountFormat.isTotalWithinSubmitTolerance(new BigDecimal("1")));
    }

    @Test
    void roundTotalForValidationIsDisplayOnlyHalfUp() {
        assertPlain("0.05", SummaryAmountFormat.roundTotalForValidation(new BigDecimal("0.054")));
        assertPlain("0.06", SummaryAmountFormat.roundTotalForValidation(new BigDecimal("0.055")));
        assertPlain("-0.06", SummaryAmountFormat.roundTotalForValidation(new BigDecimal("-0.055")));
    }
}
