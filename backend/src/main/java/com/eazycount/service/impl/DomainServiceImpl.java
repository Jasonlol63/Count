package com.eazycount.service.impl;

import com.eazycount.common.BusinessException;
import com.eazycount.dao.AuthDao;
import com.eazycount.dao.DomainDao;
import com.eazycount.dto.*;
import com.eazycount.entity.DomainFee;
import com.eazycount.entity.Owner;
import com.eazycount.entity.Tenant;
import com.eazycount.security.SecurityUtils;
import com.eazycount.security.SessionUser;
import com.eazycount.service.DomainService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

@Service
public class DomainServiceImpl implements DomainService {

    private static final Pattern SECONDARY_PASSWORD_PATTERN = Pattern.compile("^\\d{6}$");

    @Autowired
    private DomainDao domainDao;

    @Autowired
    private AuthDao authDao;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Override
    public List<DomainDTO> findAllDomainList() {
        try {
            List<DomainDTO> domainDTOList = domainDao.findAllDomainList();
            return domainDTOList != null ? domainDTOList : List.of();
        } catch (Exception e) {
            throw new BusinessException("Failed to load Domain List. Please try again.");
        }
    }

    @Override
    public List<DomainListItemDto> listDomainsForApi() {
        final List<DomainDTO> rows = findAllDomainList();
        final Map<Integer, OwnerAccumulator> byOwnerId = new LinkedHashMap<>();

        for (DomainDTO row : rows) {
            final Owner owner = row.getOwner();
            if (owner == null || owner.getId() == null) {
                continue;
            }

            final OwnerAccumulator acc = byOwnerId.computeIfAbsent(owner.getId(), id -> new OwnerAccumulator());
            acc.owner = owner;

            final Tenant tenant = row.getTenant();
            if (tenant == null || tenant.getTenantType() == null) {
                continue;
            }

            final String code = normalizeCode(tenant.getCode());
            if (code == null) {
                continue;
            }

            if (tenant.getTenantType() == Tenant.TenantType.GROUP) {
                acc.addGroup(code, tenant.getExpirationDate());
            } else if (tenant.getTenantType() == Tenant.TenantType.COMPANY) {
                acc.addCompany(code, tenant.getExpirationDate(), normalizeCode(row.getParentGroupCode()));
            }
        }

        final List<DomainListItemDto> items = new ArrayList<>(byOwnerId.size());
        for (OwnerAccumulator acc : byOwnerId.values()) {
            items.add(acc.toListItem());
        }
        return items;
    }

    @Override
    @Transactional
    public DomainListItemDto createDomain(CreateDomainRequest request) {
        if (request == null) {
            throw new BusinessException("Request body is required.");
        }

        final Owner owner = new Owner();
        owner.setOwnerCode(request.getOwnerCode());
        owner.setName(request.getName());
        owner.setEmail(request.getEmail());
        owner.setPassword(request.getPassword());
        owner.setSecondaryPassword(request.getSecondaryPassword());

        final List<TenantSaveDto> groupRows =
                request.getGroups() != null ? request.getGroups() : Collections.emptyList();
        final List<TenantSaveDto> companyRows =
                request.getCompanies() != null ? request.getCompanies() : Collections.emptyList();

        final List<Tenant> groups = new ArrayList<>();
        for (TenantSaveDto groupRow : groupRows) {
            final Tenant group = new Tenant();
            group.setCode(groupRow.getCode());
            group.setExpirationDate(groupRow.getExpirationDate());
            groups.add(group);
        }

        final List<Tenant> companies = new ArrayList<>();
        for (TenantSaveDto companyRow : companyRows) {
            final Tenant company = new Tenant();
            company.setCode(companyRow.getCode());
            company.setParentGroupCode(companyRow.getParentCode());
            company.setExpirationDate(companyRow.getExpirationDate());
            companies.add(company);
        }

        return insertNewDomain(owner, groups, companies);
    }

