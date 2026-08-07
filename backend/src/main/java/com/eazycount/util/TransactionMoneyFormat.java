package com.eazycount.util;

import com.eazycount.common.BusinessException;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 交易金额工具：入库 / 返回真值精度；UI 取整仅由前端负责。
 * <ul>
 *   <li>普通金额（PAYMENT / Bank Process / Domain / …）：最多 {@link #NORMAL_AMOUNT_SCALE} 位小数</li>
 *   <li>RATE 金额（legs / middleman 分摊）：最多 {@link #RATE_AMOUNT_SCALE} 位小数</li>
 *   <li>汇率 / middleman rate：最多 {@link #RATE_AMOUNT_SCALE} 位小数</li>
 * </ul>
 */
public final class TransactionMoneyFormat {

    /* 普通交易金额的最大小数位数。 */
    public static final int NORMAL_AMOUNT_SCALE = 6;
    /* RATE 金额与汇率的最大小数位数。 */
    public static final int RATE_AMOUNT_SCALE = 8;

    private TransactionMoneyFormat() {
    }

    /* API / 存储序列化：高精度 plain 字符串（不做 round-to-2）。 */
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

    /* 去掉尾随零，不改变数值。 */
    public static BigDecimal strip(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal stripped = value.stripTrailingZeros();
        // 整数（scale 为负）避免科学计数法。
        if (stripped.scale() < 0) {
            return stripped.setScale(0);
        }
        return stripped;
    }

    /* 去掉尾随零后的小数位数（整数为 0）。 */
    public static int decimalPlaces(BigDecimal value) {
        return Math.max(strip(value).scale(), 0);
    }

    /* 用户 / 客户端输入：保留精确值；小数位数超过 {maxScale} 时拒绝。*/
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

    /* 系统计算结果（Accounting Due / Domain fee 分摊）：不做 round-to-2。
     * 仅当小数位数超过 {maxScale} 时，HALF_UP 到该上限。*/
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

    /* Data Capture Summary 规则（截断，非 half-up）：超出 {maxScale} 的小数位向零丢弃。
     * 与前端 {MoneyDecimal.formatFixed(value, scale)} / ROUND_DOWN 对齐。*/
    public static BigDecimal truncateToScale(BigDecimal value, int maxScale) {
        BigDecimal amount = strip(nz(value));
        if (decimalPlaces(amount) > maxScale) {
            return strip(amount.setScale(maxScale, RoundingMode.DOWN));
        }
        return amount;
    }

    /* 截断到 {@link #NORMAL_AMOUNT_SCALE}（6 位）——入库前最终 processed amount。 */
    public static BigDecimal truncateNormalAmount(BigDecimal value) {
        return truncateToScale(value, NORMAL_AMOUNT_SCALE);
    }

    /* 截断到 {@link #RATE_AMOUNT_SCALE}（8 位）——走 rate 路径的中间金额。 */
    public static BigDecimal truncateRateAmount(BigDecimal value) {
        return truncateToScale(value, RATE_AMOUNT_SCALE);
    }
}
