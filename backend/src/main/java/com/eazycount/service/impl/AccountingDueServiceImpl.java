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
    @Transactional
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
            // Once: dayStart before contract creation month → skip and mark inactive.
            expireOnceIfBeforeCreationMonth(dto);
            List<AccountingDueDTO> resolved = resolveDues(dto, today);
            if (resolved == null || resolved.isEmpty()) {
                continue;
            }
            for (AccountingDueDTO due : resolved) {
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
    
    private static List<AccountingDueDTO> resolveDues(BankProcessDTO dto, LocalDate today) {
        BankProcess bp = dto.getBankProcess();
        if (bp == null || bp.getFrequency() == null) {
            return List.of();
        }
        switch (bp.getFrequency()) {
            case FIRST_OF_EVERY_MONTH:
                return resolveFirstOfMonthDues(dto, bp, today);
            case MONTHLY:
                return resolveMonthlyDues(dto, bp, today);
            case ONCE: {
                AccountingDueDTO due = resolveOnceDue(dto, bp, today);
                return due == null ? List.of() : List.of(due);
            }
            case WEEK:
                return resolveWeeklyDues(dto, bp, today);
            case DAY:
                return resolveDailyDues(dto, bp, today);
            default:
                return List.of();
        }
    }

    private static List<AccountingDueDTO> resolveFirstOfMonthDues(BankProcessDTO dto, BankProcess bp, LocalDate today) {
        LocalDate dayStart = bp.getDayStart();
        LocalDate dayEnd = bp.getDayEnd();
        if (dayStart == null || dayEnd == null) {
            return List.of();
        }
        if (today.isBefore(dayStart)) {
            return List.of();
        }
        if (bp.getStatus() == null || !BILLABLE_STATUS.contains(bp.getStatus())) {
            return List.of();
        }

        YearMonth startMonth = YearMonth.from(dayStart);
        YearMonth endMonth = YearMonth.from(dayEnd);
        YearMonth creationMonth = YearMonth.from(creationMonthFloor(bp, dayStart));
        YearMonth loopStart = startMonth.isBefore(creationMonth) ? creationMonth : startMonth;

        List<AccountingDueDTO> dues = new ArrayList<>();
        for (YearMonth month = loopStart; !month.isAfter(endMonth); month = month.plusMonths(1)) {
            AccountingDueDTO due = buildFirstOfMonthDueForMonth(dto, bp, month, dayStart, dayEnd);
            if (due == null || due.getPostedDate() == null) {
                continue;
            }
            if (due.getPostedDate().isAfter(today)) {
                continue;
            }
            dues.add(due);
        }
        return dues;
    }

    private static AccountingDueDTO buildFirstOfMonthDueForMonth(BankProcessDTO dto,BankProcess bp,YearMonth month,LocalDate dayStart, LocalDate dayEnd) {
        YearMonth startMonth = YearMonth.from(dayStart);
        YearMonth endMonth = YearMonth.from(dayEnd);
        LocalDate monthFirst = month.atDay(1);
        LocalDate monthEnd = month.atEndOfMonth();
        // ON = DAY_END_TAIL through dayEnd; OFF = FULL_MONTH through month end.
        boolean useDayEndTail = Boolean.TRUE.equals(bp.getDayEndMonthlyCapEnabled());

        LocalDate postedDate;
        LocalDate billingStart;
        LocalDate billingEnd;
        BkProcessAccountingPosted.PeriodType periodType;

        if (month.equals(startMonth)) {
            billingStart = dayStart;
            if (month.equals(endMonth) && !useDayEndTail) {
                billingEnd = monthEnd;
            } else {
                billingEnd = dayEnd.isBefore(monthEnd) ? dayEnd : monthEnd;
            }
            postedDate = dayStart;
            periodType = dayStart.getDayOfMonth() == 1
                    ? BkProcessAccountingPosted.PeriodType.FIRST_MONTH
                    : BkProcessAccountingPosted.PeriodType.PARTIAL_FIRST_MONTH;
        } else if (month.equals(endMonth)
                && useDayEndTail
                && dayEnd.getDayOfMonth() < monthEnd.getDayOfMonth()) {
            billingStart = monthFirst;
            billingEnd = dayEnd;
            postedDate = monthFirst;
            periodType = BkProcessAccountingPosted.PeriodType.DAY_END_TAIL;
        } else {
            billingStart = monthFirst;
            billingEnd = monthEnd;
            postedDate = monthFirst;
            periodType = BkProcessAccountingPosted.PeriodType.FULL_MONTH;
        }

        return buildDue(dto, bp, postedDate, billingStart, billingEnd, periodType);
    }

    private static List<AccountingDueDTO> resolveMonthlyDues(BankProcessDTO dto, BankProcess bp, LocalDate today) {
        LocalDate dayStart = bp.getDayStart();
        LocalDate dayEnd = bp.getDayEnd();
        if (dayStart == null || dayEnd == null) {
            return List.of();
        }
        if (bp.getStatus() == null || !BILLABLE_STATUS.contains(bp.getStatus())) {
            return List.of();
        }
        if (today.isBefore(dayStart)) {
            return List.of();
        }

        LocalDate creationFloor = creationMonthFloor(bp, dayStart);
        YearMonth creationMonth = YearMonth.from(creationFloor);
        YearMonth startMonth = YearMonth.from(dayStart);
        YearMonth endMonth = YearMonth.from(dayEnd);
        // Created in July with dayStart in June → skip June anchor, start from July.
        YearMonth month = startMonth.isBefore(creationMonth) ? creationMonth : startMonth;
        LocalDate posted = month.equals(startMonth) ? dayStart : monthlyAnchor(month, dayStart);

        List<AccountingDueDTO> dues = new ArrayList<>();
        while (true) {
            LocalDate periodPosted = posted.isAfter(dayEnd) ? dayEnd : posted;
            if (periodPosted.isAfter(today)) {
                break;
            }
            if (!periodPosted.isBefore(creationFloor)) {
                dues.add(buildDue(dto, bp, periodPosted, periodPosted, periodPosted.plusMonths(1),
                        BkProcessAccountingPosted.PeriodType.MONTHLY));
            }
            if (!month.isBefore(endMonth)) {
                break;
            }
            month = month.plusMonths(1);
            posted = monthlyAnchor(month, dayStart);
        }
        return dues;
    }

    private static AccountingDueDTO resolveOnceDue(BankProcessDTO dto, BankProcess bp, LocalDate today) {
        LocalDate dayStart = bp.getDayStart();
        if (dayStart == null) {
            return null;
        }
        if (bp.getStatus() == null || !BILLABLE_STATUS.contains(bp.getStatus())) {
            return null;
        }
        // Non-month skip: dayStart strictly before creation month → never enter Due.
        LocalDate creationFloor = creationMonthFloor(bp, dayStart);
        if (dayStart.isBefore(creationFloor)) {
            return null;
        }
        if (today.isBefore(dayStart)) {
            return null;
        }
        // Once due, stay until POSTED/SKIPPED even after calendar month rolls.
        return buildDue(dto, bp, dayStart, dayStart, dayStart,
                BkProcessAccountingPosted.PeriodType.ONCE_ONE_OFF);
    }

    private void expireOnceIfBeforeCreationMonth(BankProcessDTO dto) {
        BankProcess bp = dto.getBankProcess();
        if (bp == null || bp.getFrequency() != BankProcess.Frequency.ONCE) {
            return;
        }
        LocalDate dayStart = bp.getDayStart();
        if (dayStart == null || bp.getId() == null || bp.getTenantId() == null) {
            return;
        }
        if (bp.getStatus() == null || !BILLABLE_STATUS.contains(bp.getStatus())) {
            return;
        }
        LocalDate creationFloor = creationMonthFloor(bp, dayStart);
        if (!dayStart.isBefore(creationFloor)) {
            return;
        }
        bankProcessDao.updateStatus(bp.getId(), bp.getTenantId(), BankProcess.Status.INACTIVE);
        bp.setStatus(BankProcess.Status.INACTIVE);
        dto.setStatus(BankProcess.Status.INACTIVE.name());
    }


    private static LocalDate creationMonthFloor(BankProcess bp, LocalDate dayStart) {
        if (bp.getCreatedAt() != null) {
            return YearMonth.from(bp.getCreatedAt()).atDay(1);
        }
        return YearMonth.from(dayStart).atDay(1);
    }

    private static List<AccountingDueDTO> resolveWeeklyDues(BankProcessDTO dto, BankProcess bp, LocalDate today) {
        LocalDate dayStart = bp.getDayStart();
        if (dayStart == null) {
            return List.of();
        }
        if (bp.getStatus() == null || !BILLABLE_STATUS.contains(bp.getStatus())) {
            return List.of();
        }
        if (today.isBefore(dayStart)) {
            return List.of();
        }

        // Non-month skip floor = contract creation month (not rolling "today" month).
        // Periods entirely before that month are omitted; once a period is due it stays
        // until POSTED/SKIPPED even after the calendar month rolls.
        LocalDate creationFloor = creationMonthFloor(bp, dayStart);
        LocalDate posted = dayStart;
        LocalDate billingEnd = dayStart.plusDays(6);
        while (billingEnd.isBefore(creationFloor)) {
            posted = billingEnd.plusDays(1);
            billingEnd = posted.plusDays(6);
        }

        List<AccountingDueDTO> dues = new ArrayList<>();
        while (!posted.isAfter(today)) {
            LocalDate billingStart = posted;
            // Keep if period touches creation month or any later month (not pure pre-creation).
            if (!billingEnd.isBefore(creationFloor)) {
                dues.add(buildDue(dto, bp, posted, billingStart, billingEnd,
                        BkProcessAccountingPosted.PeriodType.WEEKLY));
            }
            posted = billingEnd.plusDays(1);
            billingEnd = posted.plusDays(6);
        }
        return dues;
    }

    private static List<AccountingDueDTO> resolveDailyDues(BankProcessDTO dto, BankProcess bp, LocalDate today) {
        LocalDate dayStart = bp.getDayStart();
        if (dayStart == null) {
            return List.of();
        }
        if (bp.getStatus() == null || !BILLABLE_STATUS.contains(bp.getStatus())) {
            return List.of();
        }
        if (today.isBefore(dayStart)) {
            return List.of();
        }

        LocalDate creationFloor = creationMonthFloor(bp, dayStart);
        LocalDate from = dayStart.isAfter(creationFloor) ? dayStart : creationFloor;
        if (from.isAfter(today)) {
            return List.of();
        }

        List<AccountingDueDTO> dues = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(today); d = d.plusDays(1)) {
            dues.add(buildDue(dto, bp, d, d, d, BkProcessAccountingPosted.PeriodType.DAILY));
        }
        return dues;
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
