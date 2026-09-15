package com.eazycount.util;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/*
 * 字符串/集合归一化，取代各 Service 里重复的 trim/去重/大写化写法。
 */
public final class NormalizeUtils {

    private NormalizeUtils() {
    }

    /* null 或 trim 后为空 -> null；否则返回 trim 后的值。*/
    public static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /* null -> ""；否则返回 trim 后的值。*/
    public static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    /* 过滤 null/空白，trim + 转大写，去重，返回 List（原 list 为 null/空则返回空 List）。*/
    public static List<String> normalizeUpperList(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        return raw.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(s -> s.trim().toUpperCase(Locale.ROOT))
                .distinct()
                .toList();
    }

    /* 过滤 null/非正数，去重（保留首次出现的顺序），返回 List（原 list 为 null/空则返回空 List）。*/
    public static List<Integer> normalizeIds(List<Integer> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        Set<Integer> unique = new LinkedHashSet<>();
        for (Integer id : raw) {
            if (id != null && id > 0) {
                unique.add(id);
            }
        }
        return new ArrayList<>(unique);
    }
}
