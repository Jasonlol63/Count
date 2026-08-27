package com.eazycount.service.impl;

import com.eazycount.common.BusinessException;
import com.eazycount.dao.DataCaptureDao;
import com.eazycount.dto.DataCaptureBankDTO;
import com.eazycount.dto.DataCaptureGameDTO;
import com.eazycount.entity.DataCaptureDraft;
import com.eazycount.entity.DataCaptureDraftCell;
import com.eazycount.entity.Process;
import com.eazycount.security.SecurityUtils;
import com.eazycount.security.SessionUser;
import com.eazycount.service.DataCaptureService;
import com.eazycount.util.AccessControlUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class DataCaptureServiceImpl implements DataCaptureService {

    private static final Set<String> BANK_DRAFT_PROCESS_CODES = Set.of("SALARY", "COMMISSION", "BONUS");
    private static final Set<String> BANK_PROCESS_CODES = Set.of("PROFIT", "SALARY", "COMMISSION", "BONUS");

    @Autowired
    private DataCaptureDao dataCaptureDao;

    @Override
    public List<DataCaptureGameDTO> findAllProcessSubmittedByIdAndDate(DataCaptureGameDTO request) {
        requireLogin();
        if (request == null) {
            throw new BusinessException("Request body is required");
        }

        Integer tenantId = request.getTenantId();
        requireTenantId(tenantId);
        LocalDate captureDate = requireCaptureDate(request.getCaptureDate());

        return dataCaptureDao.findAllProcessSubmittedByIdAndDate(tenantId, captureDate);
    }

    @Override
    public DataCaptureGameDTO loadGameCaptureForm(DataCaptureGameDTO request) {
        requireLogin();
        if (request == null) {
            throw new BusinessException("Request body is required");
        }

        Integer tenantId = request.getTenantId();
        LocalDate captureDate = request.getCaptureDate();
        requireTenantId(tenantId);
        LocalDate date = requireCaptureDate(captureDate);

        int dayOfWeek = toProcessDayOfWeek(date);
        List<DataCaptureGameDTO> processes = loadProcessesByDay(tenantId, date, dayOfWeek);

        DataCaptureGameDTO selectedProcess = null;
        Integer processId = request.getId();
        if (processId != null && processId > 0) {
            selectedProcess = loadProcessDetail(tenantId, processId);
        }

        DataCaptureGameDTO response = new DataCaptureGameDTO();
        response.setTenantId(tenantId);
        response.setCaptureDate(date);
        response.setProcesses(processes);
        response.setSelectedProcess(selectedProcess);
        return response;
    }

    @Override
    @Transactional
    public DataCaptureBankDTO saveBankDraft(DataCaptureBankDTO request) {
        SessionUser session = requireLogin();
        AccessControlUtils.requireWritable(session);
        if (request == null) {
            throw new BusinessException("Request body is required");
        }

        Integer tenantId = request.getTenantId();
        Integer currencyId = request.getCurrencyId();
        requireTenantId(tenantId);
        requireCurrencyId(currencyId);

        String processCode = normalizeBankProcessCode(request.getProcessCode());
        if ("PROFIT".equals(processCode)) {
            throw new BusinessException("PROFIT process is not saved to draft");
        }
        if (!BANK_DRAFT_PROCESS_CODES.contains(processCode)) {
            throw new BusinessException("Invalid bank process code for draft: " + processCode);
        }

        List<DataCaptureBankDTO.Cell> cells = normalizeCells(request);
        if (cells.isEmpty()) {
            throw new BusinessException("Draft cells are required");
        }

        Process process = ensureBankProcess(tenantId, processCode, currencyId, session);
        DataCaptureDraft draft = dataCaptureDao.findDraftByTenantProcessCurrency(
                tenantId, process.getId(), currencyId);
        if (draft == null) {
            draft = new DataCaptureDraft();
            draft.setTenantId(tenantId);
            draft.setProcessId(process.getId());
            draft.setCurrencyId(currencyId);
            dataCaptureDao.insertDraft(draft);
        } else {
            dataCaptureDao.touchDraftUpdatedAt(draft.getId());
        }

        dataCaptureDao.deleteDraftCellsByDraftId(draft.getId());
        List<DataCaptureDraftCell> rows = new ArrayList<>(cells.size());
        for (DataCaptureBankDTO.Cell cell : cells) {
            DataCaptureDraftCell row = new DataCaptureDraftCell();
            row.setDraftId(draft.getId());
            row.setRowIndex(cell.getRowIndex());
            row.setColIndex(cell.getColIndex());
            row.setCellValue(cell.getCellValue());
            rows.add(row);
        }
        dataCaptureDao.insertDraftCells(rows);

        DataCaptureBankDTO response = new DataCaptureBankDTO();
        response.setTenantId(tenantId);
        response.setProcessCode(processCode);
        response.setProcessId(process.getId());
        response.setCurrencyId(currencyId);
        response.setCells(cells);
        response.setTableData(buildTableData(cells));
        return response;
    }

    @Override
    public DataCaptureBankDTO getBankDraft(DataCaptureBankDTO request) {
        requireLogin();
        if (request == null) {
            throw new BusinessException("Request body is required");
        }

        Integer tenantId = request.getTenantId();
        Integer currencyId = request.getCurrencyId();
        requireTenantId(tenantId);
        requireCurrencyId(currencyId);

        String processCode = normalizeBankProcessCode(request.getProcessCode());
        if ("PROFIT".equals(processCode)) {
            return emptyDraftResponse(tenantId, processCode, currencyId);
        }
        if (!BANK_DRAFT_PROCESS_CODES.contains(processCode)) {
            throw new BusinessException("Invalid bank process code for draft: " + processCode);
        }

        Process process = dataCaptureDao.findBankProcessByTenantAndCode(tenantId, processCode);
        if (process == null || process.getId() == null) {
            return emptyDraftResponse(tenantId, processCode, currencyId);
        }

        DataCaptureDraft draft = dataCaptureDao.findDraftByTenantProcessCurrency(
                tenantId, process.getId(), currencyId);
        if (draft == null) {
            DataCaptureBankDTO empty = emptyDraftResponse(tenantId, processCode, currencyId);
            empty.setProcessId(process.getId());
            return empty;
        }

        List<DataCaptureDraftCell> stored = dataCaptureDao.findDraftCellsByDraftId(draft.getId());
        List<DataCaptureBankDTO.Cell> cells = new ArrayList<>();
        if (stored != null) {
            for (DataCaptureDraftCell row : stored) {
                DataCaptureBankDTO.Cell cell = new DataCaptureBankDTO.Cell();
                cell.setRowIndex(row.getRowIndex());
                cell.setColIndex(row.getColIndex());
                cell.setCellValue(row.getCellValue());
                cells.add(cell);
            }
        }

        DataCaptureBankDTO response = new DataCaptureBankDTO();
        response.setTenantId(tenantId);
        response.setProcessCode(processCode);
        response.setProcessId(process.getId());
        response.setCurrencyId(currencyId);
        response.setCells(cells);
        response.setTableData(cells.isEmpty() ? null : buildTableData(cells));
        return response;
    }

    private Process ensureBankProcess(
            Integer tenantId,
            String processCode,
            Integer currencyId,
            SessionUser session) {
        Process existing = dataCaptureDao.findBankProcessByTenantAndCode(tenantId, processCode);
        if (existing != null && existing.getId() != null) {
            return existing;
        }
        Process created = new Process();
        created.setTenantId(tenantId);
        created.setCategory(Process.Category.BANK);
        created.setCode(processCode);
        created.setCurrencyId(currencyId);
        created.setStatus(Process.Status.ACTIVE);
        created.setCreatedBy(session.login_id);
        dataCaptureDao.insertBankProcess(created);
        return created;
    }

    private List<DataCaptureGameDTO> loadProcessesByDay(
            Integer tenantId,
            LocalDate captureDate,
            int dayOfWeek) {
        List<DataCaptureGameDTO> rows = dataCaptureDao.findGameProcessesByDay(tenantId, captureDate, dayOfWeek);
        return rows != null ? rows : Collections.emptyList();
    }

    private DataCaptureGameDTO loadProcessDetail(Integer tenantId, Integer processId) {
        DataCaptureGameDTO detail = dataCaptureDao.findGameProcessDetail(tenantId, processId);
        if (detail == null) {
            throw new BusinessException("Process not found");
        }
        return detail;
    }

    private static DataCaptureBankDTO emptyDraftResponse(
            Integer tenantId,
            String processCode,
            Integer currencyId) {
        DataCaptureBankDTO response = new DataCaptureBankDTO();
        response.setTenantId(tenantId);
        response.setProcessCode(processCode);
        response.setCurrencyId(currencyId);
        response.setCells(Collections.emptyList());
        response.setTableData(null);
        return response;
    }

    private static List<DataCaptureBankDTO.Cell> normalizeCells(DataCaptureBankDTO request) {
        List<DataCaptureBankDTO.Cell> fromRequest = request.getCells();
        if (fromRequest != null && !fromRequest.isEmpty()) {
            List<DataCaptureBankDTO.Cell> out = new ArrayList<>();
            for (DataCaptureBankDTO.Cell cell : fromRequest) {
                if (cell == null) continue;
                Integer rowIndex = cell.getRowIndex();
                Integer colIndex = cell.getColIndex();
                String value = cell.getCellValue() != null ? cell.getCellValue().trim() : "";
                if (rowIndex == null || rowIndex < 0 || colIndex == null || colIndex < 1 || value.isEmpty()) {
                    continue;
                }
                DataCaptureBankDTO.Cell normalized = new DataCaptureBankDTO.Cell();
                normalized.setRowIndex(rowIndex);
                normalized.setColIndex(colIndex);
                normalized.setCellValue(value);
                out.add(normalized);
            }
            return out;
        }
        return extractCellsFromTableData(request.getTableData());
    }

    @SuppressWarnings("unchecked")
    private static List<DataCaptureBankDTO.Cell> extractCellsFromTableData(Map<String, Object> tableData) {
        if (tableData == null) {
            return Collections.emptyList();
        }
        Object rowsObj = tableData.get("rows");
        if (!(rowsObj instanceof List<?> rows) || rows.isEmpty()) {
            return Collections.emptyList();
        }
        List<DataCaptureBankDTO.Cell> out = new ArrayList<>();
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            Object rowObj = rows.get(rowIndex);
            if (!(rowObj instanceof List<?> row)) continue;
            for (Object cellObj : row) {
                if (!(cellObj instanceof Map<?, ?> cellMap)) continue;
                Object type = cellMap.get("type");
                if (type == null || !"data".equalsIgnoreCase(String.valueOf(type))) continue;
                Object valueObj = cellMap.get("value");
                String value = valueObj != null ? String.valueOf(valueObj).trim() : "";
                if (value.isEmpty()) continue;
                int colIndex;
                Object colObj = cellMap.get("col");
                if (colObj instanceof Number n) {
                    colIndex = n.intValue() + 1;
                } else {
                    continue;
                }
                if (colIndex < 1) continue;
                DataCaptureBankDTO.Cell cell = new DataCaptureBankDTO.Cell();
                cell.setRowIndex(rowIndex);
                cell.setColIndex(colIndex);
                cell.setCellValue(value);
                out.add(cell);
            }
        }
        return out;
    }

    private static Map<String, Object> buildTableData(List<DataCaptureBankDTO.Cell> cells) {
        int maxRow = 0;
        int maxCol = 0;
        for (DataCaptureBankDTO.Cell cell : cells) {
            maxRow = Math.max(maxRow, cell.getRowIndex());
            maxCol = Math.max(maxCol, cell.getColIndex());
        }
        int rowCount = Math.max(maxRow + 1, 1);
        int colCount = Math.max(maxCol, 1);

        List<String> headers = new ArrayList<>(colCount + 1);
        headers.add("");
        for (int c = 1; c <= colCount; c++) {
            headers.add(String.valueOf(c));
        }

        String[][] values = new String[rowCount][colCount];
        for (DataCaptureBankDTO.Cell cell : cells) {
            int r = cell.getRowIndex();
            int c = cell.getColIndex() - 1;
            if (r >= 0 && r < rowCount && c >= 0 && c < colCount) {
                values[r][c] = cell.getCellValue();
            }
        }

        List<List<Map<String, Object>>> rows = new ArrayList<>(rowCount);
        for (int r = 0; r < rowCount; r++) {
            List<Map<String, Object>> row = new ArrayList<>(colCount + 1);
            Map<String, Object> header = new LinkedHashMap<>();
            header.put("type", "header");
            header.put("value", rowLabel(r));
            row.add(header);
            for (int c = 0; c < colCount; c++) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("type", "data");
                data.put("value", values[r][c] != null ? values[r][c] : "");
                data.put("col", c);
                row.add(data);
            }
            rows.add(row);
        }

        Map<String, Object> tableData = new LinkedHashMap<>();
        tableData.put("headers", headers);
        tableData.put("rows", rows);
        tableData.put("rowCount", rowCount);
        tableData.put("colCount", colCount + 1);
        return tableData;
    }



    private static String rowLabel(int rowIndex) {
        int n = rowIndex;
        StringBuilder sb = new StringBuilder();
        do {
            sb.insert(0, (char) ('A' + (n % 26)));
            n = n / 26 - 1;
        } while (n >= 0);
        return sb.toString();
    }

    private static String normalizeBankProcessCode(String raw) {
        String code = raw != null ? raw.trim().toUpperCase(Locale.ROOT) : "";
        if (code.isEmpty() || !BANK_PROCESS_CODES.contains(code)) {
            throw new BusinessException("processCode is required (PROFIT/SALARY/COMMISSION/BONUS)");
        }
        return code;
    }

    private static SessionUser requireLogin() {
        SessionUser sessionUser = SecurityUtils.currentUser();
        if (sessionUser == null) {
            throw new BusinessException("Not logged in");
        }
        return sessionUser;
    }

    private static void requireTenantId(Integer tenantId) {
        if (tenantId == null || tenantId <= 0) {
            throw new BusinessException("tenantId is required");
        }
    }

    private static void requireCurrencyId(Integer currencyId) {
        if (currencyId == null || currencyId <= 0) {
            throw new BusinessException("currencyId is required");
        }
    }

    private static LocalDate requireCaptureDate(LocalDate captureDate) {
        if (captureDate == null) {
            throw new BusinessException("captureDate is required");
        }
        return captureDate;
    }

    //process_day.day_of_week: 1=Mon … 7=Sun.
    private static int toProcessDayOfWeek(LocalDate captureDate) {
        return captureDate.getDayOfWeek().getValue();
    }
}
