package com.eazycount.service.impl;

import com.eazycount.dao.ExchangeRateDao;
import com.eazycount.entity.ExchangeRate;
import com.eazycount.service.ExchangeRateService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ExchangeRateServiceImpl implements ExchangeRateService {

    private static final int CONVERT_SCALE = 8;

    @Autowired
    private ExchangeRateDao exchangeRateDao;

    @Override
    public Map<String, BigDecimal> loadRatesToUsd() {
        return exchangeRateDao.findLatestRates().stream()
                .collect(Collectors.toMap(
                        r -> r.getCurrencyCode().toUpperCase(),
                        ExchangeRate::getRateToUsd,
                        (a, b) -> a));
    }

    @Override
    public BigDecimal convert(BigDecimal amount, String fromCode, String toCode, Map<String, BigDecimal> ratesToUsd) {
        if (amount == null || fromCode == null || toCode == null) {
            return null;
        }
        String from = fromCode.trim().toUpperCase();
        String to = toCode.trim().toUpperCase();
        if (from.equals(to)) {
            return amount;
        }
        BigDecimal fromRateToUsd = ratesToUsd.get(from);
        BigDecimal toRateToUsd = ratesToUsd.get(to);
        if (fromRateToUsd == null || toRateToUsd == null || toRateToUsd.signum() == 0) {
            return null;
        }
        return amount.multiply(fromRateToUsd).divide(toRateToUsd, CONVERT_SCALE, RoundingMode.HALF_UP);
    }
}
