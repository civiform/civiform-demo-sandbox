-- Migration: add sandbox_instances columns the application already depends on
--
-- SandboxRepository.save() inserts eighteen columns and mapRow() reads eighteen, but six of
-- them were never added to init_postgres.sql:
--
--   subdomain, target_group_arn, listener_rule_arn, google_analytics_id,
--   google_analytics_url, deleted_at
--
-- deleted_at was added by 2026-09-database-per-sandbox.sql, so environments that ran that
-- file already have it; the other five exist nowhere. Because the table is created with
-- CREATE TABLE IF NOT EXISTS, a fresh Postgres volume was equally affected -- recreating the
-- volume did not help. The table starts empty, so nothing failed at startup: the INSERT in
-- save() was the first statement to touch the missing columns, and creating any sandbox
-- failed with "column ... does not exist".
--
-- Run once against sandbox_builder:
--
--   psql -h <host> -U <master-user> -d sandbox_builder -f migrations/2026-09-add-missing-sandbox-columns.sql
--
-- Safe to re-run: every statement is guarded by IF NOT EXISTS.

-- 1. Columns.
ALTER TABLE sandbox_instances ADD COLUMN IF NOT EXISTS subdomain            VARCHAR(63);
ALTER TABLE sandbox_instances ADD COLUMN IF NOT EXISTS target_group_arn     VARCHAR(512);
ALTER TABLE sandbox_instances ADD COLUMN IF NOT EXISTS listener_rule_arn    VARCHAR(512);
ALTER TABLE sandbox_instances ADD COLUMN IF NOT EXISTS google_analytics_id  VARCHAR(64);
ALTER TABLE sandbox_instances ADD COLUMN IF NOT EXISTS google_analytics_url VARCHAR(512);

-- Also in 2026-09-database-per-sandbox.sql. Repeated here so this file stands alone for
-- anyone who skipped that migration or is reconciling a partially-migrated database.
ALTER TABLE sandbox_instances ADD COLUMN IF NOT EXISTS deleted_at           TIMESTAMPTZ;

-- 2. Subdomain uniqueness among live sandboxes.
--
-- Two live sandboxes sharing a subdomain would produce conflicting load balancer host-header
-- rules: one would shadow the other, and which one won would depend on listener rule priority
-- rather than on anything a user chose. The index is partial so that a subdomain becomes
-- available again once its sandbox is torn down, and so that Docker sandboxes -- which leave
-- subdomain NULL -- are unaffected.
--
-- If this fails with a uniqueness violation, there are already duplicate live subdomains.
-- Find them before retrying:
--
--   SELECT subdomain, count(*) FROM sandbox_instances
--   WHERE deleted_at IS NULL AND subdomain IS NOT NULL
--   GROUP BY subdomain HAVING count(*) > 1;
CREATE UNIQUE INDEX IF NOT EXISTS sandbox_instances_subdomain_live_idx
  ON sandbox_instances (subdomain)
  WHERE deleted_at IS NULL AND subdomain IS NOT NULL;
