-- This script runs inside the sandbox_builder database (POSTGRES_DB=sandbox_builder).
-- The postgres user and sandbox_builder database are created by the Docker entrypoint
-- before this script runs. Statements below are idempotent.

-- ============================================================
-- Harden the metadata database: revoke PUBLIC connect
-- The sandbox_builder database holds PINs and admin emails.
-- Sandbox user credentials must not be able to connect to it.
-- ============================================================
REVOKE CONNECT, TEMPORARY ON DATABASE sandbox_builder FROM PUBLIC;

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
-- ALB listener rule priority sequence (atomic, thread-safe)
-- Used by the Terraform runtime; Docker sandboxes have no load balancer.
-- ============================================================
-- NO CYCLE is deliberate, and differs from sandbox_port_seq above. Ports are a
-- small pool that has to be recycled. Priorities are not: wrapping would hand
-- out a number a live ALB rule already holds, and the apply would fail
-- intermittently and confusingly. 49,000 values is far beyond any plausible
-- sandbox count, so exhaustion should be a loud error rather than a collision.
CREATE SEQUENCE IF NOT EXISTS sandbox_listener_priority_seq
  START 1000
  INCREMENT 2
  MINVALUE 1000
  MAXVALUE 50000
  NO CYCLE;

-- ============================================================
-- sandbox_instances: one row per live or historical sandbox
-- ============================================================
-- Column order follows SandboxRepository.mapRow so the two stay easy to diff.
CREATE TABLE IF NOT EXISTS sandbox_instances (
  id              VARCHAR(64)   PRIMARY KEY,
  city_name       VARCHAR(255)  NOT NULL,
  -- DNS label for the sandbox hostname. Nullable: the Docker runtime addresses
  -- sandboxes by host port and never assigns one. Max 63 chars per RFC 1035.
  subdomain       VARCHAR(63),
  civiform_version VARCHAR(64)  NOT NULL DEFAULT 'latest',
  status          VARCHAR(32)   NOT NULL DEFAULT 'PROVISIONING',
  url             VARCHAR(512)  NOT NULL DEFAULT '',
  admin_email     VARCHAR(255)  NOT NULL DEFAULT '',
  pin             VARCHAR(6)    NOT NULL,
  access_token    VARCHAR(64)   NOT NULL DEFAULT '',  -- Per-sandbox cookie secret
  container_id    VARCHAR(128),          -- Docker container ID (set after launch)
  host_port       INTEGER       NOT NULL,
  database_name   VARCHAR(128)  NOT NULL, -- Per-sandbox Postgres database name
  -- AWS resource ARNs, set by the Fargate/Terraform runtime only.
  target_group_arn  VARCHAR(512),
  listener_rule_arn VARCHAR(512),
  -- Priority of this sandbox's ALB listener rule, allocated from
  -- sandbox_listener_priority_seq. Recorded so teardown and debugging can find
  -- the rule without calling AWS. Null for Docker sandboxes.
  listener_priority INTEGER,
  -- Optional per-sandbox Google Analytics wiring, surfaced in the admin UI.
  google_analytics_id  VARCHAR(64),
  google_analytics_url VARCHAR(512),
  created_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
  expires_at      TIMESTAMPTZ   NOT NULL,
  -- Set when a sandbox is torn down. The row is kept as a scrubbed tombstone
  -- rather than deleted; see SandboxRepository.softDelete.
  deleted_at      TIMESTAMPTZ
);

-- Two live sandboxes sharing a subdomain would produce conflicting load balancer
-- host-header rules, so the hostname must be unique among them. Scoped to live
-- rows so a subdomain can be reused once its sandbox is torn down, and ignoring
-- NULLs so Docker sandboxes are unaffected.
CREATE UNIQUE INDEX IF NOT EXISTS sandbox_instances_subdomain_live_idx
  ON sandbox_instances (subdomain)
  WHERE deleted_at IS NULL AND subdomain IS NOT NULL;
