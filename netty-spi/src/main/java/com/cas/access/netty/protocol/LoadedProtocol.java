package com.cas.access.netty.protocol;

import lombok.Builder;
import lombok.Getter;

import java.net.URLClassLoader;

/**
 * Registry 中每个已加载协议的元数据快照。
 * 除 {@link #active} 外字段均不可变，便于并发读。
 *
 * @author yjh_c
 */
@Getter
@Builder
public class LoadedProtocol {

    private final String name;
    private final String version;
    private final String description;
    private final ProtocolDecoderProvider provider;

    /** 外部 jar 对应的 URLClassLoader；内置协议为 null（用 AppClassLoader） */
    private final URLClassLoader classLoader;

    /** jar 文件绝对路径；内置协议固定为 "builtin" */
    private final String source;

    /** 加载时间戳 */
    private final long loadedAt;

    /** 是否可用（卸载前置 false，新进 initChannel 不再使用此 Provider） */
    @Builder.Default
    private volatile boolean active = true;
}