    @Override
    @Transactional
    public DomainListItemDto updateDomain(UpdateDomainRequest request) {
        if (request == null || request.getId() == null || request.getId() <= 0) {
            throw new BusinessException("Owner id is required.");
        }

        Owner existing = authDao.findOwnerById(request.getId());
        if (existing == null && !isBlank(request.getOwnerCode())) {
            existing = authDao.findOwnerByCode(normalizeCode(request.getOwnerCode()));
            if (existing != null && existing.getId() != null) {
                request.setId(existing.getId());
            }
        }
        if (existing == null) {
            throw new BusinessException("Owner ID not found!");
        }

        final Owner owner = new Owner();
        owner.setId(request.getId());
        owner.setOwnerCode(request.getOwnerCode());
        owner.setName(request.getName());
        owner.setEmail(request.getEmail());
        owner.setPassword(request.getPassword());
        owner.setSecondaryPassword(request.getSecondaryPassword());
        prepareOwnerForUpdate(owner, existing);
        domainDao.updateOwnerDetail(owner);

        final Integer ownerId = owner.getId();
        final String createdBy = existing.getCreatedBy() != null ? existing.getCreatedBy() : resolveCreatedBy();

        final List<TenantSaveDto> groupRows =
                request.getGroups() != null ? request.getGroups() : Collections.emptyList();
        final List<TenantSaveDto> companyRows =
                request.getCompanies() != null ? request.getCompanies() : Collections.emptyList();

        final List<Tenant> existingTenants = domainDao.findTenantsByOwnerId(ownerId);
        final Map<String, Tenant> existingGroupsByCode = new LinkedHashMap<>();
        final Map<String, Tenant> existingCompaniesByCode = new LinkedHashMap<>();
        for (Tenant tenant : existingTenants) {
            final String code = normalizeCode(tenant.getCode());
            if (code == null) continue;
            if (tenant.getTenantType() == Tenant.TenantType.GROUP) {
                existingGroupsByCode.put(code, tenant);
            } else if (tenant.getTenantType() == Tenant.TenantType.COMPANY) {
                existingCompaniesByCode.put(code, tenant);
            }
        }

        final List<Tenant> savedGroups = new ArrayList<>();
        final Map<String, Integer> groupIdByCode = new LinkedHashMap<>();

        for (TenantSaveDto groupRow : groupRows) {
            final String code = normalizeCode(groupRow.getCode());
            if (code == null) continue;

            Tenant group = existingGroupsByCode.get(code);
            if (group == null) {
                validateDomainCode(code, ownerId);
                group = new Tenant();
                group.setCode(code);
                group.setExpirationDate(groupRow.getExpirationDate());
                prepareGroupForInsert(group, ownerId, createdBy);
                domainDao.addTenantDetail(group);
            } else {
                group.setExpirationDate(groupRow.getExpirationDate());
                ensureTenantFieldsForUpdate(group);
                domainDao.updateTenantDetail(group);
            }
            savedGroups.add(group);
            if (group.getId() != null) {
                groupIdByCode.put(code, group.getId());
            }
        }

        final Set<String> requestedGroupCodes = new LinkedHashSet<>();
        for (TenantSaveDto groupRow : groupRows) {
            final String code = normalizeCode(groupRow.getCode());
            if (code != null) requestedGroupCodes.add(code);
        }
        for (Map.Entry<String, Tenant> entry : existingGroupsByCode.entrySet()) {
            if (!requestedGroupCodes.contains(entry.getKey())) {
                deactivateTenant(entry.getValue());
            }
        }

        final List<Tenant> savedCompanies = new ArrayList<>();
        for (TenantSaveDto companyRow : companyRows) {
            final String code = normalizeCode(companyRow.getCode());
            if (code == null) continue;

            Tenant company = existingCompaniesByCode.get(code);
            if (company == null) {
                validateDomainCode(code, ownerId);
                company = new Tenant();
                company.setCode(code);
                company.setParentGroupCode(companyRow.getParentCode());
                company.setExpirationDate(companyRow.getExpirationDate());
                prepareCompanyForInsert(company, ownerId, createdBy, groupIdByCode);
                domainDao.addTenantDetail(company);
            } else {
                company.setExpirationDate(companyRow.getExpirationDate());
                company.setParentGroupCode(companyRow.getParentCode());
                final String parentGroupCode = normalizeCode(company.getParentGroupCode());
                if (parentGroupCode != null) {
                    final Integer parentId = groupIdByCode.get(parentGroupCode);
                    if (parentId == null) {
                        throw new BusinessException("Parent group not found for company: " + code);
                    }
                    company.setParentId(parentId);
                } else {
                    company.setParentId(null);
                }
                ensureTenantFieldsForUpdate(company);
                domainDao.updateTenantDetail(company);
            }
            savedCompanies.add(company);
        }

        final Set<String> requestedCompanyCodes = new LinkedHashSet<>();
        for (TenantSaveDto companyRow : companyRows) {
            final String code = normalizeCode(companyRow.getCode());
            if (code != null) requestedCompanyCodes.add(code);
        }
        for (Map.Entry<String, Tenant> entry : existingCompaniesByCode.entrySet()) {
            if (!requestedCompanyCodes.contains(entry.getKey())) {
                deactivateTenant(entry.getValue());
            }
        }

        owner.setOwnerCode(
                !isBlank(request.getOwnerCode())
                        ? request.getOwnerCode().trim().toUpperCase()
                        : existing.getOwnerCode()
        );
        owner.setCreatedBy(existing.getCreatedBy());
        owner.setCreatedAt(existing.getCreatedAt());
        return buildListItemFromDatabase(ownerId);
    }

