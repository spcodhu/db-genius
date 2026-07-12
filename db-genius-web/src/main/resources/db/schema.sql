
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
    file_url TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS uploaded_file (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES sys_user(id),
    original_name VARCHAR(256) NOT NULL,
    stored_path TEXT NOT NULL,
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
