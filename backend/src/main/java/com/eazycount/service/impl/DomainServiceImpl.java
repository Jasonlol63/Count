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
        try {
            if (owner.getOwnerCode() == null || owner.getOwnerCode().isBlank()) {
                throw new BusinessException("Owner Code is required");
            }
            Owner existing = domainDao.findOwnerByCode(owner.getOwnerCode().trim().toUpperCase());
            if (existing != null) {
                throw new BusinessException("Owner Code already exists!");
            }

            if (session.login_id != null) {
                owner.setCreatedBy(session.login_id);
            }
            owner.setOwnerCode(owner.getOwnerCode().trim().toUpperCase());
            owner.setName(owner.getName());
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

        if (tenant.getStatus() == null) {
            tenant.setStatus(Tenant.TenantStatus.ACTIVE);
        } else {
            throw new BusinessException("Invalid Set Tenant Status");
        }

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

    @Transactional
    @Override
    public DomainDTO createDomain(DomainDTO domainDTO) {

        Owner owner = new Owner();
        BeanUtils.copyProperties(domainDTO, owner);
        this.insertOwnerDetails(owner);
        Integer ownerId = owner.getId();
        domainDTO.setId(ownerId); // 回填 ID 给 DTO

        Map<String, Integer> groupCodeToIdMap = new HashMap<>();
        if (domainDTO.getGroups() != null && !domainDTO.getGroups().isEmpty()) {
            for (Tenant group : domainDTO.getGroups()) {
                group.setOwnerId(ownerId);
                group.setTenantType(Tenant.TenantType.GROUP);
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

                this.insertTenantDetails(company);
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
