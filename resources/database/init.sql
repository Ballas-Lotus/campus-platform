-- 校园综合服务平台 · 数据库初始化脚本（compose 首次启动时自动执行）
-- 注意：campus_platform 库由 docker-compose 的 MYSQL_DATABASE 环境变量创建，此处仅做占位。
-- 阶段 1 起：建表脚本将写入本目录 schema.sql（同样会被挂载执行）。
USE campus_platform;

SELECT 'campus-platform init placeholder' AS init_message;
