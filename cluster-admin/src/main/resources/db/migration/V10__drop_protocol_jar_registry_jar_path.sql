-- 删除 jar_path 列
-- 绝对路径是节点本地概念，跨节点部署时不一致（Linux/Windows 路径不同）
-- jar 文件路径由各节点根据 netty.server.protocol.jar-dir 配置 + 协议名推导
-- DB 只保留 jar_bytes 用于分发，不再存储本地路径
ALTER TABLE protocol_jar_registry DROP COLUMN IF EXISTS jar_path;
