
-- 创建 app schema
CREATE SCHEMA IF NOT EXISTS app;

-- 指定 schema ，不用 public
SET search_path TO app;

CREATE TABLE IF NOT EXISTS sys_user (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(64) NOT NULL UNIQUE,
    password_hash VARCHAR(256) NOT NULL,
    nickname VARCHAR(64),
    role VARCHAR(16) NOT NULL DEFAULT 'user',
    status SMALLINT NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS db_config (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES sys_user(id),
    name VARCHAR(128) NOT NULL,
    db_type VARCHAR(32) NOT NULL DEFAULT 'mysql',
    host VARCHAR(256) NOT NULL,
    port INT NOT NULL DEFAULT 3306,
    db_name VARCHAR(128) NOT NULL,
    username VARCHAR(128) NOT NULL,
    password_encrypted TEXT NOT NULL,
    status SMALLINT NOT NULL DEFAULT 0,
    builtin SMALLINT NOT NULL DEFAULT 0,
    doc_content TEXT,
    doc_generated_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS conversation (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES sys_user(id),
    title VARCHAR(256),
    type VARCHAR(32) NOT NULL,
    db_config_ids TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS message (
    id BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT NOT NULL REFERENCES conversation(id),
    role VARCHAR(16) NOT NULL,
    content TEXT NOT NULL,
    step INT,
    type VARCHAR(32),
    reasoning_content TEXT,
    tool_calls TEXT,
    file_url TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- 存量库升级（幂等，可重复执行）：为 message 表补充思考内容与工具调用记录列
ALTER TABLE message ADD COLUMN IF NOT EXISTS reasoning_content TEXT;
ALTER TABLE message ADD COLUMN IF NOT EXISTS tool_calls TEXT;

CREATE TABLE IF NOT EXISTS uploaded_file (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES sys_user(id),
    original_name VARCHAR(256) NOT NULL,
    oss_key VARCHAR(512) NOT NULL,
    file_size BIGINT,
    content_type VARCHAR(128),
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS sales_contact (
    id BIGSERIAL PRIMARY KEY,
    company_name VARCHAR(256) NOT NULL,
    contact VARCHAR(256) NOT NULL,
    remark TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- 模型提供商预设表
CREATE TABLE IF NOT EXISTS model_provider (
    id BIGSERIAL PRIMARY KEY,
    provider_code VARCHAR(32) NOT NULL UNIQUE,
    display_name VARCHAR(64) NOT NULL,
    provider_type VARCHAR(32) NOT NULL DEFAULT 'openai_compatible',
    default_base_url VARCHAR(256),
    default_model VARCHAR(128),
    builtin SMALLINT NOT NULL DEFAULT 0,
    sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- 用户模型配置表（API Key AES-256-GCM 加密存储）
CREATE TABLE IF NOT EXISTS user_model_config (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES sys_user(id),
    provider_code VARCHAR(32),
    provider_type VARCHAR(32) NOT NULL DEFAULT 'openai_compatible',
    display_name VARCHAR(128) NOT NULL,
    base_url VARCHAR(256) NOT NULL,
    api_key_encrypted TEXT NOT NULL,
    model_name VARCHAR(128) NOT NULL,
    is_default SMALLINT NOT NULL DEFAULT 0,
    status SMALLINT NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);
