-- This script runs inside the sandbox_builder database (POSTGRES_DB=sandbox_builder).
-- The postgres user and sandbox_builder database are created by the Docker entrypoint
-- before this script runs. Statements below are idempotent.

-- ============================================================
-- Port allocation sequence (atomic, thread-safe)
-- Allocate ports 10000–11000 for CiviForm sandbox containers
-- ============================================================
CREATE SEQUENCE IF NOT EXISTS sandbox_port_seq
  START 10000
  INCREMENT 1
  MINVALUE 10000
  MAXVALUE 11000
  CYCLE;

-- ============================================================
-- sandbox_instances: one row per live or historical sandbox
-- ============================================================
CREATE TABLE IF NOT EXISTS sandbox_instances (
  id                  VARCHAR(64)   PRIMARY KEY,
  city_name           VARCHAR(255)  NOT NULL,
  subdomain           VARCHAR(255),              -- URL slug (e.g. "burlington-vt")
  civiform_version    VARCHAR(64)   NOT NULL DEFAULT 'latest',
  status              VARCHAR(32)   NOT NULL DEFAULT 'PROVISIONING',
  url                 VARCHAR(512)  NOT NULL DEFAULT '',
  admin_email         VARCHAR(255)  NOT NULL DEFAULT '',
  pin                 VARCHAR(6)    NOT NULL,
  container_id        VARCHAR(128),              -- Docker container ID (set after launch)
  host_port           INTEGER       NOT NULL,
  schema_name         VARCHAR(128)  NOT NULL,    -- Per-sandbox Postgres database name
  target_group_arn    VARCHAR(512),              -- ALB target group ARN (Sprint 2 ECS only)
  listener_rule_arn   VARCHAR(512),              -- ALB listener rule ARN (Sprint 2 ECS only)
  google_analytics_id VARCHAR(64),               -- GA measurement ID (e.g. "G-ABC123XYZ")
  google_analytics_url VARCHAR(512),             -- GA property console deep-link URL
  created_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
  expires_at          TIMESTAMPTZ   NOT NULL,
  deleted_at          TIMESTAMPTZ               -- Set on soft-delete (status = DELETED)
);
