package com.cas.admin.common;

import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * 统一 API 响应工具：所有 Controller 共用的 ok()/fail() 构造方法与时间格式常量。
 */
public final class ApiResponse {

    /** 通用日期时间格式 */
    public static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private ApiResponse() {
    }

    public static Map<String, Object> ok() {
        Map<String, Object> m = new HashMap<>();
        m.put("success", true);
        return m;
    }

    public static Map<String, Object> fail(String reason) {
        Map<String, Object> m = new HashMap<>();
        m.put("success", false);
        m.put("reason", reason);
        return m;
    }
}
