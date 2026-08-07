package com.eazycount.dao;

import com.eazycount.dto.DataCaptureGameDTO;
import com.eazycount.entity.DataCaptureDraft;
import com.eazycount.entity.DataCaptureDraftCell;
import com.eazycount.entity.Process;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface DataCaptureDao {

    /* GAME-only "already submitted today" gate — BANK never reads/writes process_submitted. */
    boolean existsProcessSubmitted(@Param("tenantId") Integer tenantId, @Param("processId") Integer processId, @Param("captureDate") LocalDate captureDate);

    void insertProcessSubmitted(@Param("tenantId") Integer tenantId, @Param("processId") Integer processId, @Param("userId") Integer userId, @Param("captureDate") LocalDate captureDate);

    //Find Process By Day
    List<DataCaptureGameDTO> findGameProcessesByDay(@Param("tenantId") Integer tenantId, @Param("captureDate") LocalDate captureDate, @Param("dayOfWeek") Integer dayOfWeek);

    // Auto Fill Use For Games Process
    DataCaptureGameDTO findGameProcessDetail(@Param("tenantId") Integer tenantId, @Param("processId") Integer processId);

    // Description names linked to a process (ordered by name).
    List<String> findGameProcessDescriptionNames(@Param("processId") Integer processId);

    Process findBankProcessByTenantAndCode(@Param("tenantId") Integer tenantId, @Param("code") String code);

    void insertBankProcess(Process process);

    DataCaptureDraft findDraftByTenantProcessCurrency(@Param("tenantId") Integer tenantId, @Param("processId") Integer processId, @Param("currencyId") Integer currencyId);

    void insertDraft(DataCaptureDraft draft);

    void touchDraftUpdatedAt(@Param("id") Integer id);

    void deleteDraftCellsByDraftId(@Param("draftId") Integer draftId);

    void insertDraftCells(@Param("list") List<DataCaptureDraftCell> list);

    List<DataCaptureDraftCell> findDraftCellsByDraftId(@Param("draftId") Integer draftId);
}
