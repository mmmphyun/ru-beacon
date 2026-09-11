-- =============================================================================
-- Ru-Beacon Database Schema (Flyway V2: Tenant Admin Role Delegation)
-- Target: PostgreSQL 16
-- =============================================================================

ALTER TABLE tenants ADD COLUMN IF NOT EXISTS admin_role_id VARCHAR(32);