package com.cas.access.netty.cluster;

import lombok.Data;

/**
 * 广播请求体：协调者据此把同一请求转发到目标类型的所有 UP 节点。
 */
@Data
public class BroadcastRequest {

    /** 目标节点类型 ACCESS / PARSER（为空则所有 UP 节点） */
    private String nodeType;

    /** HTTP 方法 GET/POST/PUT/DELETE */
    private String method;

    /** 各节点执行的路径，如 /ports/8080/close */
    private String path;

    /** 转发请求体（任意 JSON 对象） */
    private Object body;
}
