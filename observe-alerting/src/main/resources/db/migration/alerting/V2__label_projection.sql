-- B9 / ADR-0004：alert 维度统一进 labels（labels JSON 列已有），下线 team/application/pipeline_labels
-- denormalize 副本，加 label_team/label_app/label_line 投影列支撑 Sybase B-tree 索引（JSON 无索引能力）。
-- 生产参考（alerting 库无 Flyway，schema 由 ddl-auto 驱动；上线前真实库冒烟）。
-- H2 测试库由 @Entity 自动建表，本脚本不影响 H2。

ALTER TABLE alerts DROP COLUMN team;
ALTER TABLE alerts DROP COLUMN application;
ALTER TABLE alerts DROP COLUMN pipeline_labels;

ALTER TABLE alerts ADD COLUMN label_team VARCHAR;
ALTER TABLE alerts ADD COLUMN label_app VARCHAR;
ALTER TABLE alerts ADD COLUMN label_line VARCHAR;

-- 原 idx_alerts_team_time(team, starts_at) 依赖已删的 team 列；按 label_team 重建（告警面板按 team 过滤路径）。
DROP INDEX idx_alerts_team_time;
CREATE INDEX idx_alerts_label_team ON alerts(label_team);
CREATE INDEX idx_alerts_label_app ON alerts(label_app);
