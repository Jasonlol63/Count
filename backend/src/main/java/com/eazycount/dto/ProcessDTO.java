package com.eazycount.dto;

import com.eazycount.entity.Process;
import com.eazycount.entity.ProcessDay;
import com.eazycount.entity.ProcessDescription;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ProcessDTO {

    private Integer id;
    private Process process;
    private List<ProcessDescription> processDescriptions;
    private List<ProcessDay> processDays;
    private String currencyCode;

    private Integer tenantId;
    private String code;
    private Integer currencyId;
    private List<Integer> descriptionIds;
    private List<Integer> dayOfWeeks; // 1=Mon … 7=Sun
    private String removeWord;
    private String replaceWordFrom;
    private String replaceWordTo;
    private String remark;
}
