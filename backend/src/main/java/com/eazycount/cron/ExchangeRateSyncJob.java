package com.eazycount.cron;

import com.eazycount.dao.ExchangeRateDao;
import com.eazycount.dto.FrankfurterRateRow;
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
import java.util.Arrays;
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
        Map<String, BigDecimal> usdToQuote;
        try {
            usdToQuote = fetchUsdToQuote(fiatCodes);
        } catch (RestClientException e) {
            // The batch call 4xx's as a whole if even one quote is invalid/unsupported (e.g. a
            // typo'd currency code a tenant entered, like "RM" instead of "MYR") — that must not
            // take every other currency's refresh down with it. Falling back to one request per
            // currency here (only on this failure path, not the common case) still lets every
            // *valid* code sync; only the bad one(s) get logged and skipped below.
            log.warn("Exchange rate sync: batch fetch failed for {} ({}), retrying per-currency",
                    fiatCodes, e.getMessage());
            usdToQuote = fetchUsdToQuoteIndividually(fiatCodes);
        }

        for (String code : fiatCodes) {
            BigDecimal rate = usdToQuote.get(code);
            if (rate == null || rate.signum() <= 0) {
                // A currency missing from the response (delisted, typo, unsupported) is logged
                // and left untouched — its previous rate_date row simply stays the latest one.
                log.warn("Exchange rate sync: no rate returned for {}, leaving previous value in place", code);
                continue;
            }
            // Response is "1 USD = X quote", we store "1 quote = ? USD".
            BigDecimal rateToUsd = BigDecimal.ONE.divide(rate, RATE_SCALE, RoundingMode.HALF_UP);
            exchangeRateDao.upsertRate(new ExchangeRate(code, rateToUsd, today, "frankfurter"));
        }
    }

    // /v2/rates takes `quotes` (not `symbols`, which is the retired v1 /latest param) and
    // returns a JSON array of one row per quote currency, not a single {rates:{...}} map.
    private Map<String, BigDecimal> fetchUsdToQuote(List<String> quoteCodes) {
        String url = UriComponentsBuilder.fromHttpUrl(frankfurterUrl)
                .queryParam("base", USD)
                .queryParam("quotes", String.join(",", quoteCodes))
                .toUriString();

        FrankfurterRateRow[] rows = restTemplate.getForObject(url, FrankfurterRateRow[].class);
        if (rows == null) {
            return Map.of();
        }
        return Arrays.stream(rows)
                .filter(row -> row.getQuote() != null && row.getRate() != null)
                .collect(Collectors.toMap(
                        row -> row.getQuote().trim().toUpperCase(),
                        FrankfurterRateRow::getRate,
                        (a, b) -> a));
    }

    private Map<String, BigDecimal> fetchUsdToQuoteIndividually(List<String> quoteCodes) {
        Map<String, BigDecimal> merged = new java.util.HashMap<>();
        for (String code : quoteCodes) {
            try {
                merged.putAll(fetchUsdToQuote(List.of(code)));
            } catch (RestClientException e) {
                log.warn("Exchange rate sync: {} has no usable Frankfurter rate ({})", code, e.getMessage());
            }
        }
        return merged;
    }
}
