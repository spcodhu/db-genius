-- ============================================================
-- 数据库迁移脚本 v1.0.1
-- 对应 commit: 0d0097c + d69498f
-- 适用场景：从 v1.0.0 (commit b45ecf9) 升级到最新版本
-- 包含：模型多厂商支持 + 思考内容与工具调用记录持久化
-- 所有操作幂等，可重复执行
-- =========================================-===================

-- 指定 schema（本项目表全部建在 app schema 下，非默认 public）
SET search_path TO app;

-- 1. 为 message 表补充思考内容与工具调用记录列
ALTER TABLE message ADD COLUMN IF NOT EXISTS reasoning_content TEXT;
ALTER TABLE message ADD COLUMN IF NOT EXISTS tool_calls TEXT;

-- 2. 模型提供商预设表
CREATE TABLE IF NOT EXISTS model_provider (
    id BIGSERIAL PRIMARY KEY,
    provider_code VARCHAR(32) NOT NULL UNIQUE,
    display_name VARCHAR(64) NOT NULL,
    provider_type VARCHAR(32) NOT NULL DEFAULT 'openai_compatible',
    default_base_url VARCHAR(256),
    default_model VARCHAR(128),
    builtin BOOLEAN NOT NULL DEFAULT FALSE,
    sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- 3. 用户模型配置表（API Key AES-256-GCM 加密存储）
CREATE TABLE IF NOT EXISTS user_model_config (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES sys_user(id),
    provider_code VARCHAR(32),
    provider_type VARCHAR(32) NOT NULL DEFAULT 'openai_compatible',
    display_name VARCHAR(128) NOT NULL,
    base_url VARCHAR(256) NOT NULL,
    api_key_encrypted TEXT NOT NULL,
    model_name VARCHAR(128) NOT NULL,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    status SMALLINT NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- 4. 存量库布尔列类型修正（SMALLINT → BOOLEAN，幂等可重复执行）
--    实体层用 Boolean（MyBatis-Plus 按 PG boolean 绑定），列类型必须一致；
--    需先 DROP DEFAULT，否则 PG 无法自动把旧默认值 0 转成 boolean
ALTER TABLE model_provider ALTER COLUMN builtin DROP DEFAULT;
ALTER TABLE model_provider ALTER COLUMN builtin TYPE BOOLEAN USING builtin <> 0;
ALTER TABLE model_provider ALTER COLUMN builtin SET DEFAULT FALSE;

ALTER TABLE db_config ALTER COLUMN builtin DROP DEFAULT;
ALTER TABLE db_config ALTER COLUMN builtin TYPE BOOLEAN USING builtin <> 0;
ALTER TABLE db_config ALTER COLUMN builtin SET DEFAULT FALSE;

ALTER TABLE user_model_config ALTER COLUMN is_default DROP DEFAULT;
ALTER TABLE user_model_config ALTER COLUMN is_default TYPE BOOLEAN USING is_default <> 0;
ALTER TABLE user_model_config ALTER COLUMN is_default SET DEFAULT FALSE;