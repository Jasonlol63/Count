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
import com.eazycount.util.RateMulCalculator;
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
 * 交易记录：Win/Loss 和 Domain Payment（Cr/Dr）分开构建，最后统一合并，避免两边规则互相影响。
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
                tenantId, accountId, dateFrom, currencyCodes, c168ProfitView));

        List<TransactionHistoryLineRow> lines = new ArrayList<>();
        List<TransactionHistoryLineRow> domainLines = transactionHistoryDao.findDomainPaymentHistoryLines(
                tenantId, accountId, dateFrom, dateTo, currencyCodes);
        if (domainLines != null) {
            for (TransactionHistoryLineRow line : domainLines) {
                if (line == null) {
                    continue;
                }
                boolean isDomainFeeOrCommission = isDomainFeeOrCommissionRemark(line.getRemark());
                boolean isDomainNetProfit = isDomainNetProfitRemark(line.getRemark());
                if (isDomainFeeOrCommission || isDomainNetProfit) {
                    // 内部记账标记，任何视角都不展示给用户
                    line.setRemark(null);
                }
                if (c168ProfitView) {
                    // C168/PROFIT 自身余额要排除收取的手续费和支付的佣金（过手资金），
                    // 但保留 Net Profit 行和真实的手动转账
                    if (isDomainFeeOrCommission) {
                        continue;
                    }
                    if (isDomainNetProfit) {
                        // 留存利润按 Cr/Dr 记全额（而非自引用抵消为 0）
                        line.setSignedAmount(TransactionMoneyFormat.nz(line.getAmount()));
                    }
                }
                if (!applyRateHistoryPresentation(line, accountId)) {
                    applyManualTransferHistoryPresentation(line, accountId);
                }
                lines.add(line);
            }
        }
        mergeRateMiddlemanDeductionsIntoMainLeg(lines, accountId);
        return new HistorySlice(bfByCurrency, lines);
    }

    /*
     * leg2 账号的 Rate-Mul/Service Fee 并入主记录的 Cr/Dr，不单独显示；Platform Fee 单边、
     * 无对手方，始终单独一行（走 Cr/Dr，product "Fee"）。
     * Service Fee 存库金额已扣过一次 Platform Fee，这里还原成全额，避免重复扣减。
     */
    private static void mergeRateMiddlemanDeductionsIntoMainLeg(List<TransactionHistoryLineRow> lines, Integer accountId) {
        Map<String, TransactionHistoryLineRow> mainLineByGroup = new LinkedHashMap<>();
        Map<String, BigDecimal> platformFeeByGroup = new LinkedHashMap<>();
        for (TransactionHistoryLineRow line : lines) {
            if (line.getRateGroupId() == null) {
                continue;
            }
            if (!Boolean.TRUE.equals(line.getRateMiddlemanFee())
                    && line.getFromAccountId() != null
                    && accountId.equals(line.getFromAccountId())) {
                mainLineByGroup.put(line.getRateGroupId(), line);
            }
            if (Boolean.TRUE.equals(line.getRateMiddlemanFee())
                    && line.getFromAccountId() == null
                    && line.getToAccountId() != null
                    && accountId.equals(line.getToAccountId())) {
                platformFeeByGroup.put(line.getRateGroupId(), TransactionMoneyFormat.nz(line.getAmount()));
            }
        }
        if (mainLineByGroup.isEmpty()) {
            return;
        }
        List<TransactionHistoryLineRow> toRemove = new ArrayList<>();
        for (TransactionHistoryLineRow line : lines) {
            // 仅处理双边中间人行（Rate-Mul/Service Fee），Platform Fee 无 fromAccountId，跳过
            if (!Boolean.TRUE.equals(line.getRateMiddlemanFee()) || line.getFromAccountId() == null) {
                continue;
            }
            if (line.getToAccountId() == null || !accountId.equals(line.getToAccountId())) {
                continue;
            }
            TransactionHistoryLineRow mainLine = mainLineByGroup.get(line.getRateGroupId());
            if (mainLine == null) {
                continue;
            }
            BigDecimal delta = TransactionMoneyFormat.nz(line.getSignedAmount());
            if (isRateMiddlemanFeeKind(line)) {
                BigDecimal platformFee = platformFeeByGroup.get(line.getRateGroupId());
                if (platformFee != null) {
                    delta = delta.subtract(platformFee);
                }
            }
            mainLine.setSignedAmount(TransactionMoneyFormat.nz(mainLine.getSignedAmount()).add(delta));
            toRemove.add(line);
        }
        lines.removeAll(toRemove);
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
                .comparing(TransactionHistoryLineRow::getTransactionDate, Comparator.nullsLast(LocalDate::compareTo))
                .thenComparing(TransactionHistoryLineRow::getCreatedAt, Comparator.nullsLast(LocalDateTime::compareTo))
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
            bfRow.setDate("B/F");
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
        // Platform Fee：唯一单边（无 fromAccountId）的 Rate-Mul/Fee 行，走 Cr/Dr，product "Fee"
        boolean isPlatformFee = isRateMiddlemanFee && line.getFromAccountId() == null;

        TransactionHistoryResult.Row row = new TransactionHistoryResult.Row();
        row.setId(line.getId());
        row.setDate(formatHistoryDate(line.getTransactionDate()));
        row.setIsBankProcessTransaction(isBank);
        row.setCardOwner(trimToEmpty(line.getCardOwner()));
        if (isAdjustment) {
            row.setProduct("ADJUSTMENT");
        } else if (isProfit) {
            row.setProduct("PROFIT");
        } else if (isPlatformFee) {
            row.setProduct("Fee");
        } else if (isRateMiddlemanFee) {
            row.setProduct("RATE");
        } else if (isDataCapture) {
            String idProduct = trimToEmpty(line.getIdProduct());
            row.setProduct(!idProduct.isEmpty() ? idProduct : "DATA CAPTURE");
        } else if (!isBank) {
            row.setProduct(resolveDomainHistoryProduct(line));
        }
        row.setCurrency(currency);
        String dataCaptureRate = isDataCapture ? trimToEmpty(line.getRateExpression()) : "";
        row.setRate(!dataCaptureRate.isEmpty() ? dataCaptureRate : "-");
        if (isBank || isAdjustment || isProfit || (isRateMiddlemanFee && !isPlatformFee)) {
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
     * RATE 记录描述（仅展示用，每次重新生成）：
     * 转账腿：EXCH RATE {rate} {ccy1} {amount} > {ccy2} | FROM|TO {accountCode}
     * 中间人手续费腿：MARKUP {rate} {ccy1} {amt} > {ccy2} | FROM {leg1 To}
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
        // 每次重新生成视角文案，避免存库审计文本影响 History 展示
        String rate = trimToEmpty(line.getRateExpression());
        String ccy1 = trimToEmpty(line.getRateCurrencyFromCode()).toUpperCase(Locale.ROOT);
        String ccy2 = trimToEmpty(line.getRateCurrencyToCode()).toUpperCase(Locale.ROOT);
        String amountText = formatRateHistoryAmount(line.getRateAmountFrom());
        if (rate.isEmpty() || ccy1.isEmpty() || ccy2.isEmpty()) {
            // FX 信息缺失时退化为 PAYMENT 样式
            applyManualTransferHistoryPresentation(line, viewedAccountId);
            return true;
        }
        String prefix = "EXCH RATE " + rate + " " + ccy1 + " " + amountText + " > " + ccy2;
        String payerCode = trimToEmpty(line.getToAccountCode()).toUpperCase(Locale.ROOT);
        String receiverCode = trimToEmpty(line.getFromAccountCode()).toUpperCase(Locale.ROOT);
        // 收款方(From)显示 TO {付款方}；付款方(To)显示 FROM {收款方}，与 PAYMENT 一致
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
     * 中间人视角记录（仅 middleman/From 腿）：
     * Rate: MARKUP {rate} {ccy1} {amt} > {ccy2} | FROM {leg1 To}
     * Fee:  MARKUP X {ccy1} {amt} > {ccy2} | FROM {leg1 To}
     */
    static void applyRateMiddlemanHistoryPresentation(
            TransactionHistoryLineRow line,
            Integer viewedAccountId) {
        if (line.getFromAccountId() == null) {
            // 单边的 Platform Fee 无中间人对手方，保留原始描述不改写
            return;
        }
        boolean middlemanView = viewedAccountId.equals(line.getFromAccountId());
        if (!middlemanView) {
            return;
        }
        line.setDescription(formatRateMiddlemanMarkupDescription(line));
        // 中间人自己视角不展示交易的通用 remark
        line.setRemark(null);
    }

    static String formatRateMiddlemanMarkupDescription(TransactionHistoryLineRow line) {
        boolean feeKind = isRateMiddlemanFeeKind(line);
        String rateToken = feeKind ? "X" : formatRateMiddlemanRateToken(line);
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

    /*
     * Rate-Mul token 展示规则：
     * - 乘法模式：原汇率 − middleman 输入，如 3 − 2.9 = 0.1
     * - 除法模式：middleman 除数 − FX 除数，如 1.305 − 1.32 = -0.015
     * 统一四舍五入到 6 位小数，末尾 0 省略
     */
    static String formatRateMiddlemanRateToken(TransactionHistoryLineRow line) {
        RateMulCalculator.ParsedRate parsed =
                RateMulCalculator.parseMiddlemanRateInput(line.getRateMiddlemanRateExpression());
        if (!parsed.valid()) {
            return formatRateHistoryDecimal(line.getRateMiddlemanRate(), 6);
        }
        if (parsed.mode() == RateMulCalculator.Mode.DIVIDE) {
            BigDecimal fxDivisor = RateMulCalculator.parseSimpleDivisionDivisor(line.getRateExpression());
            if (fxDivisor != null && parsed.divisor() != null) {
                return formatRateHistoryDecimal(parsed.divisor().subtract(fxDivisor), 6);
            }
            return formatRateHistoryDecimal(line.getRateMiddlemanRate(), 6);
        }
        boolean fxIsDivide = RateMulCalculator.parseSimpleDivisionDivisor(line.getRateExpression()) != null;
        if (!fxIsDivide && line.getRateExchangeRate() != null && line.getRateMiddlemanRate() != null) {
            return formatRateHistoryDecimal(
                    line.getRateExchangeRate().subtract(line.getRateMiddlemanRate()), 6);
        }
        return formatRateHistoryDecimal(line.getRateMiddlemanRate(), 6);
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

    /* 手动 PAYMENT/CLAIM/CLEAR/CONTRA/PROFIT 的视角文案展示，其他 Domain/系统记录保持原样 */
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

    /* 空白（旧数据）或存库的 "TYPE FROM … TO …" 审计文本才改写，其他文本（如 PAY DOMAIN FEE）保留 */
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

    static boolean isDomainFeeOrCommissionRemark(String remark) {
        String r = remark != null ? remark.trim() : "";
        return DomainFeeChargeServiceImpl.REMARK_DOMAIN_FEE.equals(r)
                || DomainFeeChargeServiceImpl.REMARK_DOMAIN_COMMISSION.equals(r);
    }

    static boolean isDomainNetProfitRemark(String remark) {
        String r = remark != null ? remark.trim() : "";
        return DomainFeeChargeServiceImpl.REMARK_DOMAIN_NET_PROFIT.equals(r);
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
