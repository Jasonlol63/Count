<?php
/**
 * 1+N 合同规则：
 * - active：统一按 1 个月价格计算；
 * - manual_inactive：仅在 inactive 赔付时，按 +N 月数放大（由 getManualInactiveMultiplierFromContract 控制）。
 */

declare(strict_types=1);

/** 与 manual_inactive 相同：1+1/1+2/1+3 → N，其余 → 1 */
function getManualInactiveMultiplierFromContract(?string $contract): int
{
    if ($contract === null || $contract === '') {
        return 1;
    }
    $c = trim($contract);
    if (preg_match('/^1\+(\d+)$/i', $c, $m)) {
        return max(1, (int) $m[1]);
    }
    return 1;
}

/** @deprecated active 场景不再使用 1+N 放大，仅保留兼容。 */
function getContractOnePlusExtraFullMonths(?string $contract): int
{
    if ($contract === null || trim($contract) === '') {
        return 0;
    }
    $c = strtoupper(trim($contract));
    if (preg_match('/^1\+(\d+)/', $c, $m)) {
        return max(0, (int) $m[1]);
    }
    return 0;
}

/** 从 startYmd 到当月底（含）占当月天数的比例 */
function ratioRemainingDaysInMonthFromStartYmd(string $startYmd): ?string
{
    $ts = strtotime($startYmd);
    if ($ts === false) {
        return null;
    }
    $daysInMonth = (int) date('t', $ts);
    $dayOfMonth = (int) date('j', $ts);
    if ($daysInMonth <= 0) {
        return null;
    }
    $daysRemaining = max(0, $daysInMonth - $dayOfMonth + 1);

    return money_div((string) $daysRemaining, (string) $daysInMonth, MONEY_CALC_SCALE);
}

/**
 * @param string|null $prorationRatio 本次入账「剩余天数/当月天数」；null 或 >=1 时不调整
 * @param string      $origCost       整月 Buy
 * @param string      $origPrice      整月 Sell
 * @param string      $origProfit     整月 Profit
 */
function applyOnePlusXRemainingDaysBuySellAddon(
    ?string $contract,
    string $origCost,
    string $origPrice,
    string $origProfit,
    string &$cost,
    string &$price,
    string &$profit,
    ?string $prorationRatio
): void {
    // New rule: active billing always keeps 1-month amounts.
    // 1+N compensation is handled only in manual_inactive flow.
    return;
}

/**
 * 某自然月第 N 日（不超过该月最后一天）— 与 process_accounting_inbox_api 的 calendarMonthDueYmd 一致。
 */
function billingCalendarMonthDueYmd(int $year, int $month, int $dueDay): string
{
    $last = (int) date('t', mktime(0, 0, 0, $month, 1, $year));
    $d = min(max(1, $dueDay), $last);

    return sprintf('%04d-%02d-%02d', $year, $month, $d);
}

/**
 * Frequency=monthly（先付 / prepaid）：应付日当天付「从应付日起连续 1 个月」的服务。
 * 区间为 [dueYmd, dueYmd+1月-1日]（例如 6/17 应付 → 6/17–7/16）。
 *
 * @return array{0:string,1:string}
 */
function billingMonthlyAnniversaryInclusiveRangeFromDue(string $dueYmd, string $contractStartYmd): array
{
    try {
        $due = new DateTimeImmutable($dueYmd);

        return [$dueYmd, $due->modify('+1 month')->modify('-1 day')->format('Y-m-d')];
    } catch (Throwable $e) {
        return [$dueYmd, $dueYmd];
    }
}

/** 含首尾两日的天数；无效或 from>to 时返回 0 */
function billingInclusiveDaysBetween(string $fromYmd, string $toYmd): int
{
    $a = strtotime($fromYmd);
    $b = strtotime($toYmd);
    if ($a === false || $b === false || $fromYmd > $toYmd) {
        return 0;
    }

    return (int) round(($b - $a) / 86400) + 1;
}

/**
 * Monthly 对日对月：整期 [p0,p1] 对应一笔固定月价（cost/price/profit），仅按 [from,p1] 占整期的日历天数比例缩放。
 * 不可使用 prorateInclusiveDateRange：该函数按「每个自然月」切片乘整月价，跨两自然月的一期会得到比例之和 >1（如 1111→1125）。
 *
 * @return array{cost:string,price:string,profit:string,ratio:?string}
 */
