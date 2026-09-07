-- =============================================================================
-- V9: 删除 protocol_jar_registry.active 列
-- active 字段已由 status 字段统一替代：
--   REGISTERED = 已注册活跃（原 active=true）
--   UNLOADED  = 已卸载（原 active=false）
--   INIT      = 刚入库待分发
--   FAILED    = 分发失败
-- =============================================================================
-- 先将已有数据迁移：active=true → status=REGISTERED，active=false → status=UNLOADED
UPDATE protocol_jar_registry SET status = 'REGISTERED' WHERE active = TRUE  AND (status IS NULL OR status = 'INIT');
UPDATE protocol_jar_registry SET status = 'UNLOADED'  WHERE active = FALSE AND (status IS NULL OR status = 'INIT');

-- 删除旧索引
DROP INDEX IF EXISTS idx_protocol_jar_active;

-- 删除 active 列
ALTER TABLE protocol_jar_registry DROP COLUMN IF EXISTS active;
