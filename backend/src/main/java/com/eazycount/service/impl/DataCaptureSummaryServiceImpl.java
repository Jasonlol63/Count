package com.eazycount.service.impl;

import com.eazycount.common.BusinessException;
import com.eazycount.dao.CurrencyDao;
import com.eazycount.dao.DataCaptureDao;
import com.eazycount.dao.DataCaptureSummaryDao;
import com.eazycount.dao.ProcessDao;
import com.eazycount.dto.DataCaptureSummaryDTO;
import com.eazycount.entity.Currency;
import com.eazycount.entity.DataCaptureFormula;
import com.eazycount.entity.Process;
import com.eazycount.security.SecurityUtils;
import com.eazycount.security.SessionUser;
import com.eazycount.service.DataCaptureSummaryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class DataCaptureSummaryServiceImpl implements DataCaptureSummaryService {

    private static final Set<String> BANK_PROCESS_CODES = Set.of("PROFIT", "SALARY", "COMMISSION", "BONUS");

    @Autowired
    private DataCaptureSummaryDao dataCaptureSummaryDao;

    @Autowired
    private DataCaptureDao dataCaptureDao;

    @Autowired
    private ProcessDao processDao;

    @Autowired
    private CurrencyDao currencyDao;

    @Override
    @Transactional
    public DataCaptureSummaryDTO saveAddFormula(DataCaptureSummaryDTO request) {
        SessionUser session = requireLogin();
        if (request == null) {
            throw new BusinessException("Request body is required");
        }

        Integer tenantId = request.getTenantId();
        Integer accountId = request.getAccountId();
        Integer currencyId = request.getCurrencyId();
        String idProduct = trimToNull(request.getIdProduct());
        String formula = trimToNull(request.getFormula());
        if (formula == null) {
            formula = trimToNull(request.getFormulaOperators());
        }

        requireTenantId(tenantId);
        if (idProduct == null) {
            throw new BusinessException("Product Id is required");
        }
        if (accountId == null || accountId <= 0) {
            throw new BusinessException("Account Id is required");
        }
        if (currencyId == null || currencyId <= 0) {
            throw new BusinessException("Currency Id is required");
        }
        if (formula == null) {
            throw new BusinessException("Formula is required");
        }

        Process process = resolveProcess(tenantId, request.getProcessId(), request.getProcessCode(), currencyId, session);
        Integer processId = process.getId();

        Currency currency = currencyDao.findByIdAndTenantId(currencyId, tenantId);
        if (currency == null || currency.getId() == null) {
            throw new BusinessException("Currency not found");
        }

        int accountCount = currencyDao.countAccountsInTenant(tenantId, List.of(accountId));
        if (accountCount < 1) {
            throw new BusinessException("Account not found");
        }

        String loginId = session.login_id != null ? session.login_id : "";
        String sourcePercent = trimToNull(request.getSourcePercent());
        if (sourcePercent == null) {
            sourcePercent = "0";
        }
        boolean enableSourcePercent = request.getEnableSourcePercent() == null
                || Boolean.TRUE.equals(request.getEnableSourcePercent());
        boolean enableInputMethod = Boolean.TRUE.equals(request.getEnableInputMethod())
                || trimToNull(request.getInputMethod()) != null;
        String formulaOperators = trimToNull(request.getFormulaOperators());
        if (formulaOperators == null) {
            formulaOperators = formula;
        }

        // Prefer MAIN when product has no main-row data; otherwise add SUB under that product.
        DataCaptureFormula mainWithAccount =
                dataCaptureSummaryDao.findMainWithAccount(tenantId, processId, idProduct);

        DataCaptureSummaryDTO saved;
        if (mainWithAccount == null) {
            saved = saveAsMain(request, tenantId, processId, idProduct, accountId, currencyId, formula, formulaOperators,
                    sourcePercent, enableSourcePercent, enableInputMethod, loginId);
        } else {
            saved = saveAsSub(request, tenantId, processId, idProduct, accountId, currencyId, formula, formulaOperators,
                    sourcePercent, enableSourcePercent, enableInputMethod, loginId);
        }
        saved.setProcessCode(process.getCode());
        return saved;
    }

    /* Resolve Bank Process Type Only Have Process Code */
    private Process resolveProcess(Integer tenantId, Integer processId, String processCode, Integer currencyId, SessionUser session) {
        if (processId != null && processId > 0) {
            Process byId = processDao.findProcessByIdAndTenantId(processId, tenantId);
            if (byId != null && byId.getId() != null) {
                return byId;
            }
        }

        String code = trimToNull(processCode);
        if (code != null) {
            code = code.toUpperCase(Locale.ROOT);
            if (BANK_PROCESS_CODES.contains(code)) {
                return ensureBankProcess(tenantId, code, currencyId, session);
            }
            Process game = processDao.findProcessCodeByTenantId(tenantId, Process.Category.GAME, code);
            if (game != null && game.getId() != null) {
                return game;
            }
            throw new BusinessException("Process not found: " + code);
        }

        throw new BusinessException("Process Id is required");
    }

    private Process ensureBankProcess(Integer tenantId, String processCode, Integer currencyId, SessionUser session) {
        Process existing = dataCaptureDao.findBankProcessByTenantAndCode(tenantId, processCode);
        if (existing != null && existing.getId() != null) {
            return existing;
        }
        if (currencyId == null || currencyId <= 0) {
            throw new BusinessException("Currency Id is required to create 'BANK' process");
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

    private DataCaptureSummaryDTO saveAsMain(DataCaptureSummaryDTO request, Integer tenantId, Integer processId,
                                             String idProduct, Integer accountId, Integer currencyId,
                                             String formula, String formulaOperators, String sourcePercent,
                                             boolean enableSourcePercent, boolean enableInputMethod, String loginId) {

        DataCaptureFormula existingMain =
                dataCaptureSummaryDao.findMainByProduct(tenantId, processId, idProduct);

        DataCaptureFormula row = existingMain != null ? existingMain : new DataCaptureFormula();
        row.setTenantId(tenantId);
        row.setProcessId(processId);
        row.setProductType(DataCaptureFormula.ProductType.MAIN);
        row.setIdProduct(idProduct);
        row.setParentIdProduct(null);
        row.setSubOrder(null);
        row.setFormulaVariant(existingMain != null && existingMain.getFormulaVariant() != null
                ? existingMain.getFormulaVariant()
                : 1);
        row.setRowIndex(request.getRowIndex());
        row.setAccountId(accountId);
        row.setCurrencyId(currencyId);
        row.setDescription(trimToEmpty(request.getDescription()));
        row.setSourceColumns(trimToNull(request.getSourceColumns()));
        row.setColumnsDisplay(trimToNull(request.getColumnsDisplay()));
        row.setFormula(formula);
        row.setFormulaOperators(formulaOperators);
        row.setInputMethod(trimToNull(request.getInputMethod()));
        row.setSourcePercent(sourcePercent);
        row.setEnableSourcePercent(enableSourcePercent);
        row.setEnableInputMethod(enableInputMethod);
        row.setUpdatedBy(loginId);

        if (existingMain != null && existingMain.getId() != null) {
            dataCaptureSummaryDao.updateMainFields(row);
        } else {
            row.setCreatedBy(loginId);
            dataCaptureSummaryDao.insertFormula(row);
        }

        return toResponse(row, request);
    }

    private DataCaptureSummaryDTO saveAsSub(DataCaptureSummaryDTO request, Integer tenantId, Integer processId,
                                            String idProduct, Integer accountId, Integer currencyId, String formula,
                                            String formulaOperators, String sourcePercent, boolean enableSourcePercent,
                                            boolean enableInputMethod, String loginId) {

        BigDecimal maxSubOrder = dataCaptureSummaryDao.findMaxSubOrder(tenantId, processId, idProduct);
        BigDecimal nextSubOrder = maxSubOrder == null
                ? BigDecimal.ONE
                : maxSubOrder.add(BigDecimal.ONE);

        DataCaptureFormula row = new DataCaptureFormula();
        row.setTenantId(tenantId);
        row.setProcessId(processId);
        row.setProductType(DataCaptureFormula.ProductType.SUB);
        row.setIdProduct(idProduct);
        row.setParentIdProduct(idProduct);
        row.setFormulaVariant(1);
        row.setSubOrder(nextSubOrder);
        row.setRowIndex(request.getRowIndex());
        row.setAccountId(accountId);
        row.setCurrencyId(currencyId);
        row.setDescription(trimToEmpty(request.getDescription()));
        row.setSourceColumns(trimToNull(request.getSourceColumns()));
        row.setColumnsDisplay(trimToNull(request.getColumnsDisplay()));
        row.setFormula(formula);
        row.setFormulaOperators(formulaOperators);
        row.setInputMethod(trimToNull(request.getInputMethod()));
        row.setSourcePercent(sourcePercent);
        row.setEnableSourcePercent(enableSourcePercent);
        row.setEnableInputMethod(enableInputMethod);
        row.setCreatedBy(loginId);
        row.setUpdatedBy(loginId);
        dataCaptureSummaryDao.insertFormula(row);

        return toResponse(row, request);
    }

    @Override
    @Transactional
    public DataCaptureSummaryDTO updateFormula(DataCaptureSummaryDTO request) {
        SessionUser session = requireLogin();
        if (request == null) {
            throw new BusinessException("Request body is required");
        }

        Integer tenantId = request.getTenantId();
        requireTenantId(tenantId);

        Integer accountId = request.getAccountId();
        Integer currencyId = request.getCurrencyId();
        if (accountId == null || accountId <= 0) {
            throw new BusinessException("Account Id is required");
        }
        if (currencyId == null || currencyId <= 0) {
            throw new BusinessException("Currency Id is required");
        }

        Currency currency = currencyDao.findByIdAndTenantId(currencyId, tenantId);
        if (currency == null || currency.getId() == null) {
            throw new BusinessException("Currency not found");
        }
        int accountCount = currencyDao.countAccountsInTenant(tenantId, List.of(accountId));
        if (accountCount < 1) {
            throw new BusinessException("Account not found");
        }

        // Bank Summary often has processCode (SALARY) without numeric processId / templateId.
        Process process = resolveProcess(tenantId, request.getProcessId(), request.getProcessCode(), currencyId, session);
        Integer processId = process.getId();

        DataCaptureFormula existing = resolveExistingForUpdate(request, tenantId, processId, accountId);
        if (existing == null || existing.getId() == null) {
            throw new BusinessException("Formula not found");
        }

        String formula = trimToNull(request.getFormula());
        if (formula == null) {
            formula = trimToNull(request.getFormulaOperators());
        }
        if (formula == null) {
            formula = trimToNull(existing.getFormula());
        }
        if (formula == null) {
            formula = trimToNull(existing.getFormulaOperators());
        }
        if (formula == null) {
            throw new BusinessException("formula is required");
        }

        String formulaOperators = trimToNull(request.getFormulaOperators());
        if (formulaOperators == null) {
            formulaOperators = formula;
        }

        String sourcePercent = trimToNull(request.getSourcePercent());
        if (sourcePercent == null) {
            sourcePercent = trimToNull(existing.getSourcePercent());
        }
        if (sourcePercent == null) {
            sourcePercent = "0";
        }

        boolean enableSourcePercent = request.getEnableSourcePercent() != null
                ? Boolean.TRUE.equals(request.getEnableSourcePercent())
                : (existing.getEnableSourcePercent() == null || Boolean.TRUE.equals(existing.getEnableSourcePercent()));
        boolean enableInputMethod = request.getEnableInputMethod() != null
                ? Boolean.TRUE.equals(request.getEnableInputMethod())
                : Boolean.TRUE.equals(existing.getEnableInputMethod());

        String description = request.getDescription() != null
                ? trimToEmpty(request.getDescription())
                : trimToEmpty(existing.getDescription());
        String sourceColumns = request.getSourceColumns() != null
                ? trimToNull(request.getSourceColumns())
                : existing.getSourceColumns();
        String columnsDisplay = request.getColumnsDisplay() != null
                ? trimToNull(request.getColumnsDisplay())
                : existing.getColumnsDisplay();
        String inputMethod = request.getInputMethod() != null
                ? trimToNull(request.getInputMethod())
                : existing.getInputMethod();
        Integer rowIndex = request.getRowIndex() != null ? request.getRowIndex() : existing.getRowIndex();

        // Identity fields stay on existing row — SUB edit never touches MAIN.
        existing.setAccountId(accountId);
        existing.setCurrencyId(currencyId);
        existing.setDescription(description);
        existing.setSourceColumns(sourceColumns);
        existing.setColumnsDisplay(columnsDisplay);
        existing.setFormula(formula);
        existing.setFormulaOperators(formulaOperators);
        existing.setInputMethod(inputMethod);
        existing.setSourcePercent(sourcePercent);
        existing.setEnableSourcePercent(enableSourcePercent);
        existing.setEnableInputMethod(enableInputMethod);
        existing.setRowIndex(rowIndex);
        existing.setUpdatedBy(session.login_id != null ? session.login_id : "");

        dataCaptureSummaryDao.updateFormulaById(existing);
        DataCaptureSummaryDTO saved = toResponse(existing, request);
        saved.setProcessCode(process.getCode());
        return saved;
    }

    @Override
    @Transactional
    public DataCaptureSummaryDTO deleteFormulas(DataCaptureSummaryDTO request) {
        requireLogin();
        if (request == null) {
            throw new BusinessException("Request body is required");
        }

        Integer tenantId = request.getTenantId();
        requireTenantId(tenantId);

        List<DataCaptureSummaryDTO> items = request.getItems();
        if (items == null || items.isEmpty()) {
            throw new BusinessException("items are required");
        }

        Process process = findProcessForDelete(tenantId, request.getProcessId(), request.getProcessCode());
        Integer processId = process.getId();

        Set<Integer> deletedIds = new LinkedHashSet<>();
        for (DataCaptureSummaryDTO item : items) {
            if (item == null) {
                continue;
            }
            DataCaptureFormula existing = resolveExistingForDelete(item, tenantId, processId);
            if (existing == null || existing.getId() == null) {
                continue;
            }
            int removed = dataCaptureSummaryDao.deleteByIdAndTenantId(existing.getId(), tenantId);
            if (removed > 0) {
                deletedIds.add(existing.getId());
            }
        }

        DataCaptureSummaryDTO result = new DataCaptureSummaryDTO();
        result.setDeletedIds(new ArrayList<>(deletedIds));
        result.setDeletedCount(deletedIds.size());
        return result;
    }

    /* Prefer formula id; else business key. Missing rows skipped (idempotent). */
    private DataCaptureFormula resolveExistingForDelete(DataCaptureSummaryDTO item, Integer tenantId, Integer processId) {
        Integer id = item.getId();
        if (id != null && id > 0) {
            DataCaptureFormula byId = dataCaptureSummaryDao.findByIdAndTenantId(id, tenantId);
            if (byId != null && byId.getId() != null) {
                return byId;
            }
        }

        Integer accountId = item.getAccountId();
        String idProduct = trimToNull(item.getIdProduct());
        if (accountId == null || accountId <= 0 || idProduct == null) {
            return null;
        }

        String productTypeRaw = trimToNull(item.getProductType());
        String productType = productTypeRaw != null && productTypeRaw.equalsIgnoreCase("SUB")
                ? "SUB"
                : "MAIN";
        String parentIdProduct = trimToNull(item.getParentIdProduct());
        if ("SUB".equals(productType) && parentIdProduct == null) {
            parentIdProduct = idProduct;
        }

        return dataCaptureSummaryDao.findByBusinessKey(tenantId, processId, productType, idProduct, parentIdProduct, accountId, item.getSubOrder());
    }

    /* Lookup process for delete — never auto-creates Bank process rows. */
    private Process findProcessForDelete(Integer tenantId, Integer processId, String processCode) {
        if (processId != null && processId > 0) {
            Process byId = processDao.findProcessByIdAndTenantId(processId, tenantId);
            if (byId != null && byId.getId() != null) {
                return byId;
            }
        }

        String code = trimToNull(processCode);
        if (code != null) {
            code = code.toUpperCase(Locale.ROOT);
            if (BANK_PROCESS_CODES.contains(code)) {
                Process bank = dataCaptureDao.findBankProcessByTenantAndCode(tenantId, code);
                if (bank != null && bank.getId() != null) {
                    return bank;
                }
                throw new BusinessException("Process not found: " + code);
            }
            Process game = processDao.findProcessCodeByTenantId(tenantId, Process.Category.GAME, code);
            if (game != null && game.getId() != null) {
                return game;
            }
            throw new BusinessException("Process not found: " + code);
        }

        throw new BusinessException("Process Id is required");
    }

    /* Prefer formula id; Bank UI often loses templateId after refresh — fall back to a business key. */
    private DataCaptureFormula resolveExistingForUpdate(DataCaptureSummaryDTO request, Integer tenantId, Integer processId, Integer accountId) {
        Integer id = request.getId();
        if (id != null && id > 0) {
            DataCaptureFormula byId = dataCaptureSummaryDao.findByIdAndTenantId(id, tenantId);
            if (byId != null && byId.getId() != null) {
                return byId;
            }
        }

        String idProduct = trimToNull(request.getIdProduct());
        if (idProduct == null) {
            throw new BusinessException("idProduct is required when formula id is missing");
        }

        String productTypeRaw = trimToNull(request.getProductType());
        String productType = productTypeRaw != null && productTypeRaw.equalsIgnoreCase("SUB")
                ? "SUB"
                : "MAIN";
        String parentIdProduct = trimToNull(request.getParentIdProduct());
        if ("SUB".equals(productType) && parentIdProduct == null) {
            parentIdProduct = idProduct;
        }

        return dataCaptureSummaryDao.findByBusinessKey(tenantId, processId, productType, idProduct, parentIdProduct, accountId, request.getSubOrder());
    }

    private static DataCaptureSummaryDTO toResponse(DataCaptureFormula row, DataCaptureSummaryDTO request) {
        DataCaptureSummaryDTO response = new DataCaptureSummaryDTO();
        response.setId(row.getId());
        response.setTenantId(row.getTenantId());
        response.setProcessId(row.getProcessId());
        response.setProcessCode(request.getProcessCode());
        response.setIdProduct(row.getIdProduct());
        response.setProductType(row.getProductType() != null ? row.getProductType().name() : null);
        response.setParentIdProduct(row.getParentIdProduct());
        response.setFormulaVariant(row.getFormulaVariant());
        response.setSubOrder(row.getSubOrder());
        response.setRowIndex(row.getRowIndex());
        response.setAccountId(row.getAccountId());
        response.setAccountDisplay(request.getAccountDisplay());
        response.setCurrencyId(row.getCurrencyId());
        response.setCurrencyDisplay(request.getCurrencyDisplay());
        response.setDescription(row.getDescription());
        response.setSourceColumns(row.getSourceColumns());
        response.setColumnsDisplay(row.getColumnsDisplay());
        response.setFormula(row.getFormula());
        response.setFormulaOperators(row.getFormulaOperators());
        response.setInputMethod(row.getInputMethod());
        response.setSourcePercent(row.getSourcePercent());
        response.setEnableSourcePercent(row.getEnableSourcePercent());
        response.setEnableInputMethod(row.getEnableInputMethod());
        return response;
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
            throw new BusinessException("Tenant Id is required");
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
