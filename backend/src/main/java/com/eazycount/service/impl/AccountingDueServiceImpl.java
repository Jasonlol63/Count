package com.eazycount.service.impl;

import com.eazycount.common.BusinessException;
import com.eazycount.dao.AccountingDueDao;
import com.eazycount.dao.BankProcessDao;
import com.eazycount.dto.AccountingDueDTO;
import com.eazycount.dto.BankProcessDTO;
import com.eazycount.entity.BankProcess;
import com.eazycount.entity.BkProcessAccountingPosted;
import com.eazycount.security.SecurityUtils;
import com.eazycount.security.SessionUser;
import com.eazycount.service.AccountingDueService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class AccountingDueServiceImpl implements AccountingDueService {

    private static final Set<BankProcess.Status> BILLABLE_STATUS = EnumSet.of(
            BankProcess.Status.ACTIVE,
            BankProcess.Status.OFFICIAL,
            BankProcess.Status.E_INVOICE);

    @Autowired
    private BankProcessDao bankProcessDao;

    @Autowired
    private AccountingDueDao accountingDueDao;

    @Override
    public List<AccountingDueDTO> resolveInbox(Integer tenantId, LocalDate asOf, boolean restoreSkipped) {
        SessionUser sessionUser = SecurityUtils.currentUser();
        if (sessionUser == null) {
            throw new BusinessException("Not logged in");
        }
        if (tenantId == null) {
            throw new BusinessException("Invalid Tenant Id!");
        }

        LocalDate today = asOf != null ? asOf : LocalDate.now();
        YearMonth currentMonth = YearMonth.from(today);
        LocalDate monthFirst = currentMonth.atDay(1);
        LocalDate monthEnd = currentMonth.atEndOfMonth();

        List<BankProcessDTO> processes = bankProcessDao.findAllBankProcess(tenantId);
        if (processes == null || processes.isEmpty()) {
            return new ArrayList<>();
        }

        // Generate candidate dues first; their posted dates drive the ledger lookup range.
        // A MONTHLY bill can post in a prior calendar month (e.g. posted 9/19 while viewed in Oct),
        // so the settled/skip range must reach back to the earliest generated posted date.
        List<AccountingDueDTO> dues = new ArrayList<>();
        LocalDate fromDate = monthFirst;
        LocalDate toDate = monthEnd;
        for (BankProcessDTO dto : processes) {
            if (dto == null) {
                continue;
            }
            AccountingDueDTO due = resolveDue(dto, today);
            if (due == null) {
                continue;
            }
            dues.add(due);
            LocalDate posted = due.getPostedDate();
            if (posted != null) {
                if (posted.isBefore(fromDate)) {
                    fromDate = posted;
                }
                if (posted.isAfter(toDate)) {
                    toDate = posted;
                }
            }
        }
        if (dues.isEmpty()) {
            return new ArrayList<>();
        }

        if (restoreSkipped) {
            accountingDueDao.deleteSkippedInRange(tenantId, fromDate, toDate);
        }

        Set<String> settled = loadSettledKeys(tenantId, fromDate, toDate);

        List<AccountingDueDTO> inbox = new ArrayList<>();
        for (AccountingDueDTO due : dues) {
            if (settled.contains(settledKey(due.getBankProcessId(), due.getPostedDate(), due.getPeriodType()))) {
                continue;
            }
            inbox.add(due);
        }
        return inbox;
    }

    @Override
    @Transactional
    public void skipPeriods(List<AccountingDueDTO> items) {
        SessionUser sessionUser = SecurityUtils.currentUser();
        if (sessionUser == null) {
            throw new BusinessException("Not logged in");
        }
        Integer tenantId = sessionUser.tenant_id;
        if (tenantId == null) {
            throw new BusinessException("Invalid Tenant Id!");
        }
        if (items == null || items.isEmpty()) {
            throw new BusinessException("No accounting due items selected!");
        }

        for (AccountingDueDTO item : items) {
            if (item == null) {
                continue;
            }
            skipOnePeriod(tenantId, item, sessionUser.login_id);
        }
    }

    private static BkProcessAccountingPosted.PeriodType parsePeriodType(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException("Period type is required!");
        }
        try {
            return BkProcessAccountingPosted.PeriodType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException("Invalid period type!");
        }
    }

    private static AccountingDueDTO resolveDue(BankProcessDTO dto, LocalDate today) {
        BankProcess bp = dto.getBankProcess();
        if (bp == null || bp.getFrequency() == null) {
            return null;
        }
        switch (bp.getFrequency()) {
            case FIRST_OF_EVERY_MONTH:
                return resolveFirstOfMonthDue(dto, bp, today);
            case MONTHLY:
                return resolveMonthlyDue(dto, bp, today);
            default:
                return null;
        }
    }

    private static AccountingDueDTO resolveFirstOfMonthDue(BankProcessDTO dto, BankProcess bp, LocalDate today) {
        LocalDate dayStart = bp.getDayStart();
        LocalDate dayEnd = bp.getDayEnd();
        if (dayStart == null || dayEnd == null) {
            return null;
        }
        if (today.isBefore(dayStart) || today.isAfter(dayEnd)) {
            return null;
        }
        if (bp.getStatus() == null || !BILLABLE_STATUS.contains(bp.getStatus())) {
            return null;
        }

        YearMonth currentMonth = YearMonth.from(today);
        YearMonth startMonth = YearMonth.from(dayStart);
        YearMonth endMonth = YearMonth.from(dayEnd);
        if (currentMonth.isBefore(startMonth) || currentMonth.isAfter(endMonth)) {
            return null;
        }

        LocalDate monthFirst = currentMonth.atDay(1);
        LocalDate monthEnd = currentMonth.atEndOfMonth();

        LocalDate postedDate;
        LocalDate billingStart;
        LocalDate billingEnd;
        BkProcessAccountingPosted.PeriodType periodType;

        if (currentMonth.equals(startMonth)) {
            billingStart = dayStart;
            billingEnd = dayEnd.isBefore(monthEnd) ? dayEnd : monthEnd;
            postedDate = dayStart;
            periodType = dayStart.getDayOfMonth() == 1
                    ? BkProcessAccountingPosted.PeriodType.FIRST_MONTH
                    : BkProcessAccountingPosted.PeriodType.PARTIAL_FIRST_MONTH;
        } else if (currentMonth.equals(endMonth) && dayEnd.getDayOfMonth() < monthEnd.getDayOfMonth()) {
            billingStart = monthFirst;
            billingEnd = dayEnd;
            postedDate = monthFirst;
            periodType = BkProcessAccountingPosted.PeriodType.DAY_END_TAIL;
        } else {
            billingStart = monthFirst;
            billingEnd = currentMonth.equals(endMonth) ? dayEnd : monthEnd;
            postedDate = monthFirst;
            periodType = BkProcessAccountingPosted.PeriodType.FULL_MONTH;
        }

        return buildDue(dto, bp, postedDate, billingStart, billingEnd, periodType);
    }

    private static AccountingDueDTO resolveMonthlyDue(BankProcessDTO dto, BankProcess bp, LocalDate today) {
        LocalDate dayStart = bp.getDayStart();
        LocalDate dayEnd = bp.getDayEnd();
        if (dayStart == null || dayEnd == null) {
            return null;
        }
        if (bp.getStatus() == null || !BILLABLE_STATUS.contains(bp.getStatus())) {
            return null;
        }

        // Real contract end = last posted date (dayEnd) plus one full billing month.
        LocalDate contractEnd = dayEnd.plusMonths(1);
        if (today.isBefore(dayStart) || today.isAfter(contractEnd)) {
            return null;
        }

        // Current active bill = the latest posted anchor on or before today; a bill stays visible
        // for its whole billing period, and the last bill lasts until contractEnd.
        LocalDate postedDate = currentMonthlyPosted(dayStart, dayEnd, today);
        if (postedDate == null) {
            return null;
        }

        LocalDate billingStart = postedDate;
        LocalDate billingEnd = postedDate.plusMonths(1);

        return buildDue(dto, bp, postedDate, billingStart, billingEnd,
                BkProcessAccountingPosted.PeriodType.MONTHLY);
    }

    private static LocalDate currentMonthlyPosted(LocalDate dayStart, LocalDate dayEnd, LocalDate today) {
        LocalDate active = null;
        LocalDate posted = dayStart;
        YearMonth month = YearMonth.from(dayStart);
        YearMonth endMonth = YearMonth.from(dayEnd);
        while (true) {
            if (posted.isAfter(dayEnd)) {
                posted = dayEnd;
            }
            if (posted.isAfter(today)) {
                break;
            }
            active = posted;
            if (!month.isBefore(endMonth)) {
                break;
            }
            month = month.plusMonths(1);
            posted = monthlyAnchor(month, dayStart);
        }
        return active;
    }

    private static LocalDate monthlyAnchor(YearMonth month, LocalDate dayStart) {
        int anchorDay = dayStart.getDayOfMonth() - 1;
        if (anchorDay <= 0) {
            return month.atEndOfMonth();
        }
        return month.atDay(Math.min(anchorDay, month.lengthOfMonth()));
    }

    private static AccountingDueDTO buildDue(BankProcessDTO dto,
                                             BankProcess bp,
                                             LocalDate postedDate,
                                             LocalDate billingStart,
                                             LocalDate billingEnd,
                                             BkProcessAccountingPosted.PeriodType periodType) {
        AccountingDueDTO due = new AccountingDueDTO();
        due.setBankProcessId(bp.getId());
        due.setTenantId(bp.getTenantId());
        due.setPostedDate(postedDate);
        due.setPeriodType(periodType.name());
        due.setBillingStart(billingStart);
        due.setBillingEnd(billingEnd);

        due.setCountryCode(dto.getCountryCode());
        due.setBankName(dto.getBankName());
        due.setCardOwner(bp.getCardOwner());
        due.setCardOwnerType(bp.getCardOwnerType());
        due.setFrequency(bp.getFrequency() != null ? bp.getFrequency().name() : null);
        due.setDayStart(bp.getDayStart());
        due.setDayEnd(bp.getDayEnd());
        due.setContract(bp.getContract());
        due.setStatus(dto.getStatus() != null ? dto.getStatus()
                : (bp.getStatus() != null ? bp.getStatus().name() : null));

        due.setSupplierAccountCode(dto.getSupplierAccountCode());
        due.setSupplierAccountName(dto.getSupplierAccountName());
        due.setSupplierPrice(bp.getSupplierPrice());
        due.setCustomerAccountCode(dto.getCustomerAccountCode());
        due.setCustomerAccountName(dto.getCustomerAccountName());
        due.setCustomerPrice(bp.getCustomerPrice());
        due.setCompanyAccountCode(dto.getCompanyAccountCode());
        due.setCompanyAccountName(dto.getCompanyAccountName());
        due.setCompanyPrice(bp.getCompanyPrice());
        return due;
    }

    private void skipOnePeriod(Integer tenantId, AccountingDueDTO item, String createdBy) {
        Integer bankProcessId = item.getBankProcessId();
        LocalDate postedDate = item.getPostedDate();
        if (bankProcessId == null || bankProcessId <= 0) {
            throw new BusinessException("Invalid bank process ID!");
        }
        if (postedDate == null) {
            throw new BusinessException("Posted date is required!");
        }

        BkProcessAccountingPosted.PeriodType periodType = parsePeriodType(item.getPeriodType());

        BankProcess bankProcess = bankProcessDao.findBKProcessByIdAndTenantId(bankProcessId, tenantId);
        if (bankProcess == null) {
            throw new BusinessException("Bank process not found!");
        }

        BkProcessAccountingPosted existing = accountingDueDao.findLedgerEntry(
                tenantId, bankProcessId, postedDate, periodType);
        if (existing != null) {
            if (existing.getOutcome() == BkProcessAccountingPosted.Outcome.POSTED) {
                throw new BusinessException("Accounting due already posted!");
            }
            if (existing.getOutcome() == BkProcessAccountingPosted.Outcome.SKIPPED) {
                throw new BusinessException("Accounting due already skipped!");
            }
        }

        BkProcessAccountingPosted row = new BkProcessAccountingPosted();
        row.setTenantId(tenantId);
        row.setBankProcessId(bankProcessId);
        row.setPostedDate(postedDate);
        row.setPeriodType(periodType);
        row.setOutcome(BkProcessAccountingPosted.Outcome.SKIPPED);
        row.setBillingStart(item.getBillingStart());
        row.setBillingEnd(item.getBillingEnd());
        row.setTransactionId(null);
        row.setCreatedBy(createdBy);
        accountingDueDao.insertLedgerEntry(row);
    }

    private Set<String> loadSettledKeys(Integer tenantId, LocalDate fromDate, LocalDate toDate) {
        List<BkProcessAccountingPosted> rows = accountingDueDao.findSettledPeriods(tenantId, fromDate, toDate);
        Set<String> keys = new HashSet<>();
        if (rows == null) {
            return keys;
        }
        for (BkProcessAccountingPosted row : rows) {
            if (row == null) {
                continue;
            }
            String periodType = row.getPeriodType() != null ? row.getPeriodType().name() : null;
            keys.add(settledKey(row.getBankProcessId(), row.getPostedDate(), periodType));
        }
        return keys;
    }

    private static String settledKey(Integer bankProcessId, LocalDate postedDate, String periodType) {
        return bankProcessId + "|" + postedDate + "|" + periodType;
    }
}
