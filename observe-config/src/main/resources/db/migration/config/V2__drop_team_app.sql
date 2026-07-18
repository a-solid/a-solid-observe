-- B9 / ADR-0004：pipeline 维度统一进 labels，下线 team/application 一等列。
-- 生产参考（observe 配置库无 Flyway，schema 由 ddl-auto 驱动；上线前真实库冒烟）。
-- H2 测试库由 @Entity 自动建表，本脚本不影响 H2。

DROP INDEX idx_pipelines_team_app;
ALTER TABLE pipelines DROP COLUMN team;
ALTER TABLE pipelines DROP COLUMN application;