    @Override
    public void deleteDomain(Integer domainId) {
        if(domainId == null){
            throw new BusinessException("Domain id is null");
        }

        Owner existing = authDao.findOwnerById(domainId);
        if(existing == null){
            throw new BusinessException("Owner not found");
        }

        List<Tenant> tenants = domainDao.findTenantsByOwnerId(domainId);
        if (tenants == null) {
            tenants = List.of();
        }

        for (Tenant tenant : tenants) {
            if ("C168".equals(normalizeCode(tenant.getCode()))) {
                throw new BusinessException("Cannot delete owners containing C168.");
            }
        }

        final List<Tenant> companies = new ArrayList<>();
        final List<Tenant> groups = new ArrayList<>();
        for (Tenant tenant : tenants) {
            if (tenant.getTenantType() == Tenant.TenantType.COMPANY) {
                companies.add(tenant);
            } else if (tenant.getTenantType() == Tenant.TenantType.GROUP) {
                groups.add(tenant);
            }
        }

        try{
            for (Tenant tenant : companies) {
                purgeTenantDependencies(tenant.getId());
                domainDao.deleteTenant(tenant.getId());
            }
            for (Tenant tenant : groups) {
                purgeTenantDependencies(tenant.getId());
                domainDao.deleteTenant(tenant.getId());
            }

            domainDao.deletePasswordResetTacOwnerByOwnerId(domainId);
            domainDao.deleteOwner(domainId);
        } catch (Exception e) {
            throw new BusinessException("Failed to delete domain. Please try again.");
        }

    }

    private void purgeTenantDependencies(Integer tenantId) {
        if (tenantId == null) {
            return;
        }
        domainDao.deleteTenantFeatureModulesByTenantId(tenantId);
        domainDao.deleteUserTenantAccessByTenantId(tenantId);
        domainDao.deleteAccountTenantAccessByTenantId(tenantId);
        domainDao.deleteTenantLinksByTenantId(tenantId);
        domainDao.deletePasswordResetTacByTenantId(tenantId);
    }


    private DomainListItemDto buildListItemFromDatabase(Integer ownerId) {
        if (ownerId == null || ownerId <= 0) {
            throw new BusinessException("Owner id is required.");
        }

        final List<DomainDTO> rows = findAllDomainList();
        final OwnerAccumulator acc = new OwnerAccumulator();

        for (DomainDTO row : rows) {
            final Owner rowOwner = row.getOwner();
            if (rowOwner == null || !ownerId.equals(rowOwner.getId())) {
                continue;
            }
            acc.owner = rowOwner;

            final Tenant tenant = row.getTenant();
            if (tenant == null || tenant.getTenantType() == null) {
                continue;
            }

            final String code = normalizeCode(tenant.getCode());
            if (code == null) {
                continue;
            }

            if (tenant.getTenantType() == Tenant.TenantType.GROUP) {
                acc.addGroup(code, tenant.getExpirationDate());
            } else if (tenant.getTenantType() == Tenant.TenantType.COMPANY) {
                acc.addCompany(code, tenant.getExpirationDate(), normalizeCode(row.getParentGroupCode()));
            }
        }

        if (acc.owner == null) {
            throw new BusinessException("Owner ID not found!");
        }
        return acc.toListItem();
    }

    private void deactivateTenant(Tenant tenant) {
        if (tenant == null || tenant.getId() == null) {
            return;
        }
        ensureTenantFieldsForUpdate(tenant);
        tenant.setStatus(Tenant.TenantStatus.INACTIVE);
        domainDao.updateTenantDetail(tenant);
    }

