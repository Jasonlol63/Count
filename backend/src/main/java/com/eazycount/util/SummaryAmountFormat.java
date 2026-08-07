package com.eazycount.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;

/**
 * Data Capture Summary processed-amount 算法（截断规则，与 Count-frontend
 * {@code summaryRowAmount.js} 对齐）：
 * <ol>
 *   <li>基础 amount：公式结果（6 位精度域）。</li>
 *   <li>走 rate（行有 rate 表达式）：{@code base × / rate}，截断（ROUND_DOWN）到
 *       {@link TransactionMoneyFormat#RATE_AMOUNT_SCALE}（8 位）。</li>
 *   <li>最终 processed amount：截断到
 *       {@link TransactionMoneyFormat#NORMAL_AMOUNT_SCALE}（6 位）——即 8→6 步骤——
 *       再写入 {@code data_capture_line.processed_amount}。</li>
 *   <li>Submit 门槛：最终金额合计 HALF_UP 到 2 位后，须在 ±{@link #SUBMIT_TOTAL_TOLERANCE} 内。
 *       仅用于展示的 2 位值永不进入存储或计算。</li>
 * </ol>
 */
public final class SummaryAmountFormat {

    /* Submit 合计门槛：|HALF_UP(total, 2)| 必须 ≤ 0.05。 */
    public static final BigDecimal SUBMIT_TOTAL_TOLERANCE = new BigDecimal("0.05");

    /* UI / 校验取整位数（仅展示；永不入库）。 */
    public static final int VALIDATION_SCALE = 2;

    private SummaryAmountFormat() {
    }

    //对基础金额应用 Summary rate 表达式，结果截断到 8 位小数。E.g. {code "*N"}, {code "/N"}, {code "N"}
    public static BigDecimal applyRateExpression(BigDecimal baseAmount, String rateExpression) {
        BigDecimal base = TransactionMoneyFormat.strip(TransactionMoneyFormat.nz(baseAmount));
        String expression = rateExpression == null ? "" : rateExpression.trim();
        if (expression.isEmpty()) {
            return base;
        }

        boolean divide = expression.startsWith("/");
        String operandText = (expression.startsWith("*") || divide)
                ? expression.substring(1).trim()
                : expression;

        BigDecimal operand = parseRateOperand(operandText);
        if (operand == null || operand.signum() == 0) {
            return base;
        }

        BigDecimal result = divide
                ? base.divide(operand, TransactionMoneyFormat.RATE_AMOUNT_SCALE, RoundingMode.DOWN)
                : base.multiply(operand);
        return TransactionMoneyFormat.truncateRateAmount(result);
    }

    /* rate 表达式的数值操作数 "*3" || "/3" || "3" → 3, 使data_capture_line.rate 使用 空 / 非法 / 0 时返回 null */
    public static BigDecimal parseRateOperand(String rateExpression) {
        String text = rateExpression == null ? "" : rateExpression.trim();
        if (text.startsWith("*") || text.startsWith("/")) {
            text = text.substring(1).trim();
        }
        if (text.isEmpty()) {
            return null;
        }
        try {
            BigDecimal operand = new BigDecimal(text.replace(",", ""));
            return operand.signum() == 0 ? null : TransactionMoneyFormat.strip(operand);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /* 最终 8→6 步骤：入库前截断（ROUND_DOWN）到 6 位。 */
    public static BigDecimal finalizeProcessedAmount(BigDecimal amount) {
        return TransactionMoneyFormat.truncateNormalAmount(amount);
    }

    // 单行完整管线：base → rate（8 位截断）→ 最终 6 位截断。无 rate 表达式的行仅将 base 截断到 6 位。
    public static BigDecimal computeProcessedAmount(BigDecimal baseAmount, String rateExpression) {
        return finalizeProcessedAmount(applyRateExpression(baseAmount, rateExpression));
    }

    /* 最终（6 位）processed amount 的高精度合计。 */
    public static BigDecimal sumProcessedAmounts(Collection<BigDecimal> amounts) {
        BigDecimal total = BigDecimal.ZERO;
        if (amounts != null) {
            for (BigDecimal amount : amounts) {
                total = total.add(TransactionMoneyFormat.nz(amount));
            }
        }
        return TransactionMoneyFormat.strip(total);
    }

    /* 仅用于校验的取整（HALF_UP → 2 位）；与前端合计展示一致。永不入库。 */
    public static BigDecimal roundTotalForValidation(BigDecimal total) {
        return TransactionMoneyFormat.nz(total).setScale(VALIDATION_SCALE, RoundingMode.HALF_UP);
    }

    /* Submit 门槛：HALF_UP(total, 2) 落在 ±0.05 内（含端点）。 */
    public static boolean isTotalWithinSubmitTolerance(BigDecimal total) {
        return roundTotalForValidation(total).abs().compareTo(SUBMIT_TOTAL_TOLERANCE) <= 0;
    }
}
