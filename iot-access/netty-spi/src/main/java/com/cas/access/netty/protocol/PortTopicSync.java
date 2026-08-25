package com.cas.access.netty.protocol;

public interface PortTopicSync {

    /**
     * 持久化一个端口绑定（upsert）。
     * 若已存在则更新 topicName + enabled=true；否则插入。
     */
    void upsertPortTopicBind(int port, String topicName);

    /**
     * 持久化解绑：标记 enabled=false（保留记录）。
     */
    void persistUnbind(int port);

}
