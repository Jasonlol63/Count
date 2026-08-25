package com.eazycount.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/** Domain Report: request filters + one process row, or the synthesized Total row. */
@Getter
@Setter
public class DomainReportDTO {

    private Integer tenantId;
    private String dateFrom;
    private String dateTo;
    private Integer processId;
    private String category;

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
