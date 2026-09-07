package com.cas.admin.cluster;

import lombok.Data;

/** 管理中心广播请求体（与旧 access 版同字段，但独立定义避免耦合） */
@Data
public class BroadcastRequest {
    private String nodeType; // ACCESS / PARSER / 空=全部 UP
    private String method;   // GET/POST/PUT/DELETE
    private String path;     // 以/开头
    private Object body;
}
