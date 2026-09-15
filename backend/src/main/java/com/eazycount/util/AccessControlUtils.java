package com.eazycount.util;

import com.eazycount.common.BusinessException;
import com.eazycount.security.SessionUser;

import java.util.Locale;
import java.util.Set;

/*
 * 权限校验统一入口，供各 Service 写操作调用，避免每个模块各写一套 read_only / 层级判断。
 *
 * 角色层级（数值越小权限越高，对应 user_role.hierarchy_level）：
 * OWNER(1) > PARTNERSHIP(2) > ADMIN(3) > MANAGER(4) > SUPERVISOR(5) > ACCOUNTANT/AUDIT/CUSTOMER_SERVICE(6-8)。
 */
//read_only 开关目前只在 Partnership、Audit 账号的界面上暴露，但只要账号被打上这个标记，后端一律拦截写操作，不区分角色。
public final class AccessControlUtils {

    /*
     * 对 Admin（员工列表）页面有写权限的角色；OWNER 无限制单独处理。AUDIT / ACCOUNTANT / CUSTOMER_SERVICE
     * 默认没有 Admin 页面入口，但账号级权限覆盖（user_permission_override）可以额外给某个账号开通菜单可见性，
     * 一旦开通，写操作按统一规则走：read_only=1 时禁止任何修改，read_only=0 时按层级只能管理
     * hierarchy_level 比自己大的角色（ACCOUNTANT=6 能管到 AUDIT=7/CUSTOMER_SERVICE=8；
     * AUDIT=7 只能管到 CUSTOMER_SERVICE=8；CUSTOMER_SERVICE=8 是最低层级，实际管不到任何人）。
     */
    private static final Set<String> ADMIN_PAGE_MANAGER_ROLES =
            Set.of("PARTNERSHIP", "ADMIN", "MANAGER", "SUPERVISOR", "AUDIT", "ACCOUNTANT", "CUSTOMER_SERVICE");

    /* Domain page "No Expiry Date" (Tenant.PERMANENT_EXPIRATION_DATE) — only Admin and above may set it. */
    private static final Set<String> PERMANENT_EXPIRATION_ROLES = Set.of("OWNER", "PARTNERSHIP", "ADMIN");

    /*
     * Contra Inbox: roles whose manual transactions (PAYMENT/CLAIM/CLEAR/CONTRA/RATE/ADJUSTMENT/PROFIT)
     * are always auto-approved, regardless of transaction date. Every other role (including Partnership,
     * and independent of that role's read_only flag) only auto-approves a same-day-or-future transaction
     * date; a backdated one goes to PENDING and needs one of these roles to approve/reject it.
     */
    private static final Set<String> MANUAL_TRANSACTION_APPROVAL_EXEMPT_ROLES =
            Set.of("OWNER", "ADMIN", "MANAGER");

    private AccessControlUtils() {
    }

    public static String normalizeRole(String role) {
        return role == null ? "" : role.trim().toUpperCase(Locale.ROOT);
    }

    public static boolean isOwner(String role) {
        return "OWNER".equals(normalizeRole(role));
    }

    /* IT accounts come from ItOperatorRegistry (it-operators.yml), not the admin/user tables —
     * deliberately kept out of ADMIN_PAGE_MANAGER_ROLES / the hierarchy above, never intermixed
     * with the Admin role system. See docs/it-role-audit-log.md. */
    public static boolean isItOperator(String role) {
        return "IT".equals(normalizeRole(role));
    }

    /* 未登录或账号 read_only=1 时抛出异常；所有写操作方法的第一行都应调用此方法。*/
    public static void requireWritable(SessionUser session) {
        if (session == null) {
            throw new BusinessException("Not logged in");
        }
        if (session.read_only == 1) {
            throw new BusinessException("Read-only access cannot perform this action");
        }
    }

    /* IT 控制台专属接口（audit-log 查询）第一行调用——非 IT 账号一律拒绝，不分层级。*/
    public static void requireItOperator(SessionUser session) {
        if (session == null) {
            throw new BusinessException("Not logged in");
        }
        if (!isItOperator(session.role)) {
            throw new BusinessException("IT access only");
        }
    }

    /*
     * Admin（员工列表）页面写操作校验。
     *
     * actorHierarchyLevel  操作者角色的 hierarchy_level
     * isSelf               是否在操作自己的账号
     * targetHierarchyLevel 目标账号（若改角色则为改后）的 hierarchy_level
     * roleFieldChanging    本次请求是否要修改目标的 role 字段
     */
    public static void assertCanManageAdminTarget(
            SessionUser actor,
            int actorHierarchyLevel,
            boolean isSelf,
            int targetHierarchyLevel,
            boolean roleFieldChanging
    ) {
        if (actor == null) {
            throw new BusinessException("Not logged in");
        }
        if (isOwner(actor.role)) {
            return;
        }
        if (!ADMIN_PAGE_MANAGER_ROLES.contains(normalizeRole(actor.role))) {
            throw new BusinessException("No permission");
        }

        requireWritable(actor);

        if (isSelf) {
            if (roleFieldChanging) {
                throw new BusinessException("Cannot change your own role");
            }
            return;
        }

        if (actorHierarchyLevel >= targetHierarchyLevel) {
            throw new BusinessException("No permission to manage this role");
        }
    }

    /* Rejects a Domain page save that sets/keeps Tenant.PERMANENT_EXPIRATION_DATE unless the actor is Admin+. */
    public static void assertCanSetPermanentExpiration(SessionUser session) {
        if (session == null) {
            throw new BusinessException("Not logged in");
        }
        if (!PERMANENT_EXPIRATION_ROLES.contains(normalizeRole(session.role))) {
            throw new BusinessException("No permission to set No Expiry Date");
        }
    }

    /* Contra Inbox: true if this role's manual transactions always auto-approve (see MANUAL_TRANSACTION_APPROVAL_EXEMPT_ROLES). */
    public static boolean isManualTransactionApprovalExempt(String role) {
        return MANUAL_TRANSACTION_APPROVAL_EXEMPT_ROLES.contains(normalizeRole(role));
    }

    /* Contra Inbox: only Owner/Admin/Manager may approve or reject a pending manual transaction. */
    public static void requireContraInboxApprover(SessionUser session) {
        requireWritable(session);
        if (!isManualTransactionApprovalExempt(session.role)) {
            throw new BusinessException("No permission to approve or reject Contra Inbox transactions");
        }
    }
}
