package com.eazycount.dao;

import com.eazycount.entity.DataCapture;
import com.eazycount.entity.DataCaptureFormula;
import com.eazycount.entity.DataCaptureLine;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.util.List;

@Mapper
public interface DataCaptureSummaryDao {

    /* Summary Submit header — one row per submit; {@code id} is generated back onto {@code dataCapture}. */
    void insertCapture(DataCapture dataCapture);

    DataCapture findCaptureByIdAndTenantId(@Param("id") Integer id, @Param("tenantId") Integer tenantId);

    /* Summary Submit row snapshots — batch insert, one capture may have many product/account lines. */
    void insertLines(@Param("list") List<DataCaptureLine> list);

    List<DataCaptureLine> findLinesByCaptureId(@Param("captureId") Integer captureId);

    //Used to decide Add Formula → MAIN vs SUB.
    DataCaptureFormula findMainWithAccount(@Param("tenantId") Integer tenantId, @Param("processId") Integer processId, @Param("idProduct") String idProduct);

    //Find Main row for the product
    DataCaptureFormula findMainByProduct(@Param("tenantId") Integer tenantId, @Param("processId") Integer processId, @Param("idProduct") String idProduct);

    BigDecimal findMaxSubOrder(@Param("tenantId") Integer tenantId, @Param("processId") Integer processId, @Param("parentIdProduct") String parentIdProduct);

    void insertFormula(DataCaptureFormula formula);

    void updateMainFields(DataCaptureFormula formula);

    DataCaptureFormula findByIdAndTenantId(@Param("id") Integer id, @Param("tenantId") Integer tenantId);

    /*
     * Resolve MAIN/SUB for Bank Edit when UI has no templateId.
     * SUB matches parent + account + sub_order; MAIN matches id_product + account. */
    DataCaptureFormula findByBusinessKey(@Param("tenantId") Integer tenantId, @Param("processId") Integer processId, @Param("productType") String productType, @Param("idProduct") String idProduct, @Param("parentIdProduct") String parentIdProduct, @Param("accountId") Integer accountId, @Param("subOrder") BigDecimal subOrder);

    /* Update mutable fields for MAIN or SUB by id (identity / type / parent / sub_order unchanged). */
    void updateFormulaById(DataCaptureFormula formula);

    int deleteByIdAndTenantId(@Param("id") Integer id, @Param("tenantId") Integer tenantId);
}
