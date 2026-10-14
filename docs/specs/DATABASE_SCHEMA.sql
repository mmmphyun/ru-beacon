-- =============================================================================
-- Ru-Beacon Database Schema (Flyway V1 Baseline)
-- Target: PostgreSQL 16
-- Conventions: Snake_case, UUID v7 or TEXT prefixed IDs, UTC timestamps
-- =============================================================================

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- -----------------------------------------------------------------------------
-- 1. Tenant & Network Topology
-- -----------------------------------------------------------------------------

CREATE TABLE tenants (
    id VARCHAR(64) PRIMARY KEY, -- e.g. "tenant_01J..."
    name VARCHAR(100) NOT NULL,
    discord_guild_id VARCHAR(32) NOT NULL UNIQUE,
    timezone VARCHAR(50) NOT NULL DEFAULT 'Asia/Seoul',
    max_account_links_per_user INT NOT NULL DEFAULT 2,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE minecraft_networks (
    id VARCHAR(64) PRIMARY KEY, -- e.g. "net_01J..."
    tenant_id VARCHAR(64) NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_minecraft_networks_tenant_id ON minecraft_networks(tenant_id);

CREATE TABLE minecraft_instances (
    id VARCHAR(64) PRIMARY KEY, -- e.g. "inst_backend_01"
    network_id VARCHAR(64) NOT NULL REFERENCES minecraft_networks(id) ON DELETE CASCADE,
    tenant_id VARCHAR(64) NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    instance_type VARCHAR(20) NOT NULL CHECK (instance_type IN ('PROXY', 'BACKEND')),
    name VARCHAR(100) NOT NULL,
    token_hash VARCHAR(128) NOT NULL, -- SHA-256 hash of auth token
    status VARCHAR(20) NOT NULL DEFAULT 'OFFLINE' CHECK (status IN ('ONLINE', 'OFFLINE', 'STALE')),
    last_heartbeat_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_minecraft_instances_network_id ON minecraft_instances(network_id);
CREATE INDEX idx_minecraft_instances_tenant_id ON minecraft_instances(tenant_id);

-- -----------------------------------------------------------------------------
-- 2. Account Linking & 2FA Security
-- -----------------------------------------------------------------------------

CREATE TABLE account_links (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    discord_user_id VARCHAR(32) NOT NULL,
    minecraft_uuid UUID NOT NULL,
    minecraft_username VARCHAR(32) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'ACTIVE')),
    verification_code VARCHAR(16),
    code_expires_at TIMESTAMPTZ,
    failed_attempts INT NOT NULL DEFAULT 0,
    verified_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_tenant_minecraft_uuid UNIQUE (tenant_id, minecraft_uuid)
);

CREATE INDEX idx_account_links_tenant_discord ON account_links(tenant_id, discord_user_id);
CREATE INDEX idx_account_links_tenant_verify_code ON account_links(tenant_id, verification_code) WHERE status = 'PENDING';

CREATE TABLE admin_2fa_policies (
    tenant_id VARCHAR(64) PRIMARY KEY REFERENCES tenants(id) ON DELETE CASCADE,
    policy_mode VARCHAR(20) NOT NULL DEFAULT 'MONITOR' CHECK (policy_mode IN ('DISABLED', 'MONITOR', 'ENFORCE')),
    auth_channel_id VARCHAR(32),
    timeout_seconds INT NOT NULL DEFAULT 60,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE admin_2fa_requests (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    discord_user_id VARCHAR(32) NOT NULL,
    minecraft_uuid UUID NOT NULL,
    source_instance_id VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED', 'DENIED', 'TIMED_OUT')),
    discord_thread_id VARCHAR(32),
    discord_message_id VARCHAR(32),
    expires_at TIMESTAMPTZ NOT NULL,
    responded_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_admin_2fa_requests_tenant_status ON admin_2fa_requests(tenant_id, status);

-- -----------------------------------------------------------------------------
-- 3. Workflows (Hybrid Model: Metadata Columns + Versioned JSONB Definition)
-- -----------------------------------------------------------------------------

CREATE TABLE workflows (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    description TEXT,
    active_version INT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_workflows_tenant_id ON workflows(tenant_id);

CREATE TABLE workflow_versions (
    id VARCHAR(64) PRIMARY KEY,
    workflow_id VARCHAR(64) NOT NULL REFERENCES workflows(id) ON DELETE CASCADE,
    tenant_id VARCHAR(64) NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    version INT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'TESTING', 'ACTIVE', 'INACTIVE', 'ARCHIVED')),
    definition JSONB NOT NULL, -- Full DAG schema: { nodes: [], edges: [], variables: {} }
    published_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_workflow_version UNIQUE (workflow_id, version)
);

CREATE INDEX idx_workflow_versions_active ON workflow_versions(tenant_id, status) WHERE status = 'ACTIVE';
CREATE INDEX idx_workflow_versions_definition_gin ON workflow_versions USING GIN (definition);

-- -----------------------------------------------------------------------------
-- 4. Attendance & Concurrency Quota
-- -----------------------------------------------------------------------------

CREATE TABLE attendance_quotas (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    reward_date DATE NOT NULL,
    total_limit INT NOT NULL DEFAULT 100,
    reserved_count INT NOT NULL DEFAULT 0,
    committed_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_attendance_tenant_date UNIQUE (tenant_id, reward_date),
    CONSTRAINT chk_attendance_reserved_limit CHECK (reserved_count <= total_limit),
    CONSTRAINT chk_attendance_committed_reserved CHECK (committed_count <= reserved_count)
);

CREATE TABLE reward_reservations (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    quota_id VARCHAR(64) NOT NULL REFERENCES attendance_quotas(id) ON DELETE CASCADE,
    reward_date DATE NOT NULL,
    player_uuid UUID NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'RESERVED' CHECK (status IN ('RESERVED', 'COMMITTED', 'RELEASED')),
    idempotency_key VARCHAR(128) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_reward_reservation_daily UNIQUE (tenant_id, reward_date, player_uuid)
);

CREATE INDEX idx_reward_reservations_status ON reward_reservations(status, expires_at) WHERE status = 'RESERVED';

-- -----------------------------------------------------------------------------
-- 5. Audit & Observability Logs
-- -----------------------------------------------------------------------------

CREATE TABLE audit_logs (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    correlation_id VARCHAR(64) NOT NULL,
    actor_type VARCHAR(32) NOT NULL, -- e.g. "DISCORD_USER", "SYSTEM", "MINECRAFT_PLAYER"
    actor_id VARCHAR(64) NOT NULL,
    action VARCHAR(64) NOT NULL,     -- e.g. "WORKFLOW_EXECUTE", "COMMAND_RUN", "2FA_APPROVE"
    target_type VARCHAR(32),
    target_id VARCHAR(64),
    status VARCHAR(20) NOT NULL CHECK (status IN ('SUCCESS', 'FAILURE', 'PARTIAL_FAILURE')),
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    ip_address INET,                -- Recorded ONLY for sensitive admin/security actions
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_audit_logs_tenant_created ON audit_logs(tenant_id, created_at DESC);
CREATE INDEX idx_audit_logs_correlation_id ON audit_logs(correlation_id);
CREATE INDEX idx_audit_logs_details_gin ON audit_logs USING GIN (details);
