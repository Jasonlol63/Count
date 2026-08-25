package com.eazycount.service.impl;

import com.eazycount.common.BusinessException;
import com.eazycount.dao.ReportDao;
import com.eazycount.dto.CustomerReportDTO;
import com.eazycount.dto.DomainReportDTO;
import com.eazycount.security.SecurityUtils;
import com.eazycount.security.SessionUser;
import com.eazycount.service.ReportService;
import com.eazycount.util.TransactionDateParse;
import com.eazycount.util.TransactionMoneyFormat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@Service
public class ReportServiceImpl implements ReportService {

    @Autowired
    private ReportDao reportDao;

    @Override
    public List<CustomerReportDTO> findCustomerReportRows(CustomerReportDTO request) {
        SessionUser sessionUser = SecurityUtils.currentUser();
        if (sessionUser == null) {
            throw new BusinessException("User is not logged in");
        }
        if (request == null || request.getTenantId() == null || request.getTenantId() <= 0) {
            throw new BusinessException("Invalid tenant id");
        }

        LocalDate dateFrom = TransactionDateParse.parseRequired(request.getDateFrom(), "dateFrom");
        LocalDate dateTo = TransactionDateParse.parseRequired(request.getDateTo(), "dateTo");
        if (dateTo.isBefore(dateFrom)) {
            throw new BusinessException("dateTo must be on or after dateFrom");
        }

        // accountId is optional: null/blank means "All Accounts" — every account under the tenant is returned.
        Integer accountId = request.getAccountId() != null && request.getAccountId() > 0
                ? request.getAccountId()
                : null;
        // currencyCodes empty/null means "every currency the account is assigned to" (account_currency) — no filter.
        List<String> currencyCodes = normalizeUpperList(request.getCurrencyCodes());
        boolean showAll = Boolean.TRUE.equals(request.getShowAll());

        List<CustomerReportDTO> rawRows = reportDao.findCustomerReportRows(
                request.getTenantId(), currencyCodes, dateFrom, dateTo, accountId);
        if (rawRows == null) {
            rawRows = List.of();
        }

        BigDecimal totalWin = BigDecimal.ZERO;
        BigDecimal totalLose = BigDecimal.ZERO;
        List<CustomerReportDTO> rows = new ArrayList<>();

        for (CustomerReportDTO raw : rawRows) {
            if (raw == null) {
                continue;
            }

            // Win: positive. Lose: negative (matches legacy Customer Report display convention).
            BigDecimal win = TransactionMoneyFormat.nz(raw.getWinAmount());
            BigDecimal lose = TransactionMoneyFormat.nz(raw.getLoseAmount()).negate();
            totalWin = totalWin.add(win);
            totalLose = totalLose.add(lose);

            raw.setWinAmount(win);
            raw.setLoseAmount(lose);

            // Show All off: only keep account/currency rows with a non-zero Win or Lose. Show All on: keep every row.
            if (!showAll && win.signum() == 0 && lose.signum() == 0) {
                continue;
            }
            rows.add(raw);
        }

        // Blended across every currency in the result (matches legacy total_win/total_lose) —
        // only meaningful to the caller when a single currency was requested.
        CustomerReportDTO total = new CustomerReportDTO();
        total.setTotalRow(true);
        total.setWinAmount(totalWin);
        total.setLoseAmount(totalLose);
        rows.add(total);

        return rows;
    }

    @Override
    public List<DomainReportDTO> findDomainReportRows(DomainReportDTO request) {
        SessionUser sessionUser = SecurityUtils.currentUser();
        if (sessionUser == null) {
            throw new BusinessException("User is not logged in");
        }
        if (request == null || request.getTenantId() == null || request.getTenantId() <= 0) {
            throw new BusinessException("Invalid tenant id");
        }

        LocalDate dateFrom = TransactionDateParse.parseRequired(request.getDateFrom(), "dateFrom");
        LocalDate dateTo = TransactionDateParse.parseRequired(request.getDateTo(), "dateTo");
        if (dateTo.isBefore(dateFrom)) {
            throw new BusinessException("dateTo must be on or after dateFrom");
        }

        Integer processId = request.getProcessId() != null && request.getProcessId() > 0
                ? request.getProcessId()
                : null;
        String category = request.getCategory() != null && !request.getCategory().isBlank()
                ? request.getCategory().trim().toUpperCase(Locale.ROOT)
                : "GAME";

        List<DomainReportDTO> rawRows = reportDao.findDomainReportRows(
                request.getTenantId(), dateFrom, dateTo, processId, category);
        if (rawRows == null) {
            rawRows = List.of();
        }

        BigDecimal totalTurnover = BigDecimal.ZERO;
        BigDecimal totalWin = BigDecimal.ZERO;
        BigDecimal totalLose = BigDecimal.ZERO;
        List<DomainReportDTO> rows = new ArrayList<>();

        for (DomainReportDTO raw : rawRows) {
            if (raw == null) {
                continue;
            }

            // Win and Lose both stay positive here — unlike Customer Report, Domain Report does not negate Lose.
            BigDecimal win = TransactionMoneyFormat.nz(raw.getWinAmount());
            BigDecimal lose = TransactionMoneyFormat.nz(raw.getLoseAmount());
            BigDecimal turnover = win.add(lose);
            BigDecimal winLose = win.subtract(lose);

            totalTurnover = totalTurnover.add(turnover);
            totalWin = totalWin.add(win);
            totalLose = totalLose.add(lose);

            raw.setWinAmount(win);
            raw.setLoseAmount(lose);
            raw.setTurnoverAmount(turnover);
            raw.setWinLoseAmount(winLose);

            // No Show All on Domain Report — every process always shows, including 0/0.
            rows.add(raw);
        }

        DomainReportDTO total = new DomainReportDTO();
        total.setTotalRow(true);
        total.setTurnoverAmount(totalTurnover);
        total.setWinAmount(totalWin);
        total.setLoseAmount(totalLose);
        total.setWinLoseAmount(totalWin.subtract(totalLose));
        rows.add(total);

        return rows;
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
}
