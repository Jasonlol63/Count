package com.eazycount.service.impl;

import com.eazycount.common.BusinessException;
import com.eazycount.dao.DomainDao;
import com.eazycount.dto.DomainDTO;
import com.eazycount.dto.OwnerTenantDTO;
import com.eazycount.entity.DomainFee;
import com.eazycount.entity.Owner;
import com.eazycount.entity.Tenant;
import com.eazycount.security.SecurityUtils;
import com.eazycount.security.SessionUser;
import com.eazycount.service.DomainService;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class DomainServiceImpl implements DomainService {

    @Autowired
    private DomainDao domainDao;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Override
    public List<OwnerTenantDTO> findAllTenantsByOwner(Integer ownerId) {
        SessionUser session = SecurityUtils.currentUser();
        if (session == null) {
            throw new BusinessException("Not logged in");
        }

        return domainDao.findAllTenantsByOwner(ownerId);
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
        tenant.setFeeShareAllocate(tenant.getFeeShareAllocate());
        tenant.setCategoryCode(tenant.getCategoryCode());
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

            if (Tenant.TenantType.GROUP.equals(findTenantOwner.getTenantType())) {
                findTenantOwner.setCategoryCode(java.util.List.of("Games"));
            } else {
                findTenantOwner.setCategoryCode(tenant.getCategoryCode());
            }

            findTenantOwner.setCode(tenant.getCode());
            findTenantOwner.setName(tenant.getCode());
            findTenantOwner.setExpirationDate(tenant.getExpirationDate());
            domainDao.updateTenantDetails(findTenantOwner);
        } catch (Exception e) {
            throw new BusinessException("Update Tenant Failed!");
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

        Map<String, Integer> groupCodeToIdMap = new HashMap<>();
        if (domainDTO.getGroups() != null && !domainDTO.getGroups().isEmpty()) {
            for (Tenant group : domainDTO.getGroups()) {
                group.setOwnerId(ownerId);
                group.setTenantType(Tenant.TenantType.GROUP);
                group.setCategoryCode(java.util.List.of("Games"));
                group.setName(group.getCode());
                this.insertTenantDetails(group);
                groupCodeToIdMap.put(group.getCode(), group.getId());
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
                    group.setName(existing.getName());
                    group.setStatus(existing.getStatus());
                    group.setFeeShareAllocate(existing.getFeeShareAllocate());
                    group.setCategoryCode(java.util.List.of("Games"));
                    this.updateTenantDetails(group);
                    groupCodeToIdMap.put(groupCode, existing.getId());
                } else {
                    group.setCategoryCode(java.util.List.of("Games"));
                    group.setName(group.getCode());
                    this.insertTenantDetails(group);
                    if (group.getId() != null) {
                        groupCodeToIdMap.put(groupCode, group.getId());
                    }
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
                    company.setName(existing.getName());
                    company.setStatus(existing.getStatus());
                    company.setFeeShareAllocate(existing.getFeeShareAllocate());
                    company.setCategoryCode(existing.getCategoryCode());
                    this.updateTenantDetails(company);
                } else {
                    company.setName(company.getCode());
                    this.insertTenantDetails(company);
                }
            }
        }

        return domainDTO;
    }

    @Override
    public List<DomainFee> findAllDomainFee() {
        List<DomainFee> list = domainDao.findAllDomainFee();

        if (list == null || list.isEmpty()) {
            DomainFee defaultFee = new DomainFee();
            defaultFee.setId(1);
            defaultFee.setCompanyPrice(new DomainFee.PriceMap());
            defaultFee.setGroupPrice(new DomainFee.PriceMap());
            domainDao.insertDomainFee(defaultFee);
            return List.of(defaultFee);
        }

        return list;
    }

    @Override
    public DomainFee updateDomainFee(DomainFee domainFee) {
        if (domainFee == null) {
            throw new BusinessException("Invalid Domain Fee");
        }

        domainFee.setId(1);

        if (domainFee.getCompanyPrice() == null) {
            domainFee.setCompanyPrice(new DomainFee.PriceMap());
        }

        if (domainFee.getGroupPrice() == null) {
            domainFee.setGroupPrice(new DomainFee.PriceMap());
        }

        List<DomainFee> existing = domainDao.findAllDomainFee();
        if (existing == null || existing.isEmpty()) {
            domainDao.insertDomainFee(domainFee);
        } else {
            domainDao.updateDomainFee(domainFee);
        }
        return domainFee;
    }
}
