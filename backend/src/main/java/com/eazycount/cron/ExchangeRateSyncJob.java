package com.eazycount.cron;

import com.eazycount.dao.ExchangeRateDao;
import com.eazycount.dto.FrankfurterRatesResponse;
import com.eazycount.entity.ExchangeRate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

// Refreshes `exchange_rate` once a day. All rates are pivoted against USD (see
// migrate_add_exchange_rate_table.sql) so converting any currency A -> B is a simple
// division at read time — this job only has to know each currency's rate against USD.
@Component
public class ExchangeRateSyncJob {

    private static final Logger log = LoggerFactory.getLogger(ExchangeRateSyncJob.class);

    private static final String USD = "USD";
    private static final int RATE_SCALE = 8;

    // Stablecoins aren't fiat, so Frankfurter has no rate for them — they're pegged ~1:1 to
    // USD, so we write that directly instead of calling an external API for them.
    private static final Set<String> STABLECOINS = Set.of("USDT", "USDC");

    private final ExchangeRateDao exchangeRateDao;
    private final RestTemplate restTemplate;

    @Value("${app.exchange-rate.frankfurter-url}")
    private String frankfurterUrl;

    public ExchangeRateSyncJob(ExchangeRateDao exchangeRateDao, RestTemplate restTemplate) {
        this.exchangeRateDao = exchangeRateDao;
        this.restTemplate = restTemplate;
    }

    @Scheduled(cron = "${app.exchange-rate.cron}")
    public void syncDailyRates() {
        LocalDate today = LocalDate.now();
        List<String> activeCodes = exchangeRateDao.findDistinctActiveCurrencyCodes();
        if (activeCodes.isEmpty()) {
            log.info("Exchange rate sync skipped: no active currencies configured");
            return;
        }

        exchangeRateDao.upsertRate(new ExchangeRate(USD, BigDecimal.ONE, today, "frankfurter"));

        Set<String> stablecoinCodes = activeCodes.stream()
                .filter(STABLECOINS::contains)
                .collect(Collectors.toSet());
        stablecoinCodes.forEach(code ->
                exchangeRateDao.upsertRate(new ExchangeRate(code, BigDecimal.ONE, today, "stablecoin")));

        List<String> fiatCodes = activeCodes.stream()
                .filter(code -> !code.equals(USD) && !STABLECOINS.contains(code))
                .distinct()
                .toList();
        if (fiatCodes.isEmpty()) {
            log.info("Exchange rate sync: no fiat currencies to fetch (stablecoins={})", stablecoinCodes);
            return;
        }

        try {
            syncFiatRates(fiatCodes, today);
        } catch (RestClientException e) {
            // Frankfurter being unreachable must not break dashboard reads: the conversion
            // service falls back to the most recent successful rate_date on its own.
            log.warn("Exchange rate sync failed, keeping previous rates ({})", e.getMessage());
        }
    }

    private void syncFiatRates(List<String> fiatCodes, LocalDate today) {
        String url = UriComponentsBuilder.fromHttpUrl(frankfurterUrl)
                .queryParam("base", USD)
                .queryParam("symbols", String.join(",", fiatCodes))
                .toUriString();

        FrankfurterRatesResponse response = restTemplate.getForObject(url, FrankfurterRatesResponse.class);
        if (response == null || response.getRates() == null) {
            log.warn("Exchange rate sync: empty response from Frankfurter for {}", fiatCodes);
            return;
        }

        Map<String, BigDecimal> usdToQuote = response.getRates();
        for (String code : fiatCodes) {
            BigDecimal rate = usdToQuote.get(code);
            if (rate == null || rate.signum() <= 0) {
                // A currency missing from the batch response (delisted, typo, unsupported) is
                // logged and left untouched rather than retried per-code — the old system's
                // per-currency backfill loop is exactly the slowdown this table replaces.
                log.warn("Exchange rate sync: no rate returned for {}, leaving previous value in place", code);
                continue;
            }
            // Response is "1 USD = X quote", we store "1 quote = ? USD".
            BigDecimal rateToUsd = BigDecimal.ONE.divide(rate, RATE_SCALE, RoundingMode.HALF_UP);
            exchangeRateDao.upsertRate(new ExchangeRate(code, rateToUsd, today, "frankfurter"));
        }
    }
}
