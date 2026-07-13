package com.eazycount.service.impl;

import com.eazycount.dao.AutoRenewDao;
import com.eazycount.common.BusinessException;
import com.eazycount.dao.DomainDao;
import com.eazycount.dao.TenantDao;
import com.eazycount.dao.UserDao;
import com.eazycount.dto.AutoRenewDTO;
import com.eazycount.dto.DomainFeeSettingsDTO;
import com.eazycount.dto.UserListDTO;
import com.eazycount.entity.Tenant;
import com.eazycount.entity.Owner;
import com.eazycount.entity.User;
import com.eazycount.security.SecurityUtils;
import com.eazycount.security.SessionUser;
import com.eazycount.service.AutoRenewService;
import com.eazycount.service.DomainService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

@Service
public class AutoRenewServiceImpl implements AutoRenewService {

    @Autowired
    private AutoRenewDao autoRenewDao;

    @Autowired
    private DomainService domainService;

    @Autowired
    private DomainDao domainDao;

    @Autowired
    private TenantDao tenantDao;

    @Autowired
    private UserDao userDao;

    private static final int WINDOW_DAYS = 30;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Override
    public Map<String, Object> getAutoRenewCounts(String tenantType, int windowDays) {
        // 保证到期租户已加载进申请表
        autoRenewDao.syncWindowRequests(windowDays);

        // 统一按当前页签过滤统计
        int pendingCnt = autoRenewDao.countRequestsByStatus("pending", tenantType, windowDays);
        int approvedCnt = autoRenewDao.countRequestsByStatus("approved", tenantType, windowDays);
        int rejectedCnt = autoRenewDao.countRequestsByStatus("rejected", tenantType, windowDays);
        int totalCnt = pendingCnt + approvedCnt + rejectedCnt;

        Map<String, Object> countsMap = new HashMap<>();
        countsMap.put("pending", pendingCnt);
        countsMap.put("approved", approvedCnt);
        countsMap.put("rejected", rejectedCnt);
        countsMap.put("total", totalCnt);

        // 统计 Company 与 Group 的各自 Pending 数量，用于页签红点徽章
        int pendingCompany = autoRenewDao.countPendingByTenantType("COMPANY", windowDays);
        int pendingGroup = autoRenewDao.countPendingByTenantType("GROUP", windowDays);

        Map<String, Object> tabPendingCounts = new HashMap<>();
        tabPendingCounts.put("company", pendingCompany);
        tabPendingCounts.put("group", pendingGroup);

        Map<String, Object> stats = new HashMap<>();
        stats.put("counts", countsMap);
        stats.put("tab_pending_counts", tabPendingCounts);
        return stats;
    }