function prorateMonthlyAnniversaryPeriodLinear(
    string $p0,
    string $p1,
    string $from,
    string $cost,
    string $price,
    string $profit
): array {
    if ($from > $p1) {
        return ['cost' => '0.00000000', 'price' => '0.00000000', 'profit' => '0.00000000', 'ratio' => null];
    }
    $adjFrom = $from < $p0 ? $p0 : $from;
    $fullD = billingInclusiveDaysBetween($p0, $p1);
    $useD = billingInclusiveDaysBetween($adjFrom, $p1);
    if ($fullD <= 0) {
        return ['cost' => '0.00000000', 'price' => '0.00000000', 'profit' => '0.00000000', 'ratio' => null];
    }
    $r = money_div((string) $useD, (string) $fullD, MONEY_CALC_SCALE);

    return [
        'cost' => money_mul($cost, $r, 2),
        'price' => money_mul($price, $r, 2),
        'profit' => money_mul($profit, $r, 2),
        'ratio' => $r,
    ];
}

/** Week：单期 [start, start+6]（含首尾 7 日）。 */
function weekPeriodEndInclusiveYmd(string $periodStartYmd): ?string
{
    try {
        return (new DateTimeImmutable($periodStartYmd))->modify('+6 days')->format('Y-m-d');
    } catch (Throwable $e) {
        return null;
    }
}

/** 下一期起点 = 上一期结束日次日（周期间不重叠，如 6/1–6/7 后接 6/8–6/14）。 */
function weekPeriodNextStartYmd(string $currentPeriodStartYmd): ?string
{
    try {
        return (new DateTimeImmutable($currentPeriodStartYmd))->modify('+7 days')->format('Y-m-d');
    } catch (Throwable $e) {
        return null;
    }
}

/** 非 Resend：仅当今天 >= 周期开始日，该周才进入 Accounting Due / 允许入账（例：6/1–6/7 在 6/1 出现）。 */
function weekPeriodIsReadyForAccounting(string $periodStartYmd, string $todayYmd, bool $resendRelax): bool
{
    if ($resendRelax) {
        return true;
    }
    return $todayYmd >= $periodStartYmd;
}

/** Day frequency：下一自然日 Y-m-d。 */
function dailyNextDayYmd(string $ymd): ?string
{
    try {
        return (new DateTimeImmutable($ymd))->modify('+1 day')->format('Y-m-d');
    } catch (Throwable $e) {
        return null;
    }
}

/** 指定自然月首日 Y-m-d。 */
function calendarMonthFirstYmd(int $year, int $month): string
{
    return sprintf('%04d-%02d-01', $year, max(1, min(12, $month)));
}

/** Day frequency：按天数累乘 cost / price / profit（单日全额 × N）。 */
function dailyAmountsForDayCount(string $cost, string $price, string $profit, int $days): array
{
    $d = (string) max(1, $days);
    return [
        'cost' => money_mul($cost, $d, 2),
        'price' => money_mul($price, $d, 2),
        'profit' => money_mul($profit, $d, 2),
    ];
}

/** 解析 daily consolidated billing_month 锚点 `start|end`（均为 Y-m-d）。 */
function dailyParseConsolidatedBillingRange(?string $billingMonth): ?array
{
    $raw = trim((string) $billingMonth);
    if ($raw === '' || strpos($raw, '|') === false) {
        return null;
    }
    [$start, $end] = array_map('trim', explode('|', $raw, 2));
    if (!preg_match('/^\d{4}-\d{2}-\d{2}$/', $start) || !preg_match('/^\d{4}-\d{2}-\d{2}$/', $end)) {
        return null;
    }
    if ($start > $end) {
        return null;
    }
    return ['start' => $start, 'end' => $end];
}

/** 含首尾的自然日天数。 */
function dailyInclusiveDayCount(string $startYmd, string $endYmd): int
{
    try {
        $a = new DateTimeImmutable($startYmd);
        $b = new DateTimeImmutable($endYmd);
        if ($b < $a) {
            return 0;
        }
        return (int) $a->diff($b)->days + 1;
    } catch (Throwable $e) {
        return 0;
    }
}
