package com.eazycount.service.impl;

import com.eazycount.common.BusinessException;
import com.eazycount.dao.AccountingDueDao;
import com.eazycount.dao.BankCountryOptionDao;
import com.eazycount.dao.BankProcessDao;
import com.eazycount.dao.CurrencyDao;
import com.eazycount.dao.TransactionDao;
import com.eazycount.dto.AccountingDueDTO;
import com.eazycount.dto.BankProcessDTO;
import com.eazycount.entity.BankCountry;
import com.eazycount.entity.BankOption;
import com.eazycount.entity.BankProcess;
import com.eazycount.entity.BankProcessShare;
import com.eazycount.entity.BkProcessAccountingPosted;
import com.eazycount.entity.Currency;
import com.eazycount.entity.Transaction;
import com.eazycount.security.SecurityUtils;
import com.eazycount.security.SessionUser;
import com.eazycount.service.AccountingDueService;
import com.eazycount.service.BankProcessResendService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class AccountingDueServiceImpl implements AccountingDueService {

    private static final Set<BankProcess.Status> BILLABLE_STATUS = EnumSet.of(
            BankProcess.Status.ACTIVE,
            BankProcess.Status.OFFICIAL,
            BankProcess.Status.E_INVOICE);

    private static final Set<BkProcessAccountingPosted.PeriodType> FIRST_OF_MONTH_POST_TYPES = EnumSet.of(
            BkProcessAccountingPosted.PeriodType.FIRST_MONTH,
            BkProcessAccountingPosted.PeriodType.PARTIAL_FIRST_MONTH,
            BkProcessAccountingPosted.PeriodType.FULL_MONTH,
            BkProcessAccountingPosted.PeriodType.DAY_END_TAIL);

    private static final int MONEY_SCALE = 2;
    private static final RoundingMode MONEY_ROUNDING = RoundingMode.DOWN;

    @Autowired
    private BankProcessDao bankProcessDao;

    @Autowired
    private AccountingDueDao accountingDueDao;

    @Autowired
    private BankProcessResendService bankProcessResendService;

    @Autowired
    private TransactionDao transactionDao;

    @Autowired
    private CurrencyDao currencyDao;

    @Autowired
    private BankCountryOptionDao bankCountryOptionDao;

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
            List<AccountingDueDTO> resolved = resolveDues(dto, today, tenantId);
            if (resolved != null) {
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
            AccountingDueDTO resendDue = bankProcessResendService.resolveOpenMakeUp(dto, today);
            if (resendDue != null) {
                dues.add(resendDue);
                LocalDate posted = resendDue.getPostedDate();
                if (posted != null) {
                    if (posted.isBefore(fromDate)) {
                        fromDate = posted;
                    }
                    if (posted.isAfter(toDate)) {
                        toDate = posted;
                    }
                }
            }
            AccountingDueDTO compensationDue = resolveOnePlusCompensationDue(dto, tenantId);
            if (compensationDue != null) {
                dues.add(compensationDue);
                LocalDate posted = compensationDue.getPostedDate();
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

    @Override
    @Transactional
    public int postToTransaction(List<AccountingDueDTO> items) {
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

        String createdBy = sessionUser.login_id;
        int createdCount = 0;
        Set<String> seen = new HashSet<>();
        for (AccountingDueDTO item : items) {
            if (item == null) {
                continue;
            }
            String key = settledKey(item.getBankProcessId(), item.getPostedDate(), item.getPeriodType());
            if (!seen.add(key)) {
                continue;
            }
            createdCount += postOneAccountingDuePeriod(tenantId, item, createdBy);
        }
        return createdCount;
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
    
    private List<AccountingDueDTO> resolveDues(BankProcessDTO dto, LocalDate today, Integer tenantId) {
        BankProcess bp = dto.getBankProcess();
        if (bp == null || bp.getFrequency() == null) {
            return List.of();
        }
        if (!isBillableForDueGeneration(bp, tenantId)) {
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

    /**
     * Normal dues: ACTIVE / OFFICIAL / E_INVOICE.
     * 1+N + INACTIVE/BLOCK with no normal POSTED yet: still generate so Case A first bill can be posted as compensation.
     * After any normal POSTED, INACTIVE/BLOCK stop normal dues (Case B COMPENSATION only).
     */
    private boolean isBillableForDueGeneration(BankProcess bp, Integer tenantId) {
        if (bp.getStatus() == null) {
            return false;
        }
        if (BILLABLE_STATUS.contains(bp.getStatus())) {
            return true;
        }
        if (!isOnePlusContract(bp.getContract())) {
            return false;
        }
        if (bp.getStatus() != BankProcess.Status.INACTIVE
                && bp.getStatus() != BankProcess.Status.BLOCK) {
            return false;
        }
        if (bp.getId() == null || tenantId == null) {
            return false;
        }
        return accountingDueDao.countNormalPosted(tenantId, bp.getId()) <= 0;
    }

    /**
     * Case B: 1+N contract, status ≠ ACTIVE, at least one normal POSTED, no settled COMPENSATION yet.
     * Ledger anchor = dayStart + COMPENSATION.
     */
    private AccountingDueDTO resolveOnePlusCompensationDue(BankProcessDTO dto, Integer tenantId) {
        BankProcess bp = dto.getBankProcess();
        if (bp == null || bp.getId() == null || bp.getDayStart() == null) {
            return null;
        }
        if (!isOnePlusContract(bp.getContract())) {
            return null;
        }
        if (bp.getStatus() == null || bp.getStatus() == BankProcess.Status.ACTIVE) {
            return null;
        }
        if (accountingDueDao.countNormalPosted(tenantId, bp.getId()) <= 0) {
            return null;
        }
        // Case A already wrote COMPENSATION txns — settle COMPENSATION slot and do not re-list.
        if (accountingDueDao.countCompensationTransactions(tenantId, bp.getId()) > 0) {
            settleCompensationSlot(tenantId, bp, null);
            return null;
        }
        return buildDue(dto, bp, bp.getDayStart(), bp.getDayStart(), bp.getDayStart(),
                BkProcessAccountingPosted.PeriodType.COMPENSATION);
    }

    private static boolean isOnePlusContract(String contract) {
        if (contract == null || contract.isBlank()) {
            return false;
        }
        return contract.trim().matches("(?i)1\\+[123]");
    }

    /** 1+1 → 1, 1+2 → 2, 1+3 → 3; others → 0 (not a compensation contract). */
    private static int compensationMultiplier(String contract) {
        if (contract == null || contract.isBlank()) {
            return 0;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)^1\\+([123])$")
                .matcher(contract.trim());
        if (!m.matches()) {
            return 0;
        }
        return Integer.parseInt(m.group(1));
    }

    private static String compensationMonthLabel(String contract) {
        return switch (compensationMultiplier(contract)) {
            case 1 -> "ONE MONTH";
            case 2 -> "TWO MONTH";
            case 3 -> "THREE MONTH";
            default -> throw new BusinessException("Invalid 1+N compensation contract!");
        };
    }

    private static boolean isCompensationPost(BankProcess bankProcess, BkProcessAccountingPosted.PeriodType periodType) {
        if (periodType == BkProcessAccountingPosted.PeriodType.COMPENSATION) {
            return true;
        }
        // Make-up bills are never compensation, even if status later becomes non-ACTIVE.
        if (periodType == BkProcessAccountingPosted.PeriodType.RESEND_CONSOLIDATED) {
            return false;
        }
        return isOnePlusContract(bankProcess.getContract())
                && bankProcess.getStatus() != null
                && bankProcess.getStatus() != BankProcess.Status.ACTIVE;
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
        row.setBillingEnd(item.getBillingEnd());row.setCreatedBy(createdBy);
        accountingDueDao.insertLedgerEntry(row);

        if (bankProcess.getFrequency() == BankProcess.Frequency.ONCE
                && periodType == BkProcessAccountingPosted.PeriodType.ONCE_ONE_OFF
                && bankProcess.getStatus() != BankProcess.Status.INACTIVE) {
            bankProcessDao.updateStatus(bankProcessId, tenantId, BankProcess.Status.INACTIVE);
            bankProcess.setStatus(BankProcess.Status.INACTIVE);
        }

        if (periodType == BkProcessAccountingPosted.PeriodType.RESEND_CONSOLIDATED
                && bankProcess.getResendScheduleDayStart() != null
                && bankProcess.getResendScheduleDayStart().equals(postedDate)) {
            bankProcessResendService.clearOpenSchedule(bankProcessId, tenantId);
        }
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

    private int postOneAccountingDuePeriod(Integer tenantId, AccountingDueDTO item, String createdBy) {
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
        if (periodType != BkProcessAccountingPosted.PeriodType.RESEND_CONSOLIDATED
                && bankProcess.getFrequency() == null) {
            throw new BusinessException("Bank process frequency is required!");
        }
        if (!isPostAllowed(bankProcess, periodType)) {
            throw new BusinessException("Bank process is not billable!");
        }

        LocalDate billingStart = item.getBillingStart();
        LocalDate billingEnd = item.getBillingEnd();
        if (billingStart == null || billingEnd == null) {
            throw new BusinessException("Billing period is required!");
        }
        if (billingEnd.isBefore(billingStart)) {
            throw new BusinessException("Invalid billing period!");
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

        if (bankProcess.getSupplierAccountId() == null
                || bankProcess.getCustomerAccountId() == null
                || bankProcess.getCompanyAccountId() == null) {
            throw new BusinessException("Supplier, customer and company accounts are required!");
        }

        if (periodType == BkProcessAccountingPosted.PeriodType.COMPENSATION) {
            return postCompensationPeriod(
                    tenantId, bankProcess, periodType, postedDate, billingStart, billingEnd, createdBy);
        }
        if (periodType == BkProcessAccountingPosted.PeriodType.RESEND_CONSOLIDATED) {
            return postResendConsolidatedPeriod(
                    tenantId, bankProcess, periodType, postedDate, billingStart, billingEnd, createdBy);
        }

        return switch (bankProcess.getFrequency()) {
            case FIRST_OF_EVERY_MONTH -> postFirstOfEveryMonthPeriod(tenantId, bankProcess, periodType, postedDate, billingStart, billingEnd, createdBy);
            case MONTHLY -> postMonthlyPeriod(tenantId, bankProcess, periodType, postedDate, billingStart, billingEnd, createdBy);
            case WEEK -> postWeeklyPeriod(tenantId, bankProcess, periodType, postedDate, billingStart, billingEnd, createdBy);
            case DAY -> postDayPeriod(tenantId, bankProcess, periodType, postedDate, billingStart, billingEnd, createdBy);
            case ONCE -> postOncePeriod(tenantId, bankProcess, periodType, postedDate, billingStart, billingEnd, createdBy);
        };
    }

    private static boolean isPostAllowed(BankProcess bankProcess, BkProcessAccountingPosted.PeriodType periodType) {
        if (bankProcess.getStatus() == null) {
            return false;
        }
        if (periodType == BkProcessAccountingPosted.PeriodType.COMPENSATION) {
            return isOnePlusContract(bankProcess.getContract())
                    && bankProcess.getStatus() != BankProcess.Status.ACTIVE;
        }
        if (BILLABLE_STATUS.contains(bankProcess.getStatus())) {
            return true;
        }
        // Case A: 1+N dues posted while already non-ACTIVE (e.g. INACTIVE/BLOCK).
        return isOnePlusContract(bankProcess.getContract())
                && bankProcess.getStatus() != BankProcess.Status.ACTIVE;
    }

    /**
     * Resend make-up post ({@code RESEND_CONSOLIDATED}): same amount/desc rules as the make-up
     * frequency, using the user-chosen billing window. Once make-up does not change status.
     */
    private int postResendConsolidatedPeriod(Integer tenantId, BankProcess bankProcess, BkProcessAccountingPosted.PeriodType periodType, LocalDate postedDate, LocalDate billingStart, LocalDate billingEnd, String createdBy) {
        if (periodType != BkProcessAccountingPosted.PeriodType.RESEND_CONSOLIDATED) {
            throw new BusinessException("Invalid resend period type!");
        }
        BankProcess.Frequency frequency = resolveResendPostFrequency(bankProcess);
        BigDecimal ratio = switch (frequency) {
            case FIRST_OF_EVERY_MONTH -> resolveResendFirstOfMonthAmountRatio(billingStart, billingEnd);
            case MONTHLY, WEEK, DAY, ONCE -> BigDecimal.ONE;
        };
        int created = writePostedTransactions(
                tenantId, bankProcess, periodType, postedDate, billingStart, billingEnd, createdBy, ratio);
        // Open make-up is consumed (same as Skip).
        if (bankProcess.getResendScheduleDayStart() != null
                && bankProcess.getResendScheduleDayStart().equals(postedDate)) {
            bankProcessResendService.clearOpenSchedule(bankProcess.getId(), tenantId);
        }
        return created;
    }

    /** Prefer open make-up frequency; fall back to process frequency. */
    private static BankProcess.Frequency resolveResendPostFrequency(BankProcess bankProcess) {
        if (bankProcess.getResendScheduleFrequency() != null) {
            return bankProcess.getResendScheduleFrequency();
        }
        if (bankProcess.getFrequency() != null) {
            return bankProcess.getFrequency();
        }
        throw new BusinessException("Resend frequency is required!");
    }

    /**
     * 1st Resend amount: split user window by calendar month, sum segment ratios
     * (partial = days/days-in-month, full month = 1). One Due / one Post.
     */
    static BigDecimal resolveResendFirstOfMonthAmountRatio(LocalDate billingStart, LocalDate billingEnd) {
        if (billingStart == null || billingEnd == null || billingEnd.isBefore(billingStart)) {
            throw new BusinessException("Invalid billing period!");
        }
        BigDecimal total = BigDecimal.ZERO;
        YearMonth cursor = YearMonth.from(billingStart);
        YearMonth last = YearMonth.from(billingEnd);
        while (!cursor.isAfter(last)) {
            LocalDate monthStart = cursor.atDay(1);
            LocalDate monthEnd = cursor.atEndOfMonth();
            LocalDate segStart = billingStart.isAfter(monthStart) ? billingStart : monthStart;
            LocalDate segEnd = billingEnd.isBefore(monthEnd) ? billingEnd : monthEnd;
            long inclusiveDays = ChronoUnit.DAYS.between(segStart, segEnd) + 1;
            if (inclusiveDays < 1) {
                throw new BusinessException("Invalid billing period!");
            }
            int daysInMonth = cursor.lengthOfMonth();
            total = total.add(BigDecimal.valueOf(inclusiveDays)
                    .divide(BigDecimal.valueOf(daysInMonth), 16, RoundingMode.HALF_UP));
            cursor = cursor.plusMonths(1);
        }
        return total;
    }

    /** Case B: dedicated compensation due (COMPENSATION), full amount × 1/2/3, txn date = today. */
    private int postCompensationPeriod(Integer tenantId, BankProcess bankProcess, BkProcessAccountingPosted.PeriodType periodType, LocalDate postedDate, LocalDate billingStart, LocalDate billingEnd, String createdBy) {
        if (periodType != BkProcessAccountingPosted.PeriodType.COMPENSATION) {
            throw new BusinessException("Invalid compensation period type!");
        }
        if (!isOnePlusContract(bankProcess.getContract())) {
            throw new BusinessException("Compensation posting requires a 1+1 / 1+2 / 1+3 contract!");
        }
        if (bankProcess.getStatus() == BankProcess.Status.ACTIVE) {
            throw new BusinessException("Compensation posting requires non-ACTIVE status!");
        }
        return writePostedTransactions(
                tenantId, bankProcess, periodType, postedDate, billingStart, billingEnd, createdBy, BigDecimal.ONE);
    }

    /** 1st of every month: full / first = 1; partial / day-end tail = days / days-in-month. */
    private int postFirstOfEveryMonthPeriod(Integer tenantId, BankProcess bankProcess, BkProcessAccountingPosted.PeriodType periodType, LocalDate postedDate, LocalDate billingStart, LocalDate billingEnd, String createdBy) {
        if (!FIRST_OF_MONTH_POST_TYPES.contains(periodType)) {
            throw new BusinessException("Invalid period type for 1st of every month frequency!");
        }
        BigDecimal ratio = resolveFirstOfMonthAmountRatio(periodType, billingStart, billingEnd);
        return writePostedTransactions(
                tenantId, bankProcess, periodType, postedDate, billingStart, billingEnd, createdBy, ratio);
    }

    /** Monthly: always full Buy / Sell / Profit / optional PS (no proration). */
    private int postMonthlyPeriod(Integer tenantId, BankProcess bankProcess, BkProcessAccountingPosted.PeriodType periodType, LocalDate postedDate, LocalDate billingStart, LocalDate billingEnd, String createdBy) {
        if (periodType != BkProcessAccountingPosted.PeriodType.MONTHLY) {
            throw new BusinessException("Only monthly period type is supported for monthly frequency!");
        }
        return writePostedTransactions(
                tenantId, bankProcess, periodType, postedDate, billingStart, billingEnd, createdBy, BigDecimal.ONE);
    }

    private int postOncePeriod(Integer tenantId, BankProcess bankProcess, BkProcessAccountingPosted.PeriodType periodType, LocalDate postedDate, LocalDate billingStart, LocalDate billingEnd, String createdBy) {
        if (periodType != BkProcessAccountingPosted.PeriodType.ONCE_ONE_OFF) {
            throw new BusinessException("Only once period type is supported for once frequency!");
        }
        int created = writePostedTransactions(
                tenantId, bankProcess, periodType, postedDate, billingStart, billingEnd, createdBy, BigDecimal.ONE);
        // Once is a one-off bill: settle → stop further Due generation.
        if (bankProcess.getStatus() != BankProcess.Status.INACTIVE) {
            bankProcessDao.updateStatus(bankProcess.getId(), tenantId, BankProcess.Status.INACTIVE);
            bankProcess.setStatus(BankProcess.Status.INACTIVE);
        }
        return created;
    }

    /** Week: always full Buy / Sell / Profit / optional PS (no proration). */
    private int postWeeklyPeriod(Integer tenantId, BankProcess bankProcess, BkProcessAccountingPosted.PeriodType periodType, LocalDate postedDate, LocalDate billingStart, LocalDate billingEnd, String createdBy) {
        if (periodType != BkProcessAccountingPosted.PeriodType.WEEKLY) {
            throw new BusinessException("Only weekly period type is supported for week frequency!");
        }
        return writePostedTransactions(
                tenantId, bankProcess, periodType, postedDate, billingStart, billingEnd, createdBy, BigDecimal.ONE);
    }

    /** Day: always full Buy / Sell / Profit / optional PS (no proration). */
    private int postDayPeriod(Integer tenantId, BankProcess bankProcess, BkProcessAccountingPosted.PeriodType periodType, LocalDate postedDate, LocalDate billingStart, LocalDate billingEnd, String createdBy) {
        if (periodType != BkProcessAccountingPosted.PeriodType.DAILY) {
            throw new BusinessException("Only daily period type is supported for day frequency!");
        }
        return writePostedTransactions(
                tenantId, bankProcess, periodType, postedDate, billingStart, billingEnd, createdBy, BigDecimal.ONE);
    }

    /**
     * Full month / first month on the 1st → ratio 1.
     * Partial first month / day-end tail → inclusive days / days in that calendar month.
     */
    static BigDecimal resolveFirstOfMonthAmountRatio(
            BkProcessAccountingPosted.PeriodType periodType,
            LocalDate billingStart,
            LocalDate billingEnd) {
        if (periodType == BkProcessAccountingPosted.PeriodType.PARTIAL_FIRST_MONTH
                || periodType == BkProcessAccountingPosted.PeriodType.DAY_END_TAIL) {
            long inclusiveDays = ChronoUnit.DAYS.between(billingStart, billingEnd) + 1;
            if (inclusiveDays < 1) {
                throw new BusinessException("Invalid billing period!");
            }
            int daysInMonth = YearMonth.from(billingStart).lengthOfMonth();
            if (daysInMonth <= 0) {
                throw new BusinessException("Invalid billing month!");
            }
            return BigDecimal.valueOf(inclusiveDays)
                    .divide(BigDecimal.valueOf(daysInMonth), 16, RoundingMode.HALF_UP);
        }
        return BigDecimal.ONE;
    }

    /* Shared ledger + Buy / Sell / Profit / PS write after frequency-specific amounts are resolved. */
    private int writePostedTransactions(Integer tenantId, BankProcess bankProcess, BkProcessAccountingPosted.PeriodType periodType, LocalDate postedDate, LocalDate billingStart, LocalDate billingEnd, String createdBy, BigDecimal ratio) {
        Integer currencyId = resolveCurrencyId(tenantId, bankProcess.getCountryId());
        String bankName = resolveBankName(tenantId, bankProcess);

        boolean compensation = isCompensationPost(bankProcess, periodType);
        int mult = compensation ? compensationMultiplier(bankProcess.getContract()) : 1;
        if (compensation && mult < 1) {
            throw new BusinessException("Compensation posting requires a 1+1 / 1+2 / 1+3 contract!");
        }
        BigDecimal compensationMult = BigDecimal.valueOf(Math.max(mult, 1));

        BigDecimal buy = scaleMoney(nz(bankProcess.getSupplierPrice()).multiply(ratio).multiply(compensationMult));
        BigDecimal sell = scaleMoney(nz(bankProcess.getCustomerPrice()).multiply(ratio).multiply(compensationMult));
        BigDecimal profit = scaleMoney(nz(bankProcess.getCompanyPrice()).multiply(ratio).multiply(compensationMult));

        // Case B compensation: economic date = today; Case A keeps the due postedDate (may also be compensation).
        LocalDate transactionDate = (periodType == BkProcessAccountingPosted.PeriodType.COMPENSATION)
                ? LocalDate.now()
                : postedDate;

        BkProcessAccountingPosted ledger = new BkProcessAccountingPosted();
        ledger.setTenantId(tenantId);
        ledger.setBankProcessId(bankProcess.getId());
        ledger.setPostedDate(postedDate);
        ledger.setPeriodType(periodType);
        ledger.setOutcome(BkProcessAccountingPosted.Outcome.POSTED);
        ledger.setBillingStart(billingStart);
        ledger.setBillingEnd(billingEnd);
        ledger.setCreatedBy(createdBy);
        accountingDueDao.insertLedgerEntry(ledger);
        Integer postedId = ledger.getId();
        if (postedId == null) {
            throw new BusinessException("Failed to record accounting posted ledger!");
        }

        LocalDateTime approvedAt = LocalDateTime.now();
        int created = 0;

        if (buy.compareTo(BigDecimal.ZERO) > 0) {
            insertTxnLine(tenantId, Transaction.TransactionType.WIN, bankProcess.getSupplierAccountId(),
                    currencyId, buy, transactionDate,
                    buildLineDescription(bankProcess, periodType, postedDate, billingStart, billingEnd, buy, bankName, compensation),
                    createdBy, approvedAt, postedId);
            created++;
        }
        if (sell.compareTo(BigDecimal.ZERO) > 0) {
            insertTxnLine(tenantId, Transaction.TransactionType.LOSE, bankProcess.getCustomerAccountId(),
                    currencyId, sell, transactionDate,
                    buildLineDescription(bankProcess, periodType, postedDate, billingStart, billingEnd, sell, bankName, compensation),
                    createdBy, approvedAt, postedId);
            created++;
        }
        if (profit.compareTo(BigDecimal.ZERO) >= 0) {
            insertTxnLine(tenantId, Transaction.TransactionType.WIN, bankProcess.getCompanyAccountId(),
                    currencyId, profit, transactionDate,
                    buildLineDescription(bankProcess, periodType, postedDate, billingStart, billingEnd, profit, bankName, compensation),
                    createdBy, approvedAt, postedId);
            created++;
        }

        List<BankProcessShare> shares = bankProcessDao.findSharesByBankProcessId(bankProcess.getId());
        if (shares != null) {
            for (BankProcessShare share : shares) {
                if (share == null || share.getAccountId() == null || share.getAccountId() <= 0) {
                    continue;
                }
                BigDecimal shareAmount = scaleMoney(nz(share.getAmount()).multiply(ratio).multiply(compensationMult));
                if (shareAmount.compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }
                insertTxnLine(tenantId, Transaction.TransactionType.WIN, share.getAccountId(), currencyId, shareAmount, transactionDate,
                        buildLineDescription(bankProcess, periodType, postedDate, billingStart, billingEnd, shareAmount, bankName, compensation),
                        createdBy, approvedAt, postedId);
                created++;
            }
        }

        // Case A already applied ×N compensation on a normal period row — settle COMPENSATION
        // so Case B does not re-open a lookalike Due (same dayStart) after refresh.
        if (compensation && periodType != BkProcessAccountingPosted.PeriodType.COMPENSATION) {
            settleCompensationSlot(tenantId, bankProcess, createdBy);
        }

        return created;
    }

    /**
     * Mark 1+N Case B slot ({@code COMPENSATION} @ dayStart) as POSTED if missing,
     * so Inbox will not regenerate a second compensation due after Case A.
     */
    private void settleCompensationSlot(Integer tenantId, BankProcess bankProcess, String createdBy) {
        if (bankProcess == null || bankProcess.getId() == null || bankProcess.getDayStart() == null) {
            return;
        }
        if (!isOnePlusContract(bankProcess.getContract())) {
            return;
        }
        LocalDate dayStart = bankProcess.getDayStart();
        BkProcessAccountingPosted existing = accountingDueDao.findLedgerEntry(
                tenantId,
                bankProcess.getId(),
                dayStart,
                BkProcessAccountingPosted.PeriodType.COMPENSATION);
        if (existing != null) {
            return;
        }
        BkProcessAccountingPosted row = new BkProcessAccountingPosted();
        row.setTenantId(tenantId);
        row.setBankProcessId(bankProcess.getId());
        row.setPostedDate(dayStart);
        row.setPeriodType(BkProcessAccountingPosted.PeriodType.COMPENSATION);
        row.setOutcome(BkProcessAccountingPosted.Outcome.POSTED);
        row.setBillingStart(dayStart);
        row.setBillingEnd(dayStart);
        row.setCreatedBy(createdBy);
        accountingDueDao.insertLedgerEntry(row);
    }

    private static String buildLineDescription(BankProcess bankProcess, BkProcessAccountingPosted.PeriodType periodType, LocalDate postedDate, LocalDate billingStart, LocalDate billingEnd, BigDecimal amount, String bankName, boolean compensation) {
        if (compensation) {
            return buildCompensationDescription(bankProcess.getContract(), amount, bankName);
        }
        BankProcess.Frequency frequency = periodType == BkProcessAccountingPosted.PeriodType.RESEND_CONSOLIDATED
                ? resolveResendPostFrequency(bankProcess)
                : bankProcess.getFrequency();
        return buildPostDescription(frequency, periodType, postedDate, billingStart, billingEnd, amount, bankName);
    }

    /*Description for Compensation*/
     static String buildCompensationDescription(String contract, BigDecimal amount, String bankName) {
        String amt = formatDescriptionAmount(amount);
        String bank = bankName != null && !bankName.isBlank() ? bankName.trim() : "";
        return "COMPENSATION " + compensationMonthLabel(contract) + " " + amt + " | " + bank;
    }

    /* Description Format By All Frequency, include format Date, Capital Letter... */
    static String buildPostDescription(BankProcess.Frequency frequency, BkProcessAccountingPosted.PeriodType periodType, LocalDate postedDate, LocalDate billingStart, LocalDate billingEnd, BigDecimal amount, String bankName) {
        String amt = formatDescriptionAmount(amount);
        String bank = bankName != null && !bankName.isBlank() ? bankName.trim() : "";
        if (frequency == BankProcess.Frequency.MONTHLY) {
            return "MONTHLY BILL " + amt + " | " + bank;
        }
        if (frequency == BankProcess.Frequency.ONCE) {
            LocalDate day = postedDate != null ? postedDate : billingStart;
            return "ONCE (" + formatDescriptionDate(day) + ") @ " + amt + " | " + bank;
        }
        if (frequency == BankProcess.Frequency.WEEK) {
            return "WEEK (" + formatDescriptionDate(billingStart) + " - " + formatDescriptionDate(billingEnd)
                    + ") @ " + amt + " | " + bank;
        }
        if (frequency == BankProcess.Frequency.DAY) {
            LocalDate day = postedDate != null ? postedDate : billingStart;
            return "DAY (" + formatDescriptionDate(day) + ") @ " + amt + " | " + bank;
        }
        if (frequency == BankProcess.Frequency.FIRST_OF_EVERY_MONTH) {
            // Resend makeup always uses PRORATED (even when every month in the window is full).
            if (periodType == BkProcessAccountingPosted.PeriodType.PARTIAL_FIRST_MONTH
                    || periodType == BkProcessAccountingPosted.PeriodType.DAY_END_TAIL
                    || periodType == BkProcessAccountingPosted.PeriodType.RESEND_CONSOLIDATED) {
                long inclusiveDays = ChronoUnit.DAYS.between(billingStart, billingEnd) + 1;
                return "PRORATED(" + formatDayMonth(billingStart) + " - " + formatDayMonth(billingEnd)
                        + " | " + inclusiveDays + " DAYS)@MONTHLY " + amt + " | " + bank;
            }
            LocalDate monthAnchor = postedDate != null ? postedDate : billingStart;
            String monthLabel = monthAnchor.getMonth()
                    .getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
                    .toUpperCase(Locale.ENGLISH);
            return "FULL MONTH (" + monthLabel + " " + monthAnchor.getYear() + ") @MONTHLY " + amt + " | " + bank;
        }
        throw new BusinessException("Posting description is not supported for this frequency yet!");
    }

    /* Description Use for Show Bank Name */
    private String resolveBankName(Integer tenantId, BankProcess bankProcess) {
        if (bankProcess.getBankOptionId() == null || bankProcess.getCountryId() == null) {
            throw new BusinessException("Bank process bank is required!");
        }
        BankOption bankOption = bankCountryOptionDao.findBankOptionById(
                tenantId, bankProcess.getCountryId(), bankProcess.getBankOptionId());
        if (bankOption == null || bankOption.getName() == null || bankOption.getName().isBlank()) {
            throw new BusinessException("Bank option not found!");
        }
        return bankOption.getName().trim();
    }

    /* Description Use to compatible List Show */
    private static String formatDayMonth(LocalDate date) {
        return date.getDayOfMonth() + "/" + date.getMonthValue();
    }

    private static String formatDescriptionDate(LocalDate date) {
        return String.format(Locale.ROOT, "%02d/%02d/%04d",
                date.getDayOfMonth(), date.getMonthValue(), date.getYear());
    }

    /* Description amount: drop trailing zeros (200.00 → 200). */
    private static String formatDescriptionAmount(BigDecimal value) {
        return scaleMoney(value).stripTrailingZeros().toPlainString();
    }

    private Integer resolveCurrencyId(Integer tenantId, Integer countryId) {
        if (countryId == null) {
            throw new BusinessException("Bank process country is required!");
        }
        BankCountry country = bankCountryOptionDao.findCountryById(tenantId, countryId);
        if (country == null || country.getCode() == null || country.getCode().isBlank()) {
            throw new BusinessException("Bank process country not found!");
        }
        Currency currency = currencyDao.findByTenantIdAndCode(tenantId, country.getCode().trim());
        if (currency == null || currency.getId() == null) {
            throw new BusinessException("Currency not found for country: " + country.getCode());
        }
        return currency.getId();
    }


    private void insertTxnLine(Integer tenantId, Transaction.TransactionType type, Integer accountId, Integer currencyId, BigDecimal amount, LocalDate transactionDate, String description, String createdBy, LocalDateTime approvedAt, Integer bankProcessPostedId) {
        Transaction txn = new Transaction();
        txn.setTenantId(tenantId);
        txn.setTransactionType(type);
        txn.setAccountId(accountId);
        txn.setFromAccountId(null);
        txn.setCurrencyId(currencyId);
        txn.setAmount(amount);
        txn.setTransactionDate(transactionDate);
        txn.setDescription(description);
        txn.setRemark(null);
        txn.setCreatedBy(createdBy);
        txn.setUpdatedBy(null);
        txn.setApprovalStatus(Transaction.ApprovalStatus.APPROVED);
        txn.setApprovedBy(createdBy);
        txn.setApprovedAt(approvedAt);
        txn.setBankProcessPostedId(bankProcessPostedId);
        transactionDao.insert(txn);
    }

    private static BigDecimal nz(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    private static BigDecimal scaleMoney(BigDecimal value) {
        return value.setScale(MONEY_SCALE, MONEY_ROUNDING);
    }


}