    private void ensureTenantFieldsForUpdate(Tenant tenant) {
        if (tenant == null || tenant.getId() == null) {
            return;
        }
        if (tenant.getTenantType() != null && tenant.getName() != null
                && tenant.getOwnerId() != null && tenant.getStatus() != null) {
            return;
        }

        final Tenant loaded = authDao.findTenantById(tenant.getId());
        if (loaded == null) {
            return;
        }
        if (tenant.getTenantType() == null) {
            tenant.setTenantType(loaded.getTenantType());
        }
        if (tenant.getName() == null) {
            tenant.setName(loaded.getName());
        }
        if (tenant.getOwnerId() == null) {
            tenant.setOwnerId(loaded.getOwnerId());
        }
        if (tenant.getStatus() == null) {
            tenant.setStatus(loaded.getStatus());
        }
        if (tenant.getCreatedBy() == null) {
            tenant.setCreatedBy(loaded.getCreatedBy());
        }
    }

    private void prepareOwnerForUpdate(Owner owner, Owner existing) {
        if (isBlank(owner.getName()) || isBlank(owner.getEmail())) {
            throw new BusinessException("Name and email are required.");
        }
        if (!isBlank(owner.getOwnerCode())) {
            owner.setOwnerCode(owner.getOwnerCode().trim().toUpperCase());
        } else {
            owner.setOwnerCode(existing.getOwnerCode());
        }
        owner.setName(owner.getName().trim().toUpperCase());
        owner.setEmail(owner.getEmail().trim().toLowerCase());

        if (isBlank(owner.getPassword())) {
            owner.setPassword(existing.getPassword());
        } else {
            owner.setPassword(passwordEncoder.encode(owner.getPassword()));
        }

        if (isBlank(owner.getSecondaryPassword())) {
            owner.setSecondaryPassword(existing.getSecondaryPassword());
        } else {
            if (!SECONDARY_PASSWORD_PATTERN.matcher(owner.getSecondaryPassword()).matches()) {
                throw new BusinessException("Secondary password must be exactly 6 digits.");
            }
            owner.setSecondaryPassword(passwordEncoder.encode(owner.getSecondaryPassword()));
        }
    }

    private DomainListItemDto insertNewDomain(Owner owner, List<Tenant> groups, List<Tenant> companies) {
        if (owner == null) {
            throw new BusinessException("Owner information is required.");
        }
        if (owner.getId() != null) {
            throw new BusinessException("Use update API for existing owners.");
        }

        prepareOwnerForInsert(owner);
        domainDao.addOwnerDetail(owner);

        final Integer ownerId = owner.getId();
        if (ownerId == null) {
            throw new BusinessException("Failed to create owner.");
        }

        final String createdBy = owner.getCreatedBy();
        final Map<String, Integer> groupIdByCode = new LinkedHashMap<>();

        for (Tenant group : groups) {
            prepareGroupForInsert(group, ownerId, createdBy);
            domainDao.addTenantDetail(group);
            final String groupCode = normalizeCode(group.getCode());
            if (groupCode != null && group.getId() != null) {
                groupIdByCode.put(groupCode, group.getId());
            }
        }

        for (Tenant company : companies) {
            prepareCompanyForInsert(company, ownerId, createdBy, groupIdByCode);
            domainDao.addTenantDetail(company);
        }

        return buildListItemFromSaved(owner, groups, companies);
    }


    @Override
    public void validateDomainCode(String code, Integer excludeOwnerId) {
        final String normalized = normalizeCode(code);
        if (normalized == null) {
            throw new BusinessException("Code is required.");
        }

        final Integer excludeId = excludeOwnerId != null && excludeOwnerId > 0 ? excludeOwnerId : null;
        if (domainDao.countTenantCodeConflict(normalized, excludeId) > 0
                || domainDao.countOwnerCodeConflict(normalized, excludeId) > 0) {
            throw new BusinessException(
                    "This ID \"" + normalized + "\" is already in use by another domain (not allowed). "
                            + "Choose a different Company ID or Group ID."
            );
        }
    }

    @Override
    public void addOwnerDetail(Owner owner) {
        try{
            owner.setId(owner.getId());
            domainDao.addOwnerDetail(owner);
        } catch (Exception e) {
            throw new BusinessException("Failed to insert Owner. Please try again.");
        }
    }

    @Override
    public void updateOwnerDetail(Owner owner) {
        if (owner == null || owner.getId() == null) {
            throw new BusinessException("Owner ID is required!");
        }
        final Owner existing = authDao.findOwnerById(owner.getId());
        if (existing == null) {
            throw new BusinessException("Owner ID not found!");
        }
        if (isBlank(owner.getPassword())) {
            owner.setPassword(existing.getPassword());
        }
        if (isBlank(owner.getSecondaryPassword())) {
            owner.setSecondaryPassword(existing.getSecondaryPassword());
        }
        try {
            domainDao.updateOwnerDetail(owner);
        } catch (Exception e) {
            throw new BusinessException("Failed to update Owner. Please try again.");
        }
    }

