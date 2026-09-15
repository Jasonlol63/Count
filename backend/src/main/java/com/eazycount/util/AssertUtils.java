package com.eazycount.util;

import com.eazycount.common.BusinessException;

import java.util.Optional;

/*
 * 通用参数/结果断言，取代各 Service 里重复的 "null 就抛 BusinessException" 写法。
 * 与租户/权限相关的校验放在 AccessControlUtils，这里只做与业务身份无关的基础断言。
 */
public final class AssertUtils {

    private AssertUtils() {
    }

    /* entity 为 null 时抛出异常，否则原样返回，便于链式赋值。*/
    public static <T> T requireFound(T entity, String message) {
        if (entity == null) {
            throw new BusinessException(message);
        }
        return entity;
    }

    /* Optional 版本，等价于 optional.orElseThrow(() -> new BusinessException(message))。*/
    public static <T> T requireFound(Optional<T> entity, String message) {
        return entity.orElseThrow(() -> new BusinessException(message));
    }

    /* value 为 null 或 <= 0 时抛出异常；fieldName 用于生成统一格式的错误信息，如 "Invalid currencyId!"。*/
    public static void requirePositive(Integer value, String fieldName) {
        if (value == null || value <= 0) {
            throw new BusinessException("Invalid " + fieldName + "!");
        }
    }
}
