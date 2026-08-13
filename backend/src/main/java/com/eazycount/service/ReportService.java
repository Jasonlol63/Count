package com.eazycount.service;

import com.eazycount.dto.CustomerReportDTO;
import com.eazycount.dto.DomainReportDTO;

import java.util.List;

public interface ReportService {

    List<CustomerReportDTO> findCustomerReportRows(CustomerReportDTO request);

    List<DomainReportDTO> findDomainReportRows(DomainReportDTO request);
}
