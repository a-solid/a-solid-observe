-- B8: 给 executions 的 trigger_type 加 CHECK 约束（含 UNKNOWN 兜底值）。
-- 合表后 failed_executions 已并入 executions（V1），故只对 executions 加约束。
--
-- 注意：仓库当前用 ddl-auto=update（无 Flyway 接管），H2 由 @Entity 驱动建表（CHECK 不由 Hibernate 生成）。
-- 本文件作为生产环境（如 Sybase ASE）手工应用的参考，与 V1 同款风格。
-- enums: CDC / CRON / API / UNKNOWN（UNKNOWN 为 ExecutionMeta.triggerType 缺省时的兜底）。

ALTER TABLE executions ADD CONSTRAINT ck_executions_trigger_type
    CHECK (trigger_type IN ('CDC', 'CRON', 'API', 'UNKNOWN'));
