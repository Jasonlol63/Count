package com.eazycount.service;

import java.math.BigDecimal;
import java.util.Map;

public interface ExchangeRateService {

    // One DB read for the whole request: latest available rate_to_usd per currency code
    // (uppercased). Callers load this once and reuse it for every row they convert —
    // conversion itself is then pure in-memory arithmetic, no further I/O.
    Map<String, BigDecimal> loadRatesToUsd();

    // amount in `fromCode` -> equivalent in `toCode`, derived as
    // amount * rate(fromCode -> USD) / rate(toCode -> USD) — no NxN rate matrix needed.
    // Returns null if either currency has no known rate yet (caller renders "—").
    BigDecimal convert(BigDecimal amount, String fromCode, String toCode, Map<String, BigDecimal> ratesToUsd);
}