    @Override
    public void updateTenantDetail(Tenant tenant) {
        if (tenant == null || tenant.getId() == null) {
            throw new BusinessException("Tenant ID is required!");
        }
        if (authDao.findTenantById(tenant.getId()) == null) {
            throw new BusinessException("Tenant ID not found!");
        }
        try {
            domainDao.updateTenantDetail(tenant);
        } catch (Exception e) {
            throw new BusinessException("Failed to update Tenant. Please try again.");
        }
    }
    @Override
    public void addTenantDetail(Tenant tenant) {
        try{
            tenant.setId(tenant.getId());
            domainDao.addTenantDetail(tenant);
        }catch (Exception e) {
            throw new BusinessException("Failed to insert Tenant. Please try again.");
        }
    }

    @Override
    public void deleteOwner(Integer ownerId) {
        if (ownerId == null) {
            throw new BusinessException("Owner ID is required!");
        }

        try{
            domainDao.deleteOwner(ownerId);
        }catch (Exception e) {
            throw new BusinessException("Failed to delete Owner. Please try again.");
        }
    }

    @Override
    public void deleteTenant(Integer tenantId) {
        if(tenantId == null){
            throw new BusinessException("Tenant ID is required!");
        }
        try{
            domainDao.deleteTenant(tenantId);
        }catch (Exception e) {
            throw new BusinessException("Failed to delete Tenant. Please try again.");
        }
    }

    @Override
    public DomainFee findFeeSetting() {
        try {
            return domainDao.findFeeSetting();
        } catch (Exception e) {
            throw new BusinessException("Failed to load domain fee settings. Please try again!");
        }
    }

    @Override
    public void updateFee(DomainFee domainFee) {
        try {
            domainFee.setId(1);
            domainFee.setUpdatedTime(LocalDateTime.now());
            domainDao.updateFee(domainFee);
        } catch (Exception e) {
            throw new BusinessException("Failed to save domain fee settings. Please try again!");
        }
    }

    private static String normalizeCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return code.trim().toUpperCase();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private void prepareOwnerForInsert(Owner owner) {
        if (isBlank(owner.getOwnerCode()) || isBlank(owner.getName()) || isBlank(owner.getEmail())) {
            throw new BusinessException("Owner code, name and email are required.");
        }
        if (isBlank(owner.getPassword()) || isBlank(owner.getSecondaryPassword())) {
            throw new BusinessException("Password and secondary password are required.");
        }
        if (!SECONDARY_PASSWORD_PATTERN.matcher(owner.getSecondaryPassword()).matches()) {
            throw new BusinessException("Secondary password must be exactly 6 digits.");
        }

        owner.setOwnerCode(owner.getOwnerCode().trim().toUpperCase());
        owner.setName(owner.getName().trim().toUpperCase());
        owner.setEmail(owner.getEmail().trim().toLowerCase());
        owner.setPassword(passwordEncoder.encode(owner.getPassword()));
        owner.setSecondaryPassword(passwordEncoder.encode(owner.getSecondaryPassword()));
        if (owner.getStatus() == null) {
            owner.setStatus(Owner.OwnerStatus.ACTIVE);
        }
        if (isBlank(owner.getCreatedBy())) {
            owner.setCreatedBy(resolveCreatedBy());
        }
    }

    private void prepareGroupForInsert(Tenant group, Integer ownerId, String createdBy) {
        final String code = normalizeCode(group.getCode());
        if (code == null) {
            throw new BusinessException("Group code is required.");
        }

        group.setCode(code);
        group.setTenantType(Tenant.TenantType.GROUP);
        group.setOwnerId(ownerId);
        group.setParentId(null);
        if (group.getStatus() == null) {
            group.setStatus(Tenant.TenantStatus.ACTIVE);
        }
        if (isBlank(group.getCreatedBy())) {
            group.setCreatedBy(createdBy);
        }
        if (isBlank(group.getName())) {
            group.setName(code);
        }
    }

