package com.eazycount.service.impl;

import com.eazycount.common.BusinessException;
import com.eazycount.dao.BankProcessDao;
import com.eazycount.dao.BankProcessResendDao;
import com.eazycount.dto.AccountingDueDTO;
import com.eazycount.dto.BankProcessDTO;
import com.eazycount.entity.BankProcess;
import com.eazycount.entity.BkProcessAccountingPosted;
import com.eazycount.security.SecurityUtils;
import com.eazycount.security.SessionUser;
import com.eazycount.service.BankProcessResendService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

@Service
public class BankProcessResendServiceImpl implements BankProcessResendService {

    @Autowired
    private BankProcessDao bankProcessDao;

    @Autowired
    private BankProcessResendDao bankProcessResendDao;

    @Override
    @Transactional
    public AccountingDueDTO resend(AccountingDueDTO request) {
        SessionUser sessionUser = SecurityUtils.currentUser();
        if (sessionUser == null) {
            throw new BusinessException("Not logged in");
        }
        if (request == null) {
            throw new BusinessException("Invalid Resend request!");
        }

        Integer tenantId = request.getTenantId();
        Integer bankProcessId = request.getBankProcessId();
        if (tenantId == null) {
            throw new BusinessException("Invalid Tenant Id!");
        }
        if (bankProcessId == null || bankProcessId <= 0) {
            throw new BusinessException("Invalid bank process ID!");
        }

        BankProcess existing = bankProcessDao.findBKProcessByIdAndTenantId(bankProcessId, tenantId);
        if (existing == null) {
            throw new BusinessException("Bank process not found!");
        }
        if (existing.getStatus() != BankProcess.Status.ACTIVE) {
            throw new BusinessException("Only ACTIVE Bank Process can use Resend!");
        }

        BankProcess.Frequency frequency = parseFrequency(request.getFrequency(), existing.getFrequency());

        Window window = resolveWindow(frequency, request.getDayStart(), request.getDayEnd());

        // Same process + same day_start while open make-up still in Due → reject.
        if (existing.getResendScheduleDayStart() != null
                && window.postedDate().equals(existing.getResendScheduleDayStart())) {
            throw new BusinessException(
                    "This process already has an open Resend bill for this Day start!");
        }

        // After Skip, allow same day_start again by clearing SKIPPED make-up ledger.
        bankProcessResendDao.deleteSkippedResendConsolidated(
                tenantId, bankProcessId, window.postedDate());
        // Different day_start overwrites the previous open schedule (latest wins).
        bankProcessResendDao.updateResendSchedule(
                bankProcessId,
                tenantId,
                window.billingStart(),
                window.billingEnd(),
                frequency);

        return buildMakeUpDue(existing, window, frequency);
    }

    @Override
    public AccountingDueDTO resolveOpenMakeUp(BankProcessDTO dto, LocalDate today) {
        if (dto == null) {
            return null;
        }
        BankProcess bp = dto.getBankProcess();
        if (bp == null || bp.getResendScheduleDayStart() == null || bp.getResendScheduleFrequency() == null) {
            return null;
        }
        LocalDate start = bp.getResendScheduleDayStart();
        LocalDate end = bp.getResendScheduleDayEnd() != null ? bp.getResendScheduleDayEnd() : start;
        // Make-up bills always appear in Accounting Due (past or future) — no today/asOf date filter.
        return buildMakeUpDueFromProcess(dto, bp, start, end, bp.getResendScheduleFrequency());
    }

    @Override
    @Transactional
    public void clearOpenSchedule(Integer bankProcessId, Integer tenantId) {
        if (bankProcessId == null || tenantId == null) {
            return;
        }
        bankProcessResendDao.clearResendSchedule(bankProcessId, tenantId);
    }

    private static Window resolveWindow(BankProcess.Frequency frequency, LocalDate dayStart,LocalDate dayEnd) {
        if (frequency == null) {
            throw new BusinessException("Resend frequency is required!");
        }
        if (dayStart == null) {
            throw new BusinessException("Resend day_start is required!");
        }
        return switch (frequency) {
            case FIRST_OF_EVERY_MONTH -> {
                if (dayEnd == null) {
                    throw new BusinessException(
                            "day_start and day_end are required for 1st of every month Resend!");
                }
                if (dayEnd.isBefore(dayStart)) {
                    throw new BusinessException("day_end cannot be before day_start!");
                }
                yield new Window(dayStart, dayStart, dayEnd);
            }
            case MONTHLY -> new Window(dayStart, dayStart, dayStart.plusMonths(1));
            case WEEK -> new Window(dayStart, dayStart, dayStart.plusDays(6));
            case ONCE, DAY -> new Window(dayStart, dayStart, dayStart);
        };
    }

    private static AccountingDueDTO buildMakeUpDue(BankProcess bp,
                                                   Window window,
                                                   BankProcess.Frequency frequency) {
        AccountingDueDTO due = new AccountingDueDTO();
        due.setTenantId(bp.getTenantId());
        due.setBankProcessId(bp.getId());
        due.setPostedDate(window.postedDate());
        due.setPeriodType(BkProcessAccountingPosted.PeriodType.RESEND_CONSOLIDATED.name());
        due.setBillingStart(window.billingStart());
        due.setBillingEnd(window.billingEnd());
        due.setDayStart(window.billingStart());
        due.setDayEnd(window.billingEnd());
        due.setFrequency(frequency.name());
        due.setStatus(bp.getStatus() != null ? bp.getStatus().name() : null);
        due.setCardOwner(bp.getCardOwner());
        due.setCardOwnerType(bp.getCardOwnerType());
        due.setContract(bp.getContract());
        due.setSupplierPrice(bp.getSupplierPrice());
        due.setCustomerPrice(bp.getCustomerPrice());
        due.setCompanyPrice(bp.getCompanyPrice());
        return due;
    }

    private static AccountingDueDTO buildMakeUpDueFromProcess(BankProcessDTO dto,
                                                              BankProcess bp,
                                                              LocalDate billingStart,
                                                              LocalDate billingEnd,
                                                              BankProcess.Frequency frequency) {
        AccountingDueDTO due = new AccountingDueDTO();
        due.setBankProcessId(bp.getId());
        due.setTenantId(bp.getTenantId());
        due.setPostedDate(billingStart);
        due.setPeriodType(BkProcessAccountingPosted.PeriodType.RESEND_CONSOLIDATED.name());
        due.setBillingStart(billingStart);
        due.setBillingEnd(billingEnd);
        due.setDayStart(billingStart);
        due.setDayEnd(billingEnd);
        due.setFrequency(frequency != null ? frequency.name() : null);
        due.setCountryCode(dto.getCountryCode());
        due.setBankName(dto.getBankName());
        due.setCardOwner(bp.getCardOwner());
        due.setCardOwnerType(bp.getCardOwnerType());
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

    private static BankProcess.Frequency parseFrequency(String raw, BankProcess.Frequency fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String normalized = raw.trim()
                .toUpperCase()
                .replace('-', '_')
                .replace(' ', '_');
        if ("1ST_OF_EVERY_MONTH".equals(normalized)) {
            normalized = "FIRST_OF_EVERY_MONTH";
        }
        try {
            return BankProcess.Frequency.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            throw new BusinessException("Invalid Resend frequency!");
        }
    }

    private record Window(LocalDate postedDate, LocalDate billingStart, LocalDate billingEnd) {
    }
}
