package com.eazycount.dto;

import com.eazycount.entity.Process;
import com.eazycount.entity.ProcessDescription;
import lombok.*;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ProcessDescriptionDTO {

    private Process process;
    private List<ProcessDescription> processDescriptions;

    private Integer tenantId;
    private String code;                       // 对应前端流程名
    private List<String> selectedDescriptions; // 对应前端选择的描述名列表
    private List<Integer> descriptionIds;
    private Integer currencyId;
    private String dayUse;                     // 星期几，例如 "1,2,3"
    private String removeWord;
    private String replaceWordFrom;
    private String replaceWordTo;
    private String remark;
}
