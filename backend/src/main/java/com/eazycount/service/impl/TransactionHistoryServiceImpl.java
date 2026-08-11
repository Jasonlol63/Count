package com.eazycount.service.impl;

import com.eazycount.common.BusinessException;
import com.eazycount.dao.TransactionHistoryDao;
import com.eazycount.dao.UserDao;
import com.eazycount.dto.TransactionHistoryBfAggregateRow;
import com.eazycount.dto.TransactionHistoryLineRow;
import com.eazycount.dto.TransactionHistoryRequest;
import com.eazycount.dto.TransactionHistoryResult;
import com.eazycount.dto.UserListDTO;
import com.eazycount.security.SecurityUtils;
import com.eazycount.security.SessionUser;
import com.eazycount.service.TransactionHistoryService;
import com.eazycount.util.TransactionDateParse;
import com.eazycount.util.TransactionMoneyFormat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Payment History: Win/Loss (Bank Process + Data Capture + manual Adjustment/Profit/Rate-middleman)
 * and Domain Payment (Cr/Dr) are built separately, then merged by the public orchestrator so Domain
 * rules do not leak into Win/Loss logic.
 */
@Service
public class TransactionHistoryServiceImpl implements TransactionHistoryService {

    private static final DateTimeFormatter HISTORY_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ROOT);
    private static final DateTimeFormatter RANGE_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ROOT);

    @Autowired
    private TransactionHistoryDao transactionHistoryDao;

    @Autowired
    private UserDao userDao;

    @Override
    public TransactionHistoryResult historyList(TransactionHistoryRequest request) {
        SessionUser session = SecurityUtils.currentUser();
        if (session == null) {
            throw new BusinessException("Not logged in");
        }
        if (request == null
                || request.getTenantId() == null || request.getTenantId() <= 0
                || request.getAccountId() == null || request.getAccountId() <= 0) {
            throw new BusinessException("Invalid request");
        }

        LocalDate dateFrom = TransactionDateParse.parseRequired(request.getDateFrom(), "dateFrom");
        LocalDate dateTo = TransactionDateParse.parseRequired(request.getDateTo(), "dateTo");
        if (dateTo.isBefore(dateFrom)) {
            throw new BusinessException("dateTo must be on or after dateFrom");
        }

        Integer tenantId = request.getTenantId();
        Integer accountId = request.getAccountId();
        List<String> currencyCodes = normalizeUpperList(request.getCurrencyCodes());

        UserListDTO account = userDao.findUserByIdAndTenantId(accountId, tenantId);
        if (account == null) {
            throw new BusinessException("Account not found");
        }

        String accountCode = trimToEmpty(account.getAccountId()).toUpperCase(Locale.ROOT);

        HistorySlice winLoss = buildWinLossHistorySlice(tenantId, accountId, dateFrom, dateTo, currencyCodes);
        HistorySlice domain = buildDomainPaymentHistorySlice(
                tenantId, accountId, dateFrom, dateTo, currencyCodes, accountCode);

        return mergeHistorySlices(account, dateFrom, dateTo, winLoss, domain);
    }

    // ── Win/Loss: Bank Process + Data Capture + manual Adjustment/Profit/Rate-middleman ─────────
    private HistorySlice buildWinLossHistorySlice(Integer tenantId, Integer accountId, LocalDate dateFrom, LocalDate dateTo, List<String> currencyCodes) {
        Map<String, BigDecimal> bfByCurrency = new LinkedHashMap<>();
        addBfRows(bfByCurrency, transactionHistoryDao.aggregateBankProcessBfByAccount(
                tenantId, accountId, dateFrom, currencyCodes));
        addBfRows(bfByCurrency, transactionHistoryDao.aggregateDataCaptureBfByAccount(
                tenantId, accountId, dateFrom, currencyCodes));
        addBfRows(bfByCurrency, transactionHistoryDao.aggregateManualAdjustmentBfByAccount(
                tenantId, accountId, dateFrom, currencyCodes));
        addBfRows(bfByCurrency, transactionHistoryDao.aggregateManualProfitBfByAccount(
                tenantId, accountId, dateFrom, currencyCodes));
        addBfRows(bfByCurrency, transactionHistoryDao.aggregateManualRateMiddlemanBfByAccount(
                tenantId, accountId, dateFrom, currencyCodes));

        List<TransactionHistoryLineRow> lines = new ArrayList<>();
        List<TransactionHistoryLineRow> bankLines = transactionHistoryDao.findBankProcessHistoryLines(
                tenantId, accountId, dateFrom, dateTo, currencyCodes);
        if (bankLines != null) {
            lines.addAll(bankLines);
        }
        List<TransactionHistoryLineRow> dataCaptureLines = transactionHistoryDao.findDataCaptureHistoryLines(
                tenantId, accountId, dateFrom, dateTo, currencyCodes);
        if (dataCaptureLines != null) {
            lines.addAll(dataCaptureLines);
        }
        List<TransactionHistoryLineRow> adjustmentLines = transactionHistoryDao.findManualAdjustmentHistoryLines(
                tenantId, accountId, dateFrom, dateTo, currencyCodes);
        if (adjustmentLines != null) {
            lines.addAll(adjustmentLines);
        }
        List<TransactionHistoryLineRow> profitLines = transactionHistoryDao.findManualProfitHistoryLines(
                tenantId, accountId, dateFrom, dateTo, currencyCodes);
        if (profitLines != null) {
            for (TransactionHistoryLineRow line : profitLines) {
                if (line == null) {
                    continue;
                }
                applyManualTransferHistoryPresentation(line, accountId);
                lines.add(line);
            }
        }
        return new HistorySlice(bfByCurrency, lines);
    }

    // ── Domain Payment (Cr/Dr) ────────────────────────────────────────────────
    private HistorySlice buildDomainPaymentHistorySlice(Integer tenantId, Integer accountId, LocalDate dateFrom, LocalDate dateTo, List<String> currencyCodes, String accountCode) {
        boolean c168ProfitView = "C168".equals(accountCode) || "PROFIT".equals(accountCode);

        Map<String, BigDecimal> bfByCurrency = new LinkedHashMap<>();
        addBfRows(bfByCurrency, transactionHistoryDao.aggregateDomainPaymentBfByAccount(
                tenantId, accountId, dateFrom, currencyCodes));

        List<TransactionHistoryLineRow> lines = new ArrayList<>();
        List<TransactionHistoryLineRow> domainLines = transactionHistoryDao.findDomainPaymentHistoryLines(
                tenantId, accountId, dateFrom, dateTo, currencyCodes);
        if (domainLines != null) {
            for (TransactionHistoryLineRow line : domainLines) {
                if (line == null) {
                    continue;
                }
                if (c168ProfitView) {
                    if (!isNetProfitDescription(line.getDescription())) {
                        continue;
                    }
                    // Retained profit as Cr/Dr (not self-leg net 0).
                    line.setSignedAmount(TransactionMoneyFormat.nz(line.getAmount()));
                }
                // Middle-Man double-entry To side (rate multiplier): hide leg2 payer.
                // Fee is middleman-only (from NULL) — always show for that account.
                if (Boolean.TRUE.equals(line.getRateMiddlemanFee())
                        && line.getFromAccountId() != null
                        && line.getToAccountId() != null
                        && accountId.equals(line.getToAccountId())) {
                    continue;
                }
                if (!applyRateHistoryPresentation(line, accountId)) {
                    applyManualTransferHistoryPresentation(line, accountId);
                }
                lines.add(line);
            }
        }
        return new HistorySlice(bfByCurrency, lines);
    }

    // ── Merge / present ───────────────────────────────────────────────────────
    private TransactionHistoryResult mergeHistorySlices(
            UserListDTO account,
            LocalDate dateFrom,
            LocalDate dateTo,
            HistorySlice winLoss,
            HistorySlice domain) {
        Map<String, BigDecimal> bfByCurrency = new LinkedHashMap<>();
        addBfMap(bfByCurrency, winLoss.bfByCurrency());
        addBfMap(bfByCurrency, domain.bfByCurrency());

        List<TransactionHistoryLineRow> lines = new ArrayList<>();
        lines.addAll(winLoss.lines());
        lines.addAll(domain.lines());
        lines.sort(Comparator
                .comparing(TransactionHistoryLineRow::getCreatedAt, Comparator.nullsLast(LocalDateTime::compareTo))
                .thenComparing(TransactionHistoryLineRow::getId, Comparator.nullsLast(Integer::compareTo)));

        Set<String> currencyOrder = new LinkedHashSet<>();
        bfByCurrency.keySet().stream().sorted().forEach(currencyOrder::add);
        for (TransactionHistoryLineRow line : lines) {
            if (line == null || line.getCurrencyCode() == null) {
                continue;
            }
            currencyOrder.add(line.getCurrencyCode().trim().toUpperCase(Locale.ROOT));
        }

        Map<String, BigDecimal> balanceByCurrency = new LinkedHashMap<>();
        for (String currency : currencyOrder) {
            balanceByCurrency.put(currency, bfByCurrency.getOrDefault(currency, BigDecimal.ZERO));
        }

        List<TransactionHistoryResult.Row> history = new ArrayList<>();

        for (String currency : currencyOrder.stream().sorted().toList()) {
            BigDecimal bf = balanceByCurrency.getOrDefault(currency, BigDecimal.ZERO);
            TransactionHistoryResult.Row bfRow = new TransactionHistoryResult.Row();
            bfRow.setRowType("bf");
            bfRow.setDate(formatHistoryDate(dateFrom));
            bfRow.setCurrency(currency);
            bfRow.setRate("-");
            bfRow.setWinLoss(TransactionMoneyFormat.formatMoney(BigDecimal.ZERO));
            bfRow.setCrDr(TransactionMoneyFormat.formatMoney(BigDecimal.ZERO));
            bfRow.setBalance(TransactionMoneyFormat.formatMoney(bf));
            bfRow.setDescription("OPENING BALANCE");
            bfRow.setIsBankProcessTransaction(false);
            history.add(bfRow);
        }

        for (TransactionHistoryLineRow line : lines) {
            if (line == null || line.getId() == null) {
                continue;
            }
            history.add(toHistoryRow(line, balanceByCurrency));
        }

        TransactionHistoryResult.Account accountDto = new TransactionHistoryResult.Account();
        accountDto.setId(account.getId());
        accountDto.setAccountId(trimToEmpty(account.getAccountId()));
        accountDto.setName(trimToEmpty(account.getName()));

        TransactionHistoryResult.DateRange range = new TransactionHistoryResult.DateRange();
        range.setFrom(dateFrom.format(RANGE_DATE));
        range.setTo(dateTo.format(RANGE_DATE));

        TransactionHistoryResult result = new TransactionHistoryResult();
        result.setAccount(accountDto);
        result.setDateRange(range);
        result.setHistory(history);
        return result;
    }

    private static TransactionHistoryResult.Row toHistoryRow(
            TransactionHistoryLineRow line,
            Map<String, BigDecimal> balanceByCurrency) {
        String currency = line.getCurrencyCode() != null
                ? line.getCurrencyCode().trim().toUpperCase(Locale.ROOT)
                : "";
        BigDecimal signed = line.getSignedAmount() != null
                ? TransactionMoneyFormat.nz(line.getSignedAmount())
                : signedAmountFallback(line.getTransactionType(), line.getAmount());
        BigDecimal running = balanceByCurrency.getOrDefault(currency, BigDecimal.ZERO).add(signed);
        balanceByCurrency.put(currency, running);

        boolean isBank = Boolean.TRUE.equals(line.getBankProcessLine());
        boolean isDataCapture = Boolean.TRUE.equals(line.getDataCaptureLine());
        boolean isAdjustment = isManualAdjustmentLine(line);
        boolean isProfit = isManualProfitLine(line);
        boolean isRateMiddlemanFee = Boolean.TRUE.equals(line.getRateMiddlemanFee());

        TransactionHistoryResult.Row row = new TransactionHistoryResult.Row();
        row.setId(line.getId());
        row.setDate(formatHistoryDate(line.getTransactionDate()));
        row.setIsBankProcessTransaction(isBank);
        row.setCardOwner(trimToEmpty(line.getCardOwner()));
        if (isAdjustment) {
            row.setProduct("ADJUSTMENT");
        } else if (isProfit) {
            row.setProduct("PROFIT");
        } else if (isRateMiddlemanFee) {
            row.setProduct("RATE");
        } else if (isDataCapture) {
            row.setProduct("DATA CAPTURE");
        } else if (!isBank) {
            row.setProduct(resolveDomainHistoryProduct(line));
        }
        row.setCurrency(currency);
        row.setRate("-");
        if (isBank || isAdjustment || isProfit || isRateMiddlemanFee) {
            row.setWinLoss(TransactionMoneyFormat.formatMoney(signed));
            row.setCrDr(TransactionMoneyFormat.formatMoney(BigDecimal.ZERO));
        } else {
            row.setWinLoss(TransactionMoneyFormat.formatMoney(BigDecimal.ZERO));
            row.setCrDr(TransactionMoneyFormat.formatMoney(signed));
        }
        row.setBalance(TransactionMoneyFormat.formatMoney(running));
        row.setDescription(trimToEmpty(line.getDescription()));
        row.setRemark(line.getRemark());
        row.setCreatedBy(trimToEmpty(line.getCreatedBy()));
        return row;
    }

    private static void addBfRows(Map<String, BigDecimal> bfByCurrency, List<TransactionHistoryBfAggregateRow> bfRows) {
        if (bfRows == null) {
            return;
        }
        for (TransactionHistoryBfAggregateRow bf : bfRows) {
            if (bf == null || bf.getCurrencyCode() == null) {
                continue;
            }
            String code = bf.getCurrencyCode().trim().toUpperCase(Locale.ROOT);
            bfByCurrency.merge(code, TransactionMoneyFormat.nz(bf.getBfAmount()), BigDecimal::add);
        }
    }

    private static void addBfMap(Map<String, BigDecimal> target, Map<String, BigDecimal> source) {
        if (source == null || source.isEmpty()) {
            return;
        }
        for (Map.Entry<String, BigDecimal> e : source.entrySet()) {
            if (e.getKey() == null) {
                continue;
            }
            target.merge(e.getKey(), TransactionMoneyFormat.nz(e.getValue()), BigDecimal::add);
        }
    }

    static boolean isManualAdjustmentLine(TransactionHistoryLineRow line) {
        if (line == null || line.getTransactionType() == null) {
            return false;
        }
        return "ADJUSTMENT".equalsIgnoreCase(line.getTransactionType().trim());
    }

    static boolean isManualProfitLine(TransactionHistoryLineRow line) {
        if (line == null || line.getTransactionType() == null) {
            return false;
        }
        return "PROFIT".equalsIgnoreCase(line.getTransactionType().trim());
    }

    /* Domain History ID Product: PAYMENT / COMMISSION / PROFIT / CLAIM / CLEAR / CONTRA. */
    static String domainProductFromDescription(String description) {
        String d = description != null ? description.trim().toUpperCase(Locale.ROOT) : "";
        if (d.startsWith("PAYMENT FROM ") || d.startsWith("PAYMENT TO ") || d.startsWith("PAY DOMAIN FEE")) {
            return "PAYMENT";
        }
        if (d.startsWith("CLAIM FROM ") || d.startsWith("CLAIM TO ")) {
            return "CLAIM";
        }
        if (d.startsWith("CLEAR FROM ") || d.startsWith("CLEAR TO ")) {
            return "CLEAR";
        }
        if (d.startsWith("CONTRA FROM ") || d.startsWith("CONTRA TO ")) {
            return "CONTRA";
        }
        if (d.contains("COMMISSION")) {
            return "COMMISSION";
        }
        if (d.startsWith("NET PROFIT") || d.startsWith("PROFIT FROM ") || d.startsWith("PROFIT TO ")) {
            return "PROFIT";
        }
        if (d.startsWith("EXCH RATE ")) {
            return "RATE";
        }

        return "";
    }

    static String resolveDomainHistoryProduct(TransactionHistoryLineRow line) {
        String fromDescription = domainProductFromDescription(line.getDescription());
        if (!fromDescription.isEmpty()) {
            return fromDescription;
        }
        if (!isManualTransferLine(line.getDescription())) {
            return "";
        }
        String type = line.getTransactionType() != null
                ? line.getTransactionType().trim().toUpperCase(Locale.ROOT)
                : "";
        if ("PAYMENT".equals(type) || "CLAIM".equals(type) || "CLEAR".equals(type)
                || "CONTRA".equals(type) || "PROFIT".equals(type) || "RATE".equals(type)) {
            return type;
        }
        return "";
    }

    /*
     * RATE History description (display only — always rebuilt; DB may store audit text with both FROM+TO):
     * Transfer legs: {@code EXCH RATE {rate} {ccy1} {amount} > {ccy2} | FROM|TO {accountCode}}
     * Middle-Man fee leg: {@code MARKUP {rate} {ccy1} {amt} > {ccy2} | FROM {leg1 To}} — middleman account only.
     * Direction follows leg1→leg2 currencies. FROM on payer (To), TO on receiver (From) — same as PAYMENT.
     */
    static boolean applyRateHistoryPresentation(
            TransactionHistoryLineRow line,
            Integer viewedAccountId) {
        if (line == null || viewedAccountId == null || viewedAccountId <= 0) {
            return false;
        }
        String type = line.getTransactionType() != null
                ? line.getTransactionType().trim().toUpperCase(Locale.ROOT)
                : "";
        if (!"RATE".equals(type)) {
            return false;
        }
        if (Boolean.TRUE.equals(line.getRateMiddlemanFee())) {
            applyRateMiddlemanHistoryPresentation(line, viewedAccountId);
            return true;
        }
        // Always rebuild viewpoint text so stored audit descriptions do not change History UI.
        String rate = trimToEmpty(line.getRateExpression());
        String ccy1 = trimToEmpty(line.getRateCurrencyFromCode()).toUpperCase(Locale.ROOT);
        String ccy2 = trimToEmpty(line.getRateCurrencyToCode()).toUpperCase(Locale.ROOT);
        String amountText = formatRateHistoryAmount(line.getRateAmountFrom());
        if (rate.isEmpty() || ccy1.isEmpty() || ccy2.isEmpty()) {
            // Fallback to PAYMENT-style if FX header missing
            applyManualTransferHistoryPresentation(line, viewedAccountId);
            return true;
        }
        String prefix = "EXCH RATE " + rate + " " + ccy1 + " " + amountText + " > " + ccy2;
        String payerCode = trimToEmpty(line.getToAccountCode()).toUpperCase(Locale.ROOT);
        String receiverCode = trimToEmpty(line.getFromAccountCode()).toUpperCase(Locale.ROOT);
        // Receiver (From): TO {payer}; payer (To): FROM {receiver} — same as PAYMENT.
        if (line.getFromAccountId() != null && viewedAccountId.equals(line.getFromAccountId())) {
            line.setDescription(prefix + " | TO " + payerCode);
            return true;
        }
        if (line.getToAccountId() != null && viewedAccountId.equals(line.getToAccountId())) {
            line.setDescription(prefix + " | FROM " + receiverCode);
            return true;
        }
        return true;
    }

    /*
     * Middle-Man History (middleman / From leg only):
     * Rate: {@code MARKUP {rate} {ccy1} {amt} > {ccy2} | FROM {leg1 To}}
     * Fee:  {@code MARKUP X {ccy1} {amt} > {ccy2} | FROM {leg1 To}}
     */
    static void applyRateMiddlemanHistoryPresentation(
            TransactionHistoryLineRow line,
            Integer viewedAccountId) {
        boolean middlemanView;
        if (line.getFromAccountId() != null) {
            middlemanView = viewedAccountId.equals(line.getFromAccountId());
        } else {
            // Fee single-sided: account_id = middleman
            middlemanView = line.getToAccountId() != null
                    && viewedAccountId.equals(line.getToAccountId());
        }
        if (!middlemanView) {
            return;
        }
        line.setDescription(formatRateMiddlemanMarkupDescription(line));
        // Service-fee CHARGE remark belongs on toAccount1 (leg1) only — not middleman History.
        line.setRemark(null);
    }

    static String formatRateMiddlemanMarkupDescription(TransactionHistoryLineRow line) {
        boolean feeKind = isRateMiddlemanFeeKind(line);
        String rateToken = feeKind
                ? "X"
                : formatRateHistoryDecimal(line.getRateMiddlemanRate(), 6);
        String ccy1 = trimToEmpty(line.getRateCurrencyFromCode()).toUpperCase(Locale.ROOT);
        String ccy2 = trimToEmpty(line.getRateCurrencyToCode()).toUpperCase(Locale.ROOT);
        String amountText = formatRateHistoryDecimal(line.getRateAmountFrom(), 6);
        String leg1ToCode = trimToEmpty(line.getRateLeg1ToAccountCode()).toUpperCase(Locale.ROOT);

        StringBuilder sb = new StringBuilder("MARKUP");
        if (!rateToken.isEmpty()) {
            sb.append(' ').append(rateToken);
        }
        if (!ccy1.isEmpty() && !ccy2.isEmpty()) {
            sb.append(' ').append(ccy1);
            if (!amountText.isEmpty()) {
                sb.append(' ').append(amountText);
            }
            sb.append(" > ").append(ccy2);
        }
        if (!leg1ToCode.isEmpty()) {
            sb.append(" | FROM ").append(leg1ToCode);
        }
        return sb.toString();
    }

    static boolean isRateMiddlemanFeeKind(TransactionHistoryLineRow line) {
        if (line == null) {
            return false;
        }
        String kind = trimToEmpty(line.getRateMiddlemanKind()).toUpperCase(Locale.ROOT);
        if ("FEE".equals(kind)) {
            return true;
        }
        if ("RATE".equals(kind)) {
            return false;
        }
        String desc = trimToEmpty(line.getDescription()).toUpperCase(Locale.ROOT);
        if (desc.startsWith("MARKUP X ") || "RATE_MIDDLEMAN_FEE".equals(desc)) {
            return true;
        }
        return false;
    }

    static String formatRateHistoryDecimal(BigDecimal amount, int maxScale) {
        if (amount == null) {
            return "";
        }
        return amount.setScale(maxScale, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }

    static String formatRateHistoryAmount(BigDecimal amount) {
        return TransactionMoneyFormat.formatMoney(amount);
    }

    /*
     * Manual PAYMENT / CLAIM / CLEAR / CONTRA / PROFIT History display (viewpoint text).
     * Domain / system lines with other descriptions are left as stored.
     */
    static void applyManualTransferHistoryPresentation(
            TransactionHistoryLineRow line,
            Integer viewedAccountId) {
        if (line == null || viewedAccountId == null || viewedAccountId <= 0) {
            return;
        }
        String type = line.getTransactionType() != null
                ? line.getTransactionType().trim().toUpperCase(Locale.ROOT)
                : "";
        if (!"PAYMENT".equals(type) && !"CLAIM".equals(type) && !"CLEAR".equals(type)
                && !"CONTRA".equals(type) && !"PROFIT".equals(type) && !"RATE".equals(type)) {
            return;
        }
        if (!shouldRewriteManualTransferHistoryDescription(line.getDescription(), type)) {
            return;
        }
        String payerCode = trimToEmpty(line.getToAccountCode()).toUpperCase(Locale.ROOT);
        String receiverCode = trimToEmpty(line.getFromAccountCode()).toUpperCase(Locale.ROOT);
        if (line.getFromAccountId() != null && viewedAccountId.equals(line.getFromAccountId())) {
            line.setDescription(type + " TO " + payerCode);
            return;
        }
        if (line.getToAccountId() != null && viewedAccountId.equals(line.getToAccountId())) {
            line.setDescription(type + " FROM " + receiverCode);
        }
    }

    /*
     * Blank (legacy) or stored audit {@code TYPE FROM … TO …} → rewrite for History.
     * Other stored text (e.g. domain {@code PAY DOMAIN FEE}) is kept.
     */
    static boolean shouldRewriteManualTransferHistoryDescription(String description, String type) {
        if (description == null || description.isBlank()) {
            return true;
        }
        String d = description.trim();
        String typeToken = type != null ? type.trim().toUpperCase(Locale.ROOT) : "";
        if (typeToken.isEmpty()) {
            return false;
        }
        String upper = d.toUpperCase(Locale.ROOT);
        return upper.startsWith(typeToken + " FROM ") && upper.contains(" TO ");
    }

    static boolean isManualTransferLine(String description) {
        return description == null || description.isBlank();
    }

    static boolean isNetProfitDescription(String description) {
        String d = description != null ? description.trim().toUpperCase(Locale.ROOT) : "";
        return d.startsWith("NET PROFIT");
    }

    private static BigDecimal signedAmountFallback(String transactionType, BigDecimal amount) {
        BigDecimal value = TransactionMoneyFormat.nz(amount);
        if (transactionType != null && "ADJUSTMENT".equalsIgnoreCase(transactionType.trim())) {
            return value;
        }
        if (transactionType != null && "WIN".equalsIgnoreCase(transactionType.trim())) {
            return value;
        }
        if (transactionType != null && "LOSE".equalsIgnoreCase(transactionType.trim())) {
            return value.negate();
        }
        return value.negate();
    }

    private static String formatHistoryDate(LocalDate date) {
        if (date == null) {
            return "/";
        }
        return date.format(HISTORY_DATE);
    }

    private static List<String> normalizeUpperList(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        return raw.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(s -> s.trim().toUpperCase(Locale.ROOT))
                .distinct()
                .collect(Collectors.toList());
    }

    private static String trimToEmpty(String value) {
        return value != null ? value.trim() : "";
    }

    /* One source's BF + period lines before merge. */
    private record HistorySlice(
            Map<String, BigDecimal> bfByCurrency,
            List<TransactionHistoryLineRow> lines) {
    }
}
