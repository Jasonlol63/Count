package com.eazycount.service.impl;

import com.eazycount.common.BusinessException;
import com.eazycount.dao.CurrencyDao;
import com.eazycount.dao.DomainDao;
import com.eazycount.dao.DomainListFeePriceDao;
import com.eazycount.dao.TenantFeeShareAllocateDao;
import com.eazycount.dao.UserDao;
import com.eazycount.dto.DomainDTO;
import com.eazycount.dto.DomainFeeSettingsDTO;
import com.eazycount.dto.OwnerTenantDTO;
import com.eazycount.dto.UserListDTO;
import com.eazycount.entity.*;
import com.eazycount.security.SecurityUtils;
import com.eazycount.security.SessionUser;
import com.eazycount.service.DomainService;
import com.eazycount.util.DomainFeeSettingsMapper;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class DomainServiceImpl implements DomainService {

    private static final int DEFAULT_GROUP_MODULE_ID = 1;

    @Autowired
    private DomainDao domainDao;

    @Autowired
    private UserDao userDao;

    @Autowired
    private CurrencyDao currencyDao;

    @Autowired
    private TenantFeeShareAllocateDao feeShareAllocateDao;

    @Autowired
    private DomainListFeePriceDao domainListFeePriceDao;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Override
    public List<TenantFeeShareAllocate> findFeeShareByTenantId(Integer tenantId) {
        SessionUser session = SecurityUtils.currentUser();
        if (session == null) {
            throw new BusinessException("Not logged in");
        }

        if (tenantId == null || tenantId <= 0) {
            throw new BusinessException("Invalid Tenant ID");
        }

        List<TenantFeeShareAllocate> rows = feeShareAllocateDao.findFeeShareByTenantId(tenantId);
        return rows != null ? rows : List.of();
    }

    @Override
    @Transactional
    public void batchInsert(List<TenantFeeShareAllocate> list) {
        SessionUser session = SecurityUtils.currentUser();
        if (session == null) {
            throw new BusinessException("Not logged in");
        }

        if (list == null || list.isEmpty()) {
            return;
        }

        validateAndPrepareFeeShareRows(list);
        feeShareAllocateDao.batchInsert(list);
    }

    @Override
    @Transactional
    public void deleteByTenantId(Integer tenantId) {
        SessionUser session = SecurityUtils.currentUser();
        if (session == null) {
            throw new BusinessException("Not logged in");
        }

        if (tenantId == null || tenantId <= 0) {
            throw new BusinessException("Invalid Tenant ID");
        }

        feeShareAllocateDao.deleteByTenantId(tenantId);
    }

    private void validateAndPrepareFeeShareRows(List<TenantFeeShareAllocate> list) {
        Map<TenantFeeShareAllocate.ShareType, BigDecimal> totalsByShareType = new EnumMap<>(
                TenantFeeShareAllocate.ShareType.class);

        for (int i = 0; i < list.size(); i++) {
            TenantFeeShareAllocate row = list.get(i);
            if (row == null) {
                throw new BusinessException("Invalid fee share row");
            }

            if (row.getTenantId() == null || row.getTenantId() <= 0) {
                throw new BusinessException("Tenant ID is required for fee share row");
            }

            if (row.getShareType() == null) {
                throw new BusinessException("Share type is required");
            }

            if (row.getOwnerType() == null || row.getOwnerType().isBlank()) {
                row.setOwnerType("owner");
            } else {
                row.setOwnerType(row.getOwnerType().trim().toLowerCase());
            }

            BigDecimal percentage = row.getPercentage() != null ? row.getPercentage() : BigDecimal.ZERO;
            if (percentage.compareTo(BigDecimal.ZERO) < 0 || percentage.compareTo(new BigDecimal("100")) > 0) {
                throw new BusinessException("Percentage must be between 0 and 100");
            }
            row.setPercentage(percentage);

            if (row.getSortOrder() == null) {
                row.setSortOrder(i);
            }

            row.setId(null);

            if ("group".equals(row.getOwnerType())) {
                if (row.getPartnerTenantId() == null || row.getPartnerTenantId() <= 0) {
                    throw new BusinessException("Partner tenant is required for group fee share");
                }
            } else if (!"owner".equals(row.getOwnerType()) && !"user".equals(row.getOwnerType())) {
                throw new BusinessException("Owner type must be owner, user, or group");
            } else if (row.getAccountId() == null || row.getAccountId() <= 0) {
                throw new BusinessException("Account is required for fee share allocation");
            }

            totalsByShareType.merge(row.getShareType(), percentage, BigDecimal::add);
        }

        for (Map.Entry<TenantFeeShareAllocate.ShareType, BigDecimal> entry : totalsByShareType.entrySet()) {
            if (entry.getValue().compareTo(new BigDecimal("100")) > 0) {
                throw new BusinessException("Total " + entry.getKey() + " allocation exceeds 100%");
            }
        }
    }

    @Override
    public List<FeatureModule> findFeatureModulesByTenantId(Integer tenantId) {
        SessionUser session = SecurityUtils.currentUser();
        if (session == null) {
            throw new BusinessException("Not logged in");
        }

        if (tenantId == null || tenantId <= 0) {
            throw new BusinessException("Invalid Tenant ID");
        }

        List<FeatureModule> modules = domainDao.findFeatureModulesByTenantId(tenantId);
        return modules != null ? modules : List.of();
    }

    @Override
    @Transactional
    public void batchInsertFeatureModules(List<TenantFeatureModule> list) {
        SessionUser session = SecurityUtils.currentUser();
        if (session == null) {
            throw new BusinessException("Not logged in");
        }

        if (list == null || list.isEmpty()) {
            return;
        }

        validateAndPrepareFeatureModuleRows(list);
        domainDao.batchInsertFeatureModules(list);
    }

    @Override
    @Transactional
    public void deleteFeatureModulesByTenantId(Integer tenantId) {
        SessionUser session = SecurityUtils.currentUser();
        if (session == null) {
            throw new BusinessException("Not logged in");
        }

        if (tenantId == null || tenantId <= 0) {
            throw new BusinessException("Invalid Tenant ID");
        }

        domainDao.deleteFeatureModulesByTenantId(tenantId);
    }

    private void validateAndPrepareFeatureModuleRows(List<TenantFeatureModule> list) {
        List<FeatureModule> activeModules = domainDao.findAllActiveFeatureModules();
        Set<Integer> activeModuleIds = activeModules == null ? Set.of()
                : activeModules.stream()
                  .map(FeatureModule::getId)
                  .filter(id -> id != null && id > 0)
                  .collect(Collectors.toSet());

        Set<String> seenPairs = new HashSet<>();

        for (TenantFeatureModule row : list) {
            if (row == null) {
                throw new BusinessException("Invalid feature module row");
            }
            if (row.getTenantId() == null || row.getTenantId() <= 0) {
                throw new BusinessException("Tenant ID is required for feature module row");
            }
            if (row.getModuleId() == null || row.getModuleId() <= 0) {
                throw new BusinessException("Module ID is required for feature module row");
            }
            if (!activeModuleIds.contains(row.getModuleId())) {
                throw new BusinessException("Invalid or inactive feature module ID: " + row.getModuleId());
            }

            String pairKey = row.getTenantId() + ":" + row.getModuleId();
            if (!seenPairs.add(pairKey)) {
                throw new BusinessException("Duplicate feature module in batch: " + row.getModuleId());
            }

            row.setId(null);
            row.setCreatedAt(null);
        }
    }

    @Override
    public List<OwnerTenantDTO> findAllTenantsByOwner(Integer ownerId) {
        SessionUser session = SecurityUtils.currentUser();
        if (session == null) {
            throw new BusinessException("Not logged in");
        }

        List<OwnerTenantDTO> rows = domainDao.findAllTenantsByOwner(ownerId);
        if (rows != null) {
            for (OwnerTenantDTO dto : rows) {
                loadTenantAssociations(dto.getTenant());
            }
        }
        return rows;
    }

    @Override
    @Transactional
    public void insertOwnerDetails(Owner owner) {
        SessionUser session = SecurityUtils.currentUser();
        if (session == null) {
            throw new BusinessException("Not logged in");
        }

        if (owner == null) {
            throw new BusinessException("Invalid Owner");
        }

        if (owner.getOwnerCode() == null || owner.getOwnerCode().isBlank()) {
            throw new BusinessException("Owner Code is required");
        }

        Owner existing = domainDao.findOwnerByCode(owner.getOwnerCode().trim().toUpperCase());
        if (existing != null) {
            throw new BusinessException("Owner Code already exists!");
        }

        try {
            if (session.login_id != null) {
                owner.setCreatedBy(session.login_id);
            }

            owner.setOwnerCode(owner.getOwnerCode().trim().toUpperCase());
            owner.setName(owner.getName());
            owner.setEmail(owner.getEmail());
            owner.setPassword(passwordEncoder.encode(owner.getPassword()));
            owner.setSecondaryPassword(passwordEncoder.encode(owner.getSecondaryPassword()));
            domainDao.insertOwnerDetails(owner);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("Create Owner Failed!");
        }

    }

    @Override
    @Transactional
    public void insertTenantDetails(Tenant tenant) {
        SessionUser session = SecurityUtils.currentUser();
        if (session == null) {
            throw new BusinessException("Not logged in");
        }
        if (tenant == null) {
            throw new BusinessException("Invalid Tenant");
        }

        tenant.setStatus(Tenant.TenantStatus.ACTIVE);

        if (session.user_id != null) {
            tenant.setCreatedBy(session.login_id);
        }

        tenant.setId(tenant.getId());
        tenant.setTenantType(tenant.getTenantType());
        tenant.setCode(tenant.getCode());
        tenant.setName(tenant.getName());
        tenant.setOwnerId(tenant.getOwnerId());
        tenant.setParentId(tenant.getParentId());
        tenant.setExpirationDate(tenant.getExpirationDate());
        try {
            domainDao.insertTenantDetails(tenant);
        } catch (Exception e) {
            throw new BusinessException("Create Tenant Failed!");
        }
    }

    @Override
    @Transactional
    public void updateOwnerDetails(Owner owner) {
        SessionUser session = SecurityUtils.currentUser();
        if (session == null) {
            throw new BusinessException("Not logged in");
        }
        if (owner == null) {
            throw new BusinessException("Invalid Owner");
        }

        Owner find = domainDao.findOwnerById(owner.getId());
        if (find == null) {
            throw new BusinessException("Owner not found!");
        }

        if (owner.getOwnerCode() != null && !owner.getOwnerCode().isBlank()) {
            Owner existing = domainDao.findOwnerByCode(owner.getOwnerCode().trim().toUpperCase());
            if (existing != null && !existing.getId().equals(owner.getId())) {
                throw new BusinessException("Owner Code already exists!");
            }
        }

        try {
            owner.setName(owner.getName());
            owner.setEmail(owner.getEmail());

            if (owner.getPassword() != null && !owner.getPassword().isBlank()) {
                owner.setPassword(passwordEncoder.encode(owner.getPassword()));
            } else {
                owner.setPassword(find.getPassword());
            }

            if (owner.getSecondaryPassword() != null && !owner.getSecondaryPassword().isBlank()) {
                owner.setSecondaryPassword(passwordEncoder.encode(owner.getSecondaryPassword()));
            } else {
                owner.setSecondaryPassword(find.getSecondaryPassword());
            }

            domainDao.updateOwnerDetails(owner);
        } catch (Exception e) {
            throw new BusinessException("Update Owner Failed!");
        }
    }

    @Override
    @Transactional
    public void updateTenantDetails(Tenant tenant) {
        SessionUser session = SecurityUtils.currentUser();
        if (session == null) {
            throw new BusinessException("Not logged in");
        }
        if (tenant == null) {
            throw new BusinessException("Invalid Tenant");
        }

        Tenant findTenantOwner = domainDao.findOwnerTenantByIdAndOwnerId(tenant.getId(), tenant.getOwnerId());
        if (findTenantOwner == null) {
            throw new BusinessException("Invalid Tenant ID or Owner ID!");
        }

        try {
            tenant.setTenantType(tenant.getTenantType());
            tenant.setParentId(tenant.getParentId());
            domainDao.updateTenantDetails(tenant);
        } catch (Exception e) {
            throw new BusinessException("Update Tenant Failed!");
        }
    }

    @Override
    @Transactional
    public void updateTenantDetailsSetting(Tenant tenant) {
        SessionUser session = SecurityUtils.currentUser();
        if (session == null) {
            throw new BusinessException("Not logged in");
        }
        if (tenant == null) {
            throw new BusinessException("Invalid Tenant");
        }

        try {
            Tenant findTenantOwner = null;
            if (tenant.getId() != null) {
                findTenantOwner = domainDao.findOwnerTenantByIdAndOwnerId(tenant.getId(), tenant.getOwnerId());
            } else if (tenant.getCode() != null && tenant.getOwnerId() != null) {
                findTenantOwner = domainDao.findTenantByCodeAndOwnerId(tenant.getCode().trim().toUpperCase(),
                        tenant.getOwnerId());
            }

            if (findTenantOwner == null) {
                throw new BusinessException("Invalid Tenant ID or Owner ID!");
            }

            findTenantOwner.setCode(tenant.getCode());
            findTenantOwner.setName(tenant.getCode());
            findTenantOwner.setExpirationDate(tenant.getExpirationDate());
            domainDao.updateTenantDetails(findTenantOwner);

            Integer tenantId = findTenantOwner.getId();

            if (tenant.getFeatureModules() != null) {
                Tenant modulePatch = new Tenant();
                modulePatch.setId(tenantId);
                modulePatch.setFeatureModules(tenant.getFeatureModules());
                replaceFeatureModules(modulePatch);
            } else if (Tenant.TenantType.GROUP.equals(findTenantOwner.getTenantType())) {
                ensureDefaultGroupFeatureModule(tenantId);
            }

            if (tenant.getFeeShareAllocations() != null) {
                replaceFeeShareAllocations(tenantId, tenant.getFeeShareAllocations());
            }
        } catch (Exception e) {
            throw new BusinessException("Update Tenant Failed!");
        }
    }

    @Override
    @Transactional
    public void deleteOwnerDetails(Owner owner) {
        SessionUser session = SecurityUtils.currentUser();
        if (session == null) {
            throw new BusinessException("Not logged in");
        }
        if (owner == null) {
            throw new BusinessException("Invalid Owner");
        }

        List<OwnerTenantDTO> tenants = domainDao.findAllTenantsByOwner(owner.getId());
        if (tenants != null && !tenants.isEmpty()) {
            for (OwnerTenantDTO dto : tenants) {
                Tenant tenant = dto.getTenant();
                if (tenant != null && tenant.getId() != null) {
                    tenant.setOwnerId(owner.getId());
                    this.deleteTenantDetails(tenant);
                }
            }
        }

        try {
            domainDao.deleteOwnerDetails(owner);
        } catch (Exception e) {
            throw new BusinessException("Delete Owner Failed!");
        }
    }

    @Override
    @Transactional
    public void deleteTenantDetails(Tenant tenant) {
        SessionUser session = SecurityUtils.currentUser();
        if (session == null) {
            throw new BusinessException("Not logged in");
        }
        if (tenant == null) {
            throw new BusinessException("Invalid Tenant");
        }
        Tenant findTenantOwner = domainDao.findOwnerTenantByIdAndOwnerId(tenant.getId(), tenant.getOwnerId());
        if (findTenantOwner == null) {
            throw new BusinessException("Invalid Tenant ID or Owner ID!");
        }

        if (findTenantOwner.getCode().equals("C168")
                && findTenantOwner.getTenantType().equals(Tenant.TenantType.COMPANY)) {
            throw new BusinessException("C168 Company cannot be deleted!");
        }

        try {
            domainDao.deleteTenantDetails(tenant);
        } catch (Exception e) {
            throw new BusinessException("Delete Tenant Failed!");
        }
    }

    @Transactional
    @Override
    public DomainDTO createDomain(DomainDTO domainDTO) {

        Owner owner = new Owner();
        BeanUtils.copyProperties(domainDTO, owner);
        this.insertOwnerDetails(owner);
        Integer ownerId = owner.getId();
        domainDTO.setId(ownerId);

        Tenant c168Tenant = domainDao.findTenantByCodeAndOwnerId("C168", 1);

        Map<String, Integer> groupCodeToIdMap = new HashMap<>();
        if (domainDTO.getGroups() != null && !domainDTO.getGroups().isEmpty()) {
            for (Tenant group : domainDTO.getGroups()) {
                group.setOwnerId(ownerId);
                group.setTenantType(Tenant.TenantType.GROUP);
                group.setName(group.getCode());
                this.insertTenantDetails(group);
                groupCodeToIdMap.put(group.getCode(), group.getId());

                this.createAccountTenantInC168(group, c168Tenant.getId());
            }

        }

        if (domainDTO.getCompanies() != null && !domainDTO.getCompanies().isEmpty()) {
            for (Tenant company : domainDTO.getCompanies()) {
                company.setOwnerId(ownerId);
                company.setTenantType(Tenant.TenantType.COMPANY);
                String parentGroupCode = company.getParentGroupCode();
                if (parentGroupCode != null && !parentGroupCode.isBlank()) {
                    Integer parentId = groupCodeToIdMap.get(parentGroupCode.trim().toUpperCase());
                    if (parentId != null) {
                        company.setParentId(parentId);
                    }
                }

                company.setName(company.getCode());
                this.insertTenantDetails(company);

                this.createAccountTenantInC168(company, c168Tenant.getId());
            }
        }

        return domainDTO;
    }

    @Override
    @Transactional
    public DomainDTO updateDomain(DomainDTO domainDTO) {
        SessionUser session = SecurityUtils.currentUser();
        if (session == null) {
            throw new BusinessException("Not logged in");
        }
        if (domainDTO == null) {
            throw new BusinessException("Invalid Domain");
        }

        Owner owner = new Owner();
        BeanUtils.copyProperties(domainDTO, owner);
        this.updateOwnerDetails(owner);

        Integer ownerId = owner.getId();

        Set<String> incomingCodes = new HashSet<>();
        if (domainDTO.getGroups() != null) {
            for (Tenant g : domainDTO.getGroups()) {
                if (g.getCode() != null) {
                    incomingCodes.add(g.getCode().trim().toUpperCase());
                }
            }
        }
        if (domainDTO.getCompanies() != null) {
            for (Tenant c : domainDTO.getCompanies()) {
                if (c.getCode() != null) {
                    incomingCodes.add(c.getCode().trim().toUpperCase());
                }
            }
        }

        List<OwnerTenantDTO> existingTenants = domainDao.findAllTenantsByOwner(ownerId);
        if (existingTenants != null && !existingTenants.isEmpty()) {
            for (OwnerTenantDTO dto : existingTenants) {
                Tenant tenant = dto.getTenant();
                if (tenant != null && tenant.getId() != null && tenant.getCode() != null) {
                    String dbCode = tenant.getCode().trim().toUpperCase();
                    if (!incomingCodes.contains(dbCode)) {
                        tenant.setOwnerId(ownerId);
                        this.deleteTenantDetails(tenant);
                    }
                }
            }
        }

        Tenant c168Tenant = domainDao.findTenantByCodeAndOwnerId("C168", 1);
        Integer c168TenantId = (c168Tenant != null) ? c168Tenant.getId() : 2;

        // 1. 批量查出 C168 下所有已拥有的账号名（Code），避免在循环中重复查询数据库
        Set<String> existingAccountCodes = new HashSet<>();
        List<UserListDTO> c168Users = userDao.findUserByTenantId(c168TenantId);
        if (c168Users != null) {
            for (UserListDTO u : c168Users) {
                if (u.getAccountId() != null) {
                    existingAccountCodes.add(u.getAccountId().trim().toUpperCase());
                }
            }
        }

        Map<String, Integer> groupCodeToIdMap = new HashMap<>();
        if (domainDTO.getGroups() != null && !domainDTO.getGroups().isEmpty()) {
            for (Tenant group : domainDTO.getGroups()) {
                group.setOwnerId(ownerId);
                group.setTenantType(Tenant.TenantType.GROUP);

                String groupCode = group.getCode() != null ? group.getCode().trim().toUpperCase() : null;

                Tenant existing = groupCode != null
                        ? domainDao.findTenantByCodeAndOwnerId(groupCode, ownerId)
                        : null;

                if (existing != null) {
                    group.setId(existing.getId());
                    group.setName(group.getCode() != null ? group.getCode() : existing.getName());
                    group.setStatus(existing.getStatus());
                    group.setExpirationDate(existing.getExpirationDate());
                    this.updateTenantDetails(group);
                    groupCodeToIdMap.put(groupCode, existing.getId());
                } else {
                    group.setName(group.getCode());
                    this.insertTenantDetails(group);
                    if (group.getId() != null) {
                        groupCodeToIdMap.put(groupCode, group.getId());
                    }
                }

                if (groupCode != null && !existingAccountCodes.contains(groupCode)) {
                    this.createAccountTenantInC168(group, c168TenantId);
                    existingAccountCodes.add(groupCode);
                }
            }
        }

        if (domainDTO.getCompanies() != null && !domainDTO.getCompanies().isEmpty()) {
            for (Tenant company : domainDTO.getCompanies()) {
                company.setOwnerId(ownerId);
                company.setTenantType(Tenant.TenantType.COMPANY);

                String parentGroupCode = company.getParentGroupCode();
                if (parentGroupCode != null && !parentGroupCode.isBlank()) {
                    Integer parentId = groupCodeToIdMap.get(parentGroupCode.trim().toUpperCase());
                    if (parentId != null) {
                        company.setParentId(parentId);
                    }
                }

                String companyCode = company.getCode() != null ? company.getCode().trim().toUpperCase() : null;

                Tenant existing = companyCode != null
                        ? domainDao.findTenantByCodeAndOwnerId(companyCode, ownerId)
                        : null;

                if (existing != null) {
                    company.setId(existing.getId());
                    company.setName(company.getCode() != null ? company.getCode() : existing.getName());
                    company.setStatus(existing.getStatus());
                    company.setExpirationDate(existing.getExpirationDate());
                    this.updateTenantDetails(company);
                } else {
                    company.setName(company.getCode());
                    this.insertTenantDetails(company);
                }

                if (companyCode != null && !existingAccountCodes.contains(companyCode)) {
                    this.createAccountTenantInC168(company, c168TenantId);
                    existingAccountCodes.add(companyCode);
                }
            }
        }

        return domainDTO;
    }

    @Override
    public List<DomainListFeePrice> findAllDomainListFeePrices() {
        List<DomainListFeePrice> rows = domainListFeePriceDao.findAll();
        if (rows == null || rows.isEmpty()) {
            domainListFeePriceDao.insertDefaults();
            rows = domainListFeePriceDao.findAll();
        }
        return rows != null ? rows : List.of();
    }

    @Override
    public List<RenewalPeriod> findAllRenewalPeriods() {
        List<RenewalPeriod> periods = domainListFeePriceDao.findAllRenewalPeriodsOrdered();
        return periods != null ? periods : List.of();
    }

    @Override
    public DomainFeeSettingsDTO findDomainFeeSettings() {
        return DomainFeeSettingsMapper.toDto(findAllDomainListFeePrices());
    }

    @Override
    @Transactional
    public DomainFeeSettingsDTO updateDomainFeeSettings(DomainFeeSettingsDTO settings) {
        if (settings == null) {
            throw new BusinessException("Invalid Domain Fee");
        }

        if (settings.getCompanyPeriodPrices() == null) {
            settings.setCompanyPeriodPrices(new DomainFeeSettingsDTO.PeriodPrices());
        }
        if (settings.getGroupPeriodPrices() == null) {
            settings.setGroupPeriodPrices(new DomainFeeSettingsDTO.PeriodPrices());
        }

        List<DomainListFeePrice> rows = DomainFeeSettingsMapper.toRows(settings);
        if (!rows.isEmpty()) {
            domainListFeePriceDao.batchUpsert(rows);
        }
        return findDomainFeeSettings();
    }

    private void loadTenantAssociations(Tenant tenant) {
        if (tenant == null || tenant.getId() == null) {
            return;
        }

        List<TenantFeeShareAllocate> feeShares = feeShareAllocateDao.findFeeShareByTenantId(tenant.getId());
        tenant.setFeeShareAllocations(feeShares != null ? feeShares : List.of());

        List<FeatureModule> modules = domainDao.findFeatureModulesByTenantId(tenant.getId());
        tenant.setFeatureModules(modules != null ? modules : List.of());
    }

    private void replaceFeeShareAllocations(Integer tenantId, List<TenantFeeShareAllocate> allocations) {
        feeShareAllocateDao.deleteByTenantId(tenantId);
        if (allocations == null || allocations.isEmpty()) {
            return;
        }

        for (TenantFeeShareAllocate row : allocations) {
            row.setTenantId(tenantId);
        }
        validateAndPrepareFeeShareRows(allocations);
        feeShareAllocateDao.batchInsert(allocations);
    }

    private void replaceFeatureModules(Tenant tenant) {
        if (tenant == null || tenant.getId() == null) {
            return;
        }

        domainDao.deleteFeatureModulesByTenantId(tenant.getId());

        List<TenantFeatureModule> rows = toFeatureModuleRows(tenant.getId(), tenant.getFeatureModules());
        if (rows.isEmpty()) {
            return;
        }

        validateAndPrepareFeatureModuleRows(rows);
        domainDao.batchInsertFeatureModules(rows);
    }

    private void ensureDefaultGroupFeatureModule(Integer tenantId) {
        List<FeatureModule> existing = domainDao.findFeatureModulesByTenantId(tenantId);
        if (existing != null && !existing.isEmpty()) {
            return;
        }

        List<TenantFeatureModule> rows = List.of(
                new TenantFeatureModule(null, tenantId, DEFAULT_GROUP_MODULE_ID, null));
        validateAndPrepareFeatureModuleRows(rows);
        domainDao.batchInsertFeatureModules(rows);
    }

    private List<TenantFeatureModule> toFeatureModuleRows(Integer tenantId, List<FeatureModule> modules) {
        if (tenantId == null || tenantId <= 0 || modules == null || modules.isEmpty()) {
            return List.of();
        }

        return modules.stream()
                .filter(module -> module != null && module.getId() != null && module.getId() > 0)
                .map(module -> new TenantFeatureModule(null, tenantId, module.getId(), null))
                .collect(Collectors.toList());
    }

    private void createAccountTenantInC168(Tenant tenant, Integer c168TenantId) {
        if (tenant == null || tenant.getId() == null || tenant.getCode() == null) {
            return;
        }

        String code = tenant.getCode().trim().toUpperCase();

        try {
            User user = new User();
            user.setAccountId(code);
            user.setName(code);
            user.setRole("MEMBER");
            user.setPassword(passwordEncoder.encode("111"));
            user.setStatus(User.AccountStatus.ACTIVE);
            user.setPaymentAlert(0);
            userDao.addUserDetails(user);

            if (user.getId() != null) {
                UserTenantAccess userTenantAccess = new UserTenantAccess();
                userTenantAccess.setAccountId(user.getId());
                userTenantAccess.setTenantId(c168TenantId);
                userDao.insertAccountTenantAccess(userTenantAccess);

                Integer myrCurrencyId = null;
                List<Currency> currencyList = currencyDao.findCurrencyByTenantId(c168TenantId);
                if (currencyList != null) {
                    for (Currency curr : currencyList) {
                        if ("MYR".equalsIgnoreCase(curr.getCode())) {
                            myrCurrencyId = curr.getId();
                            break;
                        }
                    }
                }
                if (myrCurrencyId == null) {
                    myrCurrencyId = 6;
                }

                UserCurrency userCurrency = new UserCurrency();
                userCurrency.setAccountId(user.getId());
                userCurrency.setTenantId(c168TenantId);
                userCurrency.setCurrencyId(myrCurrencyId);
                userCurrency.setSortOrder(0);
                currencyDao.insertAccountCurrency(userCurrency);
            }
        } catch (Exception e) {
            System.err.println("Auto Create Account Failed for code: " + code + ", error: " + e.getMessage());
        }
    }
}
