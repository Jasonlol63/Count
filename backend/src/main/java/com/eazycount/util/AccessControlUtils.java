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
 *
 * read_only 开关目前只在 Partnership、Audit 账号的界面上暴露，但只要账号被打上这个标记，后端一律拦截写操作，不区分角色。
 */
public final class AccessControlUtils {

    /* 对 Admin（员工列表）页面有写权限的角色；OWNER 无限制单独处理，其余角色没有该页面入口。 */
    private static final Set<String> ADMIN_PAGE_MANAGER_ROLES = Set.of("PARTNERSHIP", "ADMIN", "MANAGER", "SUPERVISOR");

    private AccessControlUtils() {
    }

    public static String normalizeRole(String role) {
        return role == null ? "" : role.trim().toUpperCase(Locale.ROOT);
    }

    public static boolean isOwner(String role) {
        return "OWNER".equals(normalizeRole(role));
    }

    /* 未登录或账号 read_only=1 时抛出异常；所有写操作方法的第一行都应调用此方法。 */
    public static void requireWritable(SessionUser session) {
        if (session == null) {
            throw new BusinessException("Not logged in");
        }
        if (session.read_only == 1) {
            throw new BusinessException("Read-only access cannot perform this action");
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
}