    @Override
    public Map<String, Object> getAutoRenewList(String status, String tenantType, String dateFromStr,
            String dateToStr) {

        LocalDate dateFrom = parseLocalDate(dateFromStr);
        LocalDate dateTo = parseLocalDate(dateToStr);
        String statusFilter = "all".equalsIgnoreCase(status) ? null : status;

        // 规范化 tenantType 传参 ("COMPANY" / "GROUP")
        String tenantTypeFilter = tenantType != null ? tenantType.toUpperCase() : "COMPANY";

        // 同步数据
        autoRenewDao.syncWindowRequests(WINDOW_DAYS);

        // 按当前页签类型查询列表
        List<AutoRenewDTO> rows = autoRenewDao.selectAutoRenewList(
                statusFilter,
                tenantTypeFilter,
                dateFrom,
                dateTo,
                WINDOW_DAYS);

        // 获取 C168 关联账户列表与默认账户配置，以适配前端 Approve 操作
        Tenant c168Tenant = tenantDao.findTenantByCode("C168");
        Integer c168TenantId = c168Tenant != null ? c168Tenant.getId() : null;
        List<UserListDTO> c168Users = new ArrayList<>();
        if (c168TenantId != null) {
            c168Users = userDao.findUserByTenantId(c168TenantId);
        }

        List<Map<String, Object>> accountsList = new ArrayList<>();
        for (UserListDTO u : c168Users) {
            if (u.getStatus() == User.AccountStatus.ACTIVE) {
                Map<String, Object> m = new HashMap<>();
                m.put("id", u.getId());
                m.put("account_code", u.getAccountId());
                m.put("name", u.getName());
                accountsList.add(m);
            }
        }

        // 动态填充行属性 (entity_type, default accounts, codes 等)
        for (AutoRenewDTO row : rows) {
            // 默认收款账户为 C168 账户中 code 为 "C168" 的账户
            Integer defaultToId = null;
            for (UserListDTO u : c168Users) {
                if (u.getStatus() == User.AccountStatus.ACTIVE && "C168".equalsIgnoreCase(u.getAccountId())) {
                    defaultToId = u.getId();
                    break;
                }
            }
            row.setDefaultToAccountId(defaultToId);
            if (row.getToAccountId() == null) {
                row.setToAccountId(defaultToId);
            }

            // 默认扣款账户为 C168 账户中 code 匹配 companyCode 的账户，或匹配 owner_code_companyCode 遗留格式的账户
            Integer defaultFromId = null;
            String compCode = row.getCompanyCode();
            if (compCode != null && !compCode.trim().isEmpty()) {
                String codeClean = compCode.trim().toUpperCase();
                for (UserListDTO u : c168Users) {
                    if (u.getStatus() == User.AccountStatus.ACTIVE && codeClean.equalsIgnoreCase(u.getAccountId())) {
                        defaultFromId = u.getId();
                        break;
                    }
                }
                if (defaultFromId == null && row.getOwnerId() != null) {
                    Owner owner = domainDao.findOwnerById(row.getOwnerId());
                    if (owner != null && owner.getOwnerCode() != null) {
                        String ownerCodeClean = owner.getOwnerCode().replaceAll("[^A-Za-z0-9]", "").toUpperCase();
                        String legacyCode = ownerCodeClean + "_" + codeClean;
                        for (UserListDTO u : c168Users) {
                            if (u.getStatus() == User.AccountStatus.ACTIVE
                                    && legacyCode.equalsIgnoreCase(u.getAccountId())) {
                                defaultFromId = u.getId();
                                break;
                            }
                        }
                    }
                }
            }
            row.setDefaultFromAccountId(defaultFromId);
            if (row.getFromAccountId() == null) {
                row.setFromAccountId(defaultFromId);
            }

            // 映射账户 Code
            if (row.getFromAccountId() != null) {
                for (UserListDTO u : c168Users) {
                    if (u.getId().equals(row.getFromAccountId())) {
                        row.setFromAccountCode(u.getAccountId());
                        break;
                    }
                }
            }
            if (row.getToAccountId() != null) {
                for (UserListDTO u : c168Users) {
                    if (u.getId().equals(row.getToAccountId())) {
                        row.setToAccountCode(u.getAccountId());
                        break;
                    }
                }
            }

            // 动态设置前端逻辑需要的 can_approve & can_delete 标记
            boolean accountsResolved = row.getFromAccountId() != null && row.getToAccountId() != null
                    && !row.getFromAccountId().equals(row.getToAccountId());
            row.setCanApprove(
                    "pending".equalsIgnoreCase(row.getStatus()) && !row.getIsPaymentDeleted() && accountsResolved);
            row.setCanDelete("approved".equalsIgnoreCase(row.getStatus()) && row.getRequestId() != null
                    && row.getRequestId() > 0 && row.getTransactionId() != null && !row.getIsPaymentDeleted());
        }

        // 价格配置
        DomainFeeSettingsDTO feeSettings = domainService.findDomainFeeSettings();

        // 计算当前页签的分类统计数和红点徽章统计数
        Map<String, Object> stats = this.getAutoRenewCounts(tenantTypeFilter, WINDOW_DAYS);

        // 组合结果
        Map<String, Object> responseData = new HashMap<>();
        responseData.put("rows", rows);
        responseData.put("accounts", accountsList);
        responseData.put("counts", stats.get("counts"));
        responseData.put("tab_pending_counts", stats.get("tab_pending_counts"));
        responseData.put("fee_settings", feeSettings);
        responseData.put("can_edit", true);

        return responseData;
    }

    private LocalDate parseLocalDate(String dateStr) {
        if (dateStr == null || dateStr.trim().isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(dateStr.trim(), DATE_FORMATTER);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    @Override
    public void rejectRequest(Integer requestId) {
        SessionUser session = SecurityUtils.currentUser();
        if (session == null || session.user_id == null) {
            throw new BusinessException("Not logged in");
        }

        if (requestId == null || requestId <= 0) {
            throw new BusinessException("Invalid request id");
        }

        AutoRenewDTO request = autoRenewDao.selectRequestById(requestId);
        if (request == null) {
            throw new RuntimeException("Auto renew request not found");
        }

        if (!"pending".equalsIgnoreCase(request.getStatus())) {
            throw new RuntimeException("Auto renew request is not pending");
        }

        String processedBy = session.login_id != null ? session.login_id : "system";

        autoRenewDao.rejectRequest(requestId, processedBy);
    }
}
