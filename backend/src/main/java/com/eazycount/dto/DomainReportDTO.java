package com.eazycount.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/** Domain Report: request filters + one process row, or the synthesized Total row. */
@Getter
@Setter
public class DomainReportDTO {

    // Request
    private Integer tenantId;
    private String dateFrom;
    private String dateTo;
    /* Single process filter; null = "All Process". */
    private Integer processId;

    // Response row
    private Integer processRowId;
    private String processCode;
    private String description;
    /* Turnover = winAmount + loseAmount. */
    private BigDecimal turnoverAmount;
    private BigDecimal winAmount;
    /* Positive magnitude — unlike CustomerReportDTO, Domain Report does NOT negate Lose. */
    private BigDecimal loseAmount;
    /* Net balance check: winAmount - loseAmount, expected to sit close to 0. */
    private BigDecimal winLoseAmount;

    /* true only on the synthesized grand-total row appended at the end of the list. */
    private Boolean totalRow;
}
