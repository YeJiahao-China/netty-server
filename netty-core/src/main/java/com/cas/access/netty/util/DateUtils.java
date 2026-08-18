package com.cas.access.netty.util;

import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;

/**
 * 日期时间工具类。
 * 提供全局统一的日期格式化器，避免在各处重复创建。
 * <p>
 * SimpleDateFormat 非线程安全，本类使用 ThreadLocal 封装，确保并发安全。
 */
public class DateUtils {

    /**
     * 标准日期时间格式：yyyy-MM-dd HH:mm:ss
     */
    public static final String DATETIME_PATTERN = "yyyy-MM-dd HH:mm:ss";

    /**
     * 线程安全的 DateTimeFormatter（Java 8+ 推荐使用）
     */
    public static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern(DATETIME_PATTERN);

    /**
     * 线程安全的 SimpleDateFormat（用于兼容旧代码或需要 SimpleDateFormat 的场景）
     */
    private static final ThreadLocal<SimpleDateFormat> SIMPLE_DATE_FORMAT = 
            ThreadLocal.withInitial(() -> new SimpleDateFormat(DATETIME_PATTERN));

    /**
     * 获取线程安全的 SimpleDateFormat 实例。
     * <p>
     * 注意：使用完毕后不需要手动 remove，ThreadLocal 会在 GC 时处理，
     * 但如果在特定线程（如线程池线程）中大量使用，建议在不需要时调用 
     * {@link #removeDateFormat()} 以防止内存泄漏。
     */
    public static SimpleDateFormat getDateFormat() {
        return SIMPLE_DATE_FORMAT.get();
    }

    /**
     * 移除当前线程的 SimpleDateFormat 实例。
     */
    public static void removeDateFormat() {
        SIMPLE_DATE_FORMAT.remove();
    }

    /**
     * 格式化 Date 为字符串。
     *
     * @param date 日期
     * @return 格式化后的字符串，若 date 为 null 则返回空字符串
     */
    public static String format(Date date) {
        if (date == null) {
            return "";
        }
        return getDateFormat().format(date);
    }

    /**
     * 格式化 LocalDateTime 为字符串。
     *
     * @param dateTime 日期时间
     * @return 格式化后的字符串，若 dateTime 为 null 则返回空字符串
     */
    public static String format(LocalDateTime dateTime) {
        if (dateTime == null) {
            return "";
        }
        return dateTime.format(DATETIME_FORMATTER);
    }

    /**
     * 格式化 java.sql.Timestamp 为字符串。
     *
     * @param timestamp 时间戳
     * @return 格式化后的字符串，若 timestamp 为 null 则返回空字符串
     */
    public static String format(java.sql.Timestamp timestamp) {
        if (timestamp == null) {
            return "";
        }
        return getDateFormat().format(timestamp);
    }

    private DateUtils() {
        // 工具类禁止实例化
    }
}
