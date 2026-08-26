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

    // Copy From: deep-copy a source process's formulas onto a new process id.
    // Formula sync group: tag the source formulas with a group id (self-id) before copying, if they
    // don't already have one, so the copy below can inherit it.
    void backfillFormulaGroupIds(@Param("sourceProcessId") Integer sourceProcessId, @Param("tenantId") Integer tenantId);

    void copyProcessFormulas(@Param("sourceProcessId") Integer sourceProcessId, @Param("newProcessId") Integer newProcessId, @Param("tenantId") Integer tenantId, @Param("createdBy") String createdBy);

    // Copy From formula sync: look up the group tag of the row being edited (null if never copied).
    Integer findFormulaGroupIdByIdAndTenantId(@Param("tenantId") Integer tenantId, @Param("id") Integer id);

    // Copy From formula sync: mirror the same edit onto every other row sharing this group tag
    // (the row that was directly edited is excluded via id != #{excludeId}).
    int propagateFormulaGroupUpdate(
            @Param("tenantId") Integer tenantId,
            @Param("groupId") Integer groupId,
            @Param("excludeId") Integer excludeId,
            @Param("accountId") Integer accountId,
            @Param("sourcePercent") String sourcePercent,
            @Param("inputMethod") String inputMethod,
            @Param("formula") String formula,
            @Param("description") String description,
            @Param("updatedBy") String updatedBy);
}
