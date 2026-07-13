package com.eazycount.service.impl;

import com.eazycount.common.BusinessException;
import com.eazycount.dao.AdminDao;
import com.eazycount.dao.DomainDao;
import com.eazycount.dao.PermissionDao;
import com.eazycount.dao.TenantDao;
import com.eazycount.dto.AdminDTO;
import com.eazycount.entity.Admin;
import com.eazycount.entity.AdminRole;
import com.eazycount.entity.AdminTenantAccess;
import com.eazycount.entity.AdminTenantAccountAccess;
import com.eazycount.entity.AdminTenantProcessAccess;
import com.eazycount.entity.Owner;
import com.eazycount.entity.Tenant;
import com.eazycount.security.SecurityUtils;
import com.eazycount.security.SessionUser;
import com.eazycount.service.AdminService;
import com.eazycount.service.DomainService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class AdminServiceImpl implements AdminService {

    @Autowired
    private AdminDao adminDao;

    @Autowired
    private PermissionDao permissionDao;

    @Autowired
    private DomainDao domainDao;

    @Autowired
    private TenantDao tenantDao;

    @Autowired
    private DomainService domainService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    public AdminServiceImpl(AdminDao adminDao) {
        this.adminDao = adminDao;
    }

    @Override
    public List<AdminDTO> findAdminsByTenantId(Integer tenantId) {
        if (tenantId == null || tenantId <= 0) {
            throw new BusinessException("Invalid Tenant Id!");
        }
        List<AdminDTO> list = new ArrayList<>(adminDao.findAdminsByTenantId(tenantId));
        prependOwnerShadowRowIfViewerIsOwner(list, tenantId);
        return list;
    }

    private void prependOwnerShadowRowIfViewerIsOwner(List<AdminDTO> list, int tenantId) {
        SessionUser session = SecurityUtils.currentUser();
        if (session == null || session.user_type == null
                || !"owner".equalsIgnoreCase(session.user_type.trim())
                || session.user_id == null) {
            return;
        }

        Tenant tenant = tenantDao.findTenantById(tenantId);
        if (tenant == null || tenant.getOwnerId() == null
                || !tenant.getOwnerId().equals(session.user_id)) {
            return;
        }

        Owner owner = domainDao.findOwnerById(tenant.getOwnerId());
        if (owner == null) {
            return;
        }

        boolean alreadyListed = list.stream().anyMatch(row ->
                row.getAdmin() != null && owner.getId().equals(row.getAdmin().getId()));
        if (alreadyListed) {
            return;
        }

        list.add(0, buildOwnerShadowListRow(owner));
    }

    @Override
    public AdminDTO getAdminDetailByUserId(Integer userId, Integer scopeTenantId) {
        requireLoggedIn();

        if (userId == null || userId <= 0 || scopeTenantId == null || scopeTenantId <= 0) {
            throw new BusinessException("Invalid request");
        }

        Tenant tenant = tenantDao.findTenantById(scopeTenantId);
        if (tenant != null && tenant.getOwnerId() != null && tenant.getOwnerId().equals(userId)) {
            Owner owner = domainDao.findOwnerById(userId);
            if (owner != null) {
                requireOwnerSessionForProfile(userId);
                return buildOwnerShadowDetail(owner, scopeTenantId);
            }
        }

        Admin admin = adminDao.findAdminById(userId);
        if (admin == null) {
            throw new BusinessException("User not found!");
        }

        AdminTenantAccess scopedAccess =
                adminDao.findTenantAccessByUserIdAndTenantId(userId, scopeTenantId);

        boolean ownerShadow = scopedAccess == null
                && admin.getRoleCode() != null
                && "OWNER".equalsIgnoreCase(admin.getRoleCode().trim());

        if (scopedAccess == null && !ownerShadow) {
            throw new BusinessException("User not found!");
        }

        AdminDTO detail = new AdminDTO();
        detail.setId(admin.getId());
        detail.setLoginId(admin.getLoginId());
        detail.setName(admin.getName());
        detail.setEmail(admin.getEmail());
        detail.setRole(admin.getRole());
        detail.setStatus(admin.getStatus() != null
                ? admin.getStatus().name().toLowerCase(Locale.ROOT)
                : "active");
        detail.setReadOnly(admin.getReadOnly() != null ? admin.getReadOnly() : true);
        detail.setScopeTenantId(scopeTenantId);

        if (ownerShadow) {
            detail.setTenantAccessId(null);
            detail.setTenantIds(List.of());
            detail.setAccountPermissions(null);
            detail.setProcessPermissions(null);
            detail.setPermissions(resolveSidebarPermissionCodes(admin.getRoleId()));
            return detail;
        }

        detail.setTenantAccessId(scopedAccess.getId());
        detail.setTenantIds(adminDao.findAdminTenantIdsByUserId(userId));
        detail.setAccountPermissions(resolveAccountPermissions(scopedAccess));
        detail.setProcessPermissions(resolveProcessPermissions(scopedAccess));
        detail.setPermissions(resolveSidebarPermissionCodes(admin.getRoleId()));
        return detail;
    }

    private List<String> resolveSidebarPermissionCodes(Integer roleId) {
        if (roleId == null || roleId <= 0) {
            return List.of();
        }
        return permissionDao.findActivePermissionsByRoleId(roleId).stream()
                .map(p -> p.getCode() == null ? "" : p.getCode().trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private List<AdminDTO.AccountPermissionItem> resolveAccountPermissions(AdminTenantAccess access) {
        AdminTenantAccess.AclMode mode = access.getAccountAclMode();
        if (mode == null || mode == AdminTenantAccess.AclMode.ALL) {
            return null;
        }
        if (mode == AdminTenantAccess.AclMode.NONE) {
            return List.of();
        }
        return adminDao.findAccountPermissionsByUserTenantAccessId(access.getId());
    }

    private List<AdminDTO.ProcessPermissionItem> resolveProcessPermissions(AdminTenantAccess access) {
        AdminTenantAccess.AclMode mode = access.getProcessAclMode();
        if (mode == null || mode == AdminTenantAccess.AclMode.ALL) {
            return null;
        }
        if (mode == AdminTenantAccess.AclMode.NONE) {
            return List.of();
        }
        return adminDao.findProcessPermissionsByUserTenantAccessId(access.getId());
    }

    @Override
    @Transactional
    public AdminDTO updateOwnerProfile(AdminDTO dto) {
        requireLoggedIn();
        if (dto == null || dto.getId() == null || dto.getId() <= 0) {
            throw new BusinessException("Invalid request");
        }

        requireOwnerSessionForProfile(dto.getId());

        Owner existing = domainDao.findOwnerById(dto.getId());
        if (existing == null) {
            throw new BusinessException("Owner not found!");
        }

        Owner patch = new Owner();
        patch.setId(existing.getId());
        patch.setOwnerCode(existing.getOwnerCode());
        patch.setName(dto.getName() != null && !dto.getName().isBlank()
                ? dto.getName().trim()
                : existing.getName());
        patch.setEmail(dto.getEmail() != null && !dto.getEmail().isBlank()
                ? dto.getEmail().trim().toLowerCase(Locale.ROOT)
                : existing.getEmail());
        patch.setPassword(dto.getPassword());
        patch.setSecondaryPassword(dto.getSecondaryPassword());

        domainService.updateOwnerDetails(patch);

        Owner updated = domainDao.findOwnerById(dto.getId());
        if (updated == null) {
            throw new BusinessException("Owner not found!");
        }
        return buildOwnerShadowListRow(updated);
    }

    private AdminDTO buildOwnerShadowListRow(Owner owner) {
        AdminDTO dto = new AdminDTO();
        dto.setIsOwnerShadow(true);
        dto.setAdmin(mapOwnerToAdminShell(owner));
        dto.setAdminTenantAccess(null);
        return dto;
    }

    private AdminDTO buildOwnerShadowDetail(Owner owner, Integer scopeTenantId) {
        AdminDTO detail = new AdminDTO();
        detail.setIsOwnerShadow(true);
        detail.setId(owner.getId());
        detail.setLoginId(owner.getOwnerCode());
        detail.setName(owner.getName());
        detail.setEmail(owner.getEmail());
        detail.setRole("OWNER");
        detail.setStatus(ownerStatusLabel(owner));
        detail.setReadOnly(false);
        detail.setScopeTenantId(scopeTenantId);
        detail.setTenantAccessId(null);
        detail.setTenantIds(List.of());
        detail.setAccountPermissions(null);
        detail.setProcessPermissions(null);
        detail.setPermissions(resolveOwnerSidebarPermissionCodes());
        return detail;
    }

    private Admin mapOwnerToAdminShell(Owner owner) {
        Admin admin = new Admin();
        admin.setId(owner.getId());
        admin.setLoginId(owner.getOwnerCode());
        admin.setName(owner.getName());
        admin.setEmail(owner.getEmail());
        admin.setRoleCode("OWNER");
        admin.setRoleId(resolveOwnerRoleId());
        admin.setStatus(mapOwnerStatus(owner));
        admin.setCreatedBy(owner.getCreatedBy());
        admin.setReadOnly(false);
        return admin;
    }

    private Integer resolveOwnerRoleId() {
        AdminRole ownerRole = permissionDao.findStaffRoleByCode("OWNER");
        return ownerRole != null ? ownerRole.getId() : null;
    }

    private List<String> resolveOwnerSidebarPermissionCodes() {
        return resolveSidebarPermissionCodes(resolveOwnerRoleId());
    }

    private static String ownerStatusLabel(Owner owner) {
        if (owner == null || owner.getStatus() == null) {
            return "active";
        }
        return owner.getStatus().name().toLowerCase(Locale.ROOT);
    }

    private static Admin.UserStatus mapOwnerStatus(Owner owner) {
        if (owner == null || owner.getStatus() == null) {
            return Admin.UserStatus.ACTIVE;
        }
        return owner.getStatus() == Owner.OwnerStatus.INACTIVE
                ? Admin.UserStatus.INACTIVE
                : Admin.UserStatus.ACTIVE;
    }

    private void requireOwnerSessionForProfile(int ownerId) {
        SessionUser session = SecurityUtils.currentUser();
        if (session == null || session.user_type == null
                || !"owner".equalsIgnoreCase(session.user_type.trim())
                || session.user_id == null
                || session.user_id != ownerId) {
            throw new BusinessException("No permission");
        }
    }

    private void assertNotTenantOwner(Integer userId, Integer scopeTenantId) {
        Tenant tenant = tenantDao.findTenantById(scopeTenantId);
        if (tenant != null && tenant.getOwnerId() != null && tenant.getOwnerId().equals(userId)) {
            throw new BusinessException("Owner cannot be deleted or status toggled");
        }
    }

    @Override
    @Transactional
    public AdminDTO createAdmin(AdminDTO dto) {
        requireLoggedIn();
        if (dto == null) {
            throw new BusinessException("Invalid Admin");
        }

        Admin admin = persistUserForCreate(dto);
        AdminTenantAccess primaryAccess = syncTenantGrants(admin, dto, true);
        return buildResult(admin, primaryAccess);
    }

    @Override
    @Transactional
    public AdminDTO updateAdmin(AdminDTO dto) {
        requireLoggedIn();
        if (dto == null) {
            throw new BusinessException("Invalid Admin");
        }
        if (resolveUserId(dto) == null) {
            throw new BusinessException("Invalid Admin");
        }
        if (dto.getScopeTenantId() == null || dto.getScopeTenantId() <= 0) {
            throw new BusinessException("Invalid Tenant Id!");
        }

        Admin existing = loadExistingAdmin(dto);
        Admin admin = persistUserForUpdate(dto, existing);
        AdminTenantAccess primaryAccess = syncTenantGrants(admin, dto, false);
        return buildResult(admin, primaryAccess);
    }

    private Admin persistUserForCreate(AdminDTO dto) {
        Admin admin = mapDtoToAdmin(dto);
        admin.setRoleId(resolveRoleId(dto.getRole()));

        if (dto.getStatus() != null && !dto.getStatus().isBlank()) {
            admin.setStatus(Admin.UserStatus.valueOf(dto.getStatus().trim().toUpperCase(Locale.ROOT)));
        } else {
            admin.setStatus(Admin.UserStatus.ACTIVE);
        }
        if (admin.getReadOnly() == null) {
            admin.setReadOnly(true);
        }

        normalizeAdminFields(admin);
        validateRequiredForCreate(admin);
        assertNoDuplicateLoginId(admin.getLoginId());
        assertNoDuplicateEmail(admin.getEmail(), null);
        encodePasswords(admin);

        SessionUser session = SecurityUtils.currentUser();
        admin.setCreatedBy(session.login_id);

        try {
            adminDao.insertAdmin(admin);
        } catch (Exception e) {
            throw new BusinessException("Insert Admin Failed!");
        }
        return admin;
    }

    private Admin persistUserForUpdate(AdminDTO dto, Admin existing) {
        Admin admin = new Admin();
        admin.setId(existing.getId());
        admin.setLoginId(existing.getLoginId());

        String name = dto.getName() != null && !dto.getName().isBlank()
                ? dto.getName().trim()
                : existing.getName();
        String email = dto.getEmail() != null && !dto.getEmail().isBlank()
                ? dto.getEmail().trim().toLowerCase(Locale.ROOT)
                : existing.getEmail();
        admin.setName(name);
        admin.setEmail(email);

        if (dto.getRole() != null && !dto.getRole().isBlank()) {
            admin.setRoleId(resolveRoleId(dto.getRole()));
            admin.setRoleCode(dto.getRole());
        } else {
            admin.setRoleId(existing.getRoleId());
            admin.setRoleCode(existing.getRoleCode());
        }

        if (dto.getPassword() != null && !dto.getPassword().isBlank()) {
            admin.setPassword(passwordEncoder.encode(dto.getPassword()));
        } else {
            admin.setPassword(existing.getPassword());
        }

        if (dto.getSecondaryPassword() != null && !dto.getSecondaryPassword().isBlank()) {
            admin.setSecondaryPassword(passwordEncoder.encode(dto.getSecondaryPassword()));
        } else {
            admin.setSecondaryPassword(existing.getSecondaryPassword());
        }

        if (dto.getStatus() != null && !dto.getStatus().isBlank()) {
            admin.setStatus(Admin.UserStatus.valueOf(dto.getStatus().trim().toUpperCase(Locale.ROOT)));
        } else {
            admin.setStatus(existing.getStatus());
        }

        if (dto.getReadOnly() != null) {
            admin.setReadOnly(dto.getReadOnly());
        } else {
            admin.setReadOnly(existing.getReadOnly());
        }

        assertNoDuplicateEmail(admin.getEmail(), admin.getId());

        try {
            adminDao.updateAdmin(admin);
        } catch (Exception e) {
            throw new BusinessException("Update Admin Failed!");
        }
        return admin;
    }

    private AdminTenantAccess syncTenantGrants(Admin admin, AdminDTO dto, boolean isCreate) {
        AclModes modes = resolveAclModes(dto);
        Integer scopeTenantId = resolveScopeTenantId(dto);
        List<Integer> tenantIds = dto.getTenantIds();

        if (!isCreate && (tenantIds == null || tenantIds.isEmpty())) {
            if (scopeTenantId == null) {
                throw new BusinessException("Invalid Tenant Id!");
            }
            AdminTenantAccess access = upsertTenantAccess(admin.getId(), scopeTenantId, modes);
            replaceAccountAcl(access.getId(), dto.getAccountPermissions(), modes.account());
            replaceProcessAcl(access.getId(), dto.getProcessPermissions(), modes.process());
            return access;
        }

        if (tenantIds == null || tenantIds.isEmpty()) {
            throw new BusinessException("Invalid Tenant Id!");
        }

        if (!isCreate) {
            adminDao.deleteTenantAccessByUserIdExceptTenants(admin.getId(), tenantIds);
        }

        AdminTenantAccess primaryAccess = null;
        for (Integer tenantId : tenantIds) {
            if (tenantId == null || tenantId <= 0) {
                continue;
            }

            AdminTenantAccess access = upsertTenantAccess(admin.getId(), tenantId, modes);
            replaceAccountAcl(access.getId(), dto.getAccountPermissions(), modes.account());
            replaceProcessAcl(access.getId(), dto.getProcessPermissions(), modes.process());

            if (primaryAccess == null
                    || (scopeTenantId != null && scopeTenantId.equals(tenantId))) {
                primaryAccess = access;
            }
        }

        if (primaryAccess == null) {
            throw new BusinessException("Insert Admin Tenant Access Failed!");
        }
        return primaryAccess;
    }

    private AdminTenantAccess upsertTenantAccess(
            int userId,
            int tenantId,
            AclModes modes
    ) {
        AdminTenantAccess existing = adminDao.findTenantAccessByUserIdAndTenantId(userId, tenantId);
        if (existing != null) {
            existing.setAccountAclMode(modes.account());
            existing.setProcessAclMode(modes.process());
            try {
                adminDao.updateAdminTenantAccess(existing);
            } catch (Exception e) {
                throw new BusinessException("Update Admin Tenant Access Failed!");
            }
            return existing;
        }

        AdminTenantAccess access = new AdminTenantAccess();
        access.setUserId(userId);
        access.setTenantId(tenantId);
        access.setAccountAclMode(modes.account());
        access.setProcessAclMode(modes.process());
        try {
            adminDao.insertAdminTenantAccess(access);
        } catch (Exception e) {
            throw new BusinessException("Insert Admin Tenant Access Failed!");
        }
        return access;
    }

    private void replaceAccountAcl(
            Long userTenantAccessId,
            List<AdminDTO.AccountPermissionItem> items,
            AdminTenantAccess.AclMode mode
    ) {
        if (items == null) {
            return;
        }

        adminDao.deleteAccountAccessByUserTenantAccessId(userTenantAccessId);
        if (mode != AdminTenantAccess.AclMode.CUSTOM) {
            return;
        }

        List<AdminTenantAccountAccess> rows = buildAccountRows(userTenantAccessId, items);
        if (rows.isEmpty()) {
            return;
        }

        try {
            adminDao.insertAdminTenantAccountAccessBatch(rows);
        } catch (Exception e) {
            throw new BusinessException("Insert Admin Tenant Account Access Failed!");
        }
    }

    private void replaceProcessAcl(
            Long userTenantAccessId,
            List<AdminDTO.ProcessPermissionItem> items,
            AdminTenantAccess.AclMode mode
    ) {
        if (items == null) {
            return;
        }

        adminDao.deleteProcessAccessByUserTenantAccessId(userTenantAccessId);
        if (mode != AdminTenantAccess.AclMode.CUSTOM) {
            return;
        }

        List<AdminTenantProcessAccess> rows = buildProcessRows(userTenantAccessId, items);
        if (rows.isEmpty()) {
            return;
        }

        try {
            adminDao.insertAdminTenantProcessAccessBatch(rows);
        } catch (Exception e) {
            throw new BusinessException("Insert Admin Tenant Process Access Failed!");
        }
    }

    private List<AdminTenantAccountAccess> buildAccountRows(
            Long userTenantAccessId,
            List<AdminDTO.AccountPermissionItem> items
    ) {
        List<AdminTenantAccountAccess> rows = new ArrayList<>();
        for (AdminDTO.AccountPermissionItem item : items) {
            if (item.getAccountId() == null || item.getAccountId() <= 0) {
                continue;
            }
            rows.add(new AdminTenantAccountAccess(
                    null,
                    userTenantAccessId,
                    item.getAccountId(),
                    null
            ));
        }
        return rows;
    }

    private List<AdminTenantProcessAccess> buildProcessRows(
            Long userTenantAccessId,
            List<AdminDTO.ProcessPermissionItem> items
    ) {
        List<AdminTenantProcessAccess> rows = new ArrayList<>();
        for (AdminDTO.ProcessPermissionItem item : items) {
            if (item.getProcessId() == null || item.getProcessId() <= 0) {
                continue;
            }
            rows.add(new AdminTenantProcessAccess(
                    null,
                    userTenantAccessId,
                    item.getProcessId(),
                    null
            ));
        }
        return rows;
    }

    private Admin mapDtoToAdmin(AdminDTO dto) {
        Admin admin = new Admin();
        admin.setLoginId(dto.getLoginId());
        admin.setName(dto.getName());
        admin.setEmail(dto.getEmail());
        admin.setPassword(dto.getPassword());
        admin.setSecondaryPassword(dto.getSecondaryPassword());
        admin.setReadOnly(dto.getReadOnly() != null ? dto.getReadOnly() : true);
        admin.setRoleCode(dto.getRole());
        return admin;
    }

    private AdminDTO buildResult(Admin admin, AdminTenantAccess primaryAccess) {
        AdminDTO result = new AdminDTO();
        result.setAdmin(admin);
        result.setAdminTenantAccess(primaryAccess);
        return result;
    }

    private Admin loadExistingAdmin(AdminDTO dto) {
        int userId = resolveUserId(dto);
        Admin existing = adminDao.findAdminById(userId);
        if (existing == null) {
            throw new BusinessException("User not found!");
        }

        AdminDTO scoped = adminDao.findAdminByUserIdAndTenantId(userId, dto.getScopeTenantId());
        if (scoped == null) {
            throw new BusinessException("User not found!");
        }
        return existing;
    }

    private Integer resolveUserId(AdminDTO dto) {
        if (dto.getId() != null && dto.getId() > 0) {
            return dto.getId();
        }
        if (dto.getAdmin() != null && dto.getAdmin().getId() != null && dto.getAdmin().getId() > 0) {
            return dto.getAdmin().getId();
        }
        return null;
    }

    private Integer resolveScopeTenantId(AdminDTO dto) {
        if (dto.getScopeTenantId() != null && dto.getScopeTenantId() > 0) {
            return dto.getScopeTenantId();
        }
        SessionUser session = SecurityUtils.currentUser();
        return session != null ? session.tenant_id : null;
    }

    private Integer resolveRoleId(String role) {
        if (role == null || role.isBlank()) {
            throw new BusinessException("Invalid role");
        }
        AdminRole staffRole = permissionDao.findStaffRoleByCode(normalizeStaffRoleCode(role));
        if (staffRole == null || staffRole.getId() == null) {
            throw new BusinessException("Invalid role");
        }
        return staffRole.getId();
    }

    private AclModes resolveAclModes(AdminDTO dto) {
        List<AdminDTO.AccountPermissionItem> accountItems =
                dto.getAccountPermissions() != null ? dto.getAccountPermissions() : List.of();
        List<AdminDTO.ProcessPermissionItem> processItems =
                dto.getProcessPermissions() != null ? dto.getProcessPermissions() : List.of();

        AdminTenantAccess.AclMode accountMode = accountItems.isEmpty()
                ? AdminTenantAccess.AclMode.ALL
                : AdminTenantAccess.AclMode.CUSTOM;
        AdminTenantAccess.AclMode processMode = processItems.isEmpty()
                ? AdminTenantAccess.AclMode.ALL
                : AdminTenantAccess.AclMode.CUSTOM;
        return new AclModes(accountMode, processMode);
    }

    private void requireLoggedIn() {
        if (SecurityUtils.currentUser() == null) {
            throw new BusinessException("Not logged in");
        }
    }

    private void normalizeAdminFields(Admin admin) {
        admin.setLoginId(admin.getLoginId().trim().toUpperCase(Locale.ROOT));
        admin.setName(admin.getName().trim());
        admin.setEmail(admin.getEmail().trim().toLowerCase(Locale.ROOT));
    }

    private void validateRequiredForCreate(Admin admin) {
        if (admin.getRoleId() == null) {
            throw new BusinessException("Invalid role");
        }
        if (admin.getLoginId() == null || admin.getLoginId().isBlank()) {
            throw new BusinessException("Login Id is required");
        }
        if (admin.getName() == null || admin.getName().isBlank()) {
            throw new BusinessException("Name is required");
        }
        if (admin.getEmail() == null || admin.getEmail().isBlank()) {
            throw new BusinessException("Email is required");
        }
        if (admin.getPassword() == null || admin.getPassword().isBlank()) {
            throw new BusinessException("Password is required");
        }
    }

    private void assertNoDuplicateLoginId(String loginId) {
        if (adminDao.findDuplicateLoginId(loginId) != null) {
            throw new BusinessException("Duplicate Login Id!");
        }
    }

    private void assertNoDuplicateEmail(String email, Integer excludeId) {
        Admin duplicate = excludeId == null
                ? adminDao.findDuplicateEmail(email)
                : adminDao.findDuplicateEmailExcludingId(email, excludeId);
        if (duplicate != null) {
            throw new BusinessException("Duplicate Email!");
        }
    }

    private void encodePasswords(Admin admin) {
        admin.setPassword(passwordEncoder.encode(admin.getPassword()));
        if (admin.getSecondaryPassword() == null || admin.getSecondaryPassword().isBlank()) {
            admin.setSecondaryPassword(null);
        } else {
            admin.setSecondaryPassword(passwordEncoder.encode(admin.getSecondaryPassword()));
        }
    }

    private static String normalizeStaffRoleCode(String role) {
        return role.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
    }

    private record AclModes(AdminTenantAccess.AclMode account, AdminTenantAccess.AclMode process) {
    }

    @Override
    public AdminDTO updateStatusById(Integer userId, Integer scopeTenantId) {
        requireLoggedIn();
        SessionUser session = SecurityUtils.currentUser();

        if (userId == null || userId <= 0 || scopeTenantId == null || scopeTenantId <= 0) {
            throw new BusinessException("Invalid request");
        }
        assertNotTenantOwner(userId, scopeTenantId);
        if (session.user_id != null && session.user_id.equals(userId)) {
            throw new BusinessException("You cannot toggle your own status");
        }

        AdminDTO scoped = adminDao.findAdminByUserIdAndTenantId(userId, scopeTenantId);
        if (scoped == null || scoped.getAdmin() == null) {
            throw new BusinessException("User not found!");
        }

        Admin admin = scoped.getAdmin();
        Admin.UserStatus current = admin.getStatus() != null ? admin.getStatus() : Admin.UserStatus.ACTIVE;
        Admin.UserStatus newStatus = current == Admin.UserStatus.ACTIVE
                ? Admin.UserStatus.INACTIVE
                : Admin.UserStatus.ACTIVE;

        try {
            adminDao.updateStatusById(userId, newStatus);
        } catch (Exception e) {
            throw new BusinessException("Update Admin Status Failed!");
        }

        AdminDTO result = adminDao.findAdminByUserIdAndTenantId(userId, scopeTenantId);
        if (result == null) {
            throw new BusinessException("User not found!");
        }
        return result;
    }

    @Override
    @Transactional
    public void deleteAdminByIdAndStatus(Integer userId, Integer scopeTenantId) {
        requireLoggedIn();
        SessionUser session = SecurityUtils.currentUser();

        if (userId == null || userId <= 0 || scopeTenantId == null || scopeTenantId <= 0) {
            throw new BusinessException("Invalid request");
        }
        assertNotTenantOwner(userId, scopeTenantId);
        if (session.user_id != null && session.user_id.equals(userId)) {
            throw new BusinessException("You cannot delete your own account");
        }

        AdminDTO scoped = adminDao.findAdminByUserIdAndTenantId(userId, scopeTenantId);
        if (scoped == null || scoped.getAdmin() == null) {
            throw new BusinessException("User not found!");
        }
        if (scoped.getAdmin().getStatus() == Admin.UserStatus.ACTIVE) {
            throw new BusinessException("User is not inactive, cannot be deleted!");
        }

        try {
            adminDao.deleteTenantAccessByUserIdAndTenantId(userId, scopeTenantId);
        } catch (Exception e) {
            throw new BusinessException("Delete Admin Tenant Access failed!");
        }

        try {
            adminDao.deleteAdminByIdAndStatus(userId, Admin.UserStatus.INACTIVE);
        } catch (Exception e) {
            throw new BusinessException("Delete Admin failed!");
        }
    }
}
