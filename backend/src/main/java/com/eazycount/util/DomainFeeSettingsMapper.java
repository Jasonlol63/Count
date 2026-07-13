package com.eazycount.util;

import com.eazycount.dto.DomainFeeSettingsDTO;
import com.eazycount.dto.DomainFeeSettingsDTO.PeriodPrices;
import com.eazycount.entity.DomainListFeePrice;
import com.eazycount.entity.Tenant.TenantType;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public final class DomainFeeSettingsMapper {

    private DomainFeeSettingsMapper() {
    }

    public static DomainFeeSettingsDTO toDto(List<DomainListFeePrice> rows) {
        DomainFeeSettingsDTO dto = new DomainFeeSettingsDTO();
        dto.setCompanyPeriodPrices(new PeriodPrices());
        dto.setGroupPeriodPrices(new PeriodPrices());

        if (rows == null) {
            return dto;
        }

        for (DomainListFeePrice row : rows) {
            if (row == null || row.getTenantType() == null || row.getPeriod() == null) {
                continue;
            }
            PeriodPrices target = row.getTenantType() == TenantType.COMPANY
                    ? dto.getCompanyPeriodPrices()
                    : dto.getGroupPeriodPrices();
            applyPrice(target, row.getPeriod(), row.getPrice());
        }
        return dto;
    }

    public static List<DomainListFeePrice> toRows(DomainFeeSettingsDTO dto) {
        List<DomainListFeePrice> rows = new ArrayList<>();
        if (dto == null) {
            return rows;
        }

        appendRows(rows, TenantType.COMPANY, dto.getCompanyPeriodPrices());
        appendRows(rows, TenantType.GROUP, dto.getGroupPeriodPrices());
        return rows;
    }

    private static void appendRows(List<DomainListFeePrice> rows, TenantType tenantType, PeriodPrices prices) {
        if (prices == null) {
            return;
        }
        addRow(rows, tenantType, "7days", prices.getDays7());
        addRow(rows, tenantType, "1month", prices.getMonth1());
        addRow(rows, tenantType, "3months", prices.getMonths3());
        addRow(rows, tenantType, "6months", prices.getMonths6());
        addRow(rows, tenantType, "1year", prices.getYear1());
    }

    private static void addRow(List<DomainListFeePrice> rows, TenantType tenantType, String period, BigDecimal price) {
        if (price == null) {
            return;
        }
        DomainListFeePrice row = new DomainListFeePrice();
        row.setTenantType(tenantType);
        row.setPeriod(period);
        row.setPrice(price);
        rows.add(row);
    }

    private static void applyPrice(PeriodPrices prices, String period, BigDecimal price) {
        if (prices == null || period == null) {
            return;
        }
        switch (period) {
            case "7days" -> prices.setDays7(price);
            case "1month" -> prices.setMonth1(price);
            case "3months" -> prices.setMonths3(price);
            case "6months" -> prices.setMonths6(price);
            case "1year" -> prices.setYear1(price);
            default -> {
            }
        }
    }
}
