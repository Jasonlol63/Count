package com.eazycount.audit;

import com.eazycount.dao.UserDao;
import com.eazycount.dto.UserListDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Resolves an account id to its display name for hand-written audit summaries (e.g. "批准 CONTRA
 * 交易（收 张三 / 付 李四）") — shared so every module that needs "this account's name, not its id"
 * in a summary doesn't carry its own copy of the same lookup + fallback.
 */
@Component
public class AuditAccountNames {

    @Autowired
    private UserDao userDao;

    /** The account's name, falling back to its id when unset/blank or the account can't be found. */
    public String resolve(Integer accountId, Integer tenantId) {
        if (accountId == null) {
            return "?";
        }
        UserListDTO account = userDao.findUserByIdAndTenantId(accountId, tenantId);
        return account != null && account.getName() != null && !account.getName().isBlank()
                ? account.getName()
                : String.valueOf(accountId);
    }
}
