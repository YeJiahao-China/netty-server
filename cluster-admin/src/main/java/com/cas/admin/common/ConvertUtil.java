package com.cas.admin.common;

/**
 * 类型转换工具方法。
 */
public final class ConvertUtil {

    private ConvertUtil() {
    }

    public static long toLong(Object o) {
        return o instanceof Number ? ((Number) o).longValue() : 0;
    }
}