    private void prepareCompanyForInsert(
            Tenant company,
            Integer ownerId,
            String createdBy,
            Map<String, Integer> groupIdByCode
    ) {
        final String code = normalizeCode(company.getCode());
        if (code == null) {
            throw new BusinessException("Company code is required.");
        }

        company.setCode(code);
        company.setTenantType(Tenant.TenantType.COMPANY);
        company.setOwnerId(ownerId);
        if (company.getParentId() == null) {
            final String parentGroupCode = normalizeCode(company.getParentGroupCode());
            if (parentGroupCode != null) {
                final Integer parentId = groupIdByCode.get(parentGroupCode);
                if (parentId == null) {
                    throw new BusinessException("Parent group not found for company: " + code);
                }
                company.setParentId(parentId);
            }
        }
        if (company.getStatus() == null) {
            company.setStatus(Tenant.TenantStatus.ACTIVE);
        }
        if (isBlank(company.getCreatedBy())) {
            company.setCreatedBy(createdBy);
        }
        if (isBlank(company.getName())) {
            company.setName(code);
        }
    }

    private static String resolveCreatedBy() {
        final SessionUser user = SecurityUtils.currentUser();
        if (user != null && !isBlank(user.login_id)) {
            return user.login_id;
        }
        return "system";
    }

    private DomainListItemDto buildListItemFromSaved(
            Owner owner,
            List<Tenant> groups,
            List<Tenant> companies
    ) {
        final DomainListItemDto item = new DomainListItemDto();
        item.setId(owner.getId());
        item.setOwnerCode(owner.getOwnerCode());
        item.setName(owner.getName());
        item.setEmail(owner.getEmail());
        item.setCreatedBy(owner.getCreatedBy());
        item.setCreatedAt(owner.getCreatedAt());

        final List<TenantBriefDto> groupItems = new ArrayList<>();
        for (Tenant group : groups) {
            final String code = normalizeCode(group.getCode());
            if (code == null) {
                continue;
            }
            final TenantBriefDto brief = new TenantBriefDto();
            brief.setCode(code);
            brief.setTenantType(Tenant.TenantType.GROUP.name());
            brief.setExpirationDate(group.getExpirationDate());
            groupItems.add(brief);
        }
        groupItems.sort((a, b) -> a.getCode().compareTo(b.getCode()));
        item.setGroups(groupItems);

        final List<TenantBriefDto> companyItems = new ArrayList<>();
        for (Tenant company : companies) {
            final String code = normalizeCode(company.getCode());
            if (code == null) {
                continue;
            }
            final TenantBriefDto brief = new TenantBriefDto();
            brief.setCode(code);
            brief.setTenantType(Tenant.TenantType.COMPANY.name());
            brief.setExpirationDate(company.getExpirationDate());
            brief.setParentCode(normalizeCode(company.getParentGroupCode()));
            companyItems.add(brief);
        }
        companyItems.sort((a, b) -> a.getCode().compareTo(b.getCode()));
        item.setCompanies(companyItems);
        return item;
    }

    private static final class OwnerAccumulator {
        private Owner owner;
        private final Map<String, TenantBriefDto> groupsByCode = new TreeMap<>();
        private final Map<String, TenantBriefDto> companiesByCode = new TreeMap<>();

        private void addGroup(String code, LocalDate expirationDate) {
            groupsByCode.putIfAbsent(code, toBrief(code, Tenant.TenantType.GROUP.name(), expirationDate, null));
        }

        private void addCompany(String code, LocalDate expirationDate, String parentCode) {
            companiesByCode.putIfAbsent(
                    code,
                    toBrief(code, Tenant.TenantType.COMPANY.name(), expirationDate, parentCode)
            );
        }

        private static TenantBriefDto toBrief(
                String code,
                String tenantType,
                LocalDate expirationDate,
                String parentCode
        ) {
            final TenantBriefDto brief = new TenantBriefDto();
            brief.setCode(code);
            brief.setTenantType(tenantType);
            brief.setExpirationDate(expirationDate);
            brief.setParentCode(parentCode);
            return brief;
        }

        private DomainListItemDto toListItem() {
            final DomainListItemDto item = new DomainListItemDto();
            item.setId(owner.getId());
            item.setOwnerCode(owner.getOwnerCode());
            item.setName(owner.getName());
            item.setEmail(owner.getEmail());
            item.setCreatedBy(owner.getCreatedBy());
            item.setCreatedAt(owner.getCreatedAt());
            item.setGroups(new ArrayList<>(groupsByCode.values()));
            item.setCompanies(new ArrayList<>(companiesByCode.values()));
            return item;
        }
    }

    public static class AnnouncementServiceImpl {
    }
}
