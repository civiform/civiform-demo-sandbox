-- ============================================================
-- Terraform runtime support (Sprint 2)
--
-- Apply to any existing sandbox_builder database:
--   psql -U postgres -d sandbox_builder -f migrations/2026-09-terraform-runtime.sql
--
-- Idempotent — safe to run more than once. The same statements are folded into
-- init_postgres.sql so that fresh volumes come up identical to migrated ones.
--
-- Adds the ALB listener rule priority allocator used by TerraformSandboxService.
-- ============================================================

-- Every sandbox attaches a host-header rule to the shared HTTPS listener, and
-- ALB requires rule priorities to be unique per listener. Allocating from a
-- sequence makes that atomic: EcsFargateSandboxService used to describe the
-- existing rules and pick the next free number, which races whenever two
-- sandboxes are created at the same time — and the loser fails mid-provision,
-- after its ECS service already exists.
--
-- Deliberately NO CYCLE, unlike sandbox_port_seq. Ports are a small pool that
-- must be recycled; priorities are not. Wrapping around would hand out a
-- priority that a live rule already holds, and the resulting apply failure
-- would be both confusing and intermittent. The range holds 49,000 sandboxes,
-- so exhausting it would mean something is badly wrong and an outright error is
-- the correct outcome.
CREATE SEQUENCE IF NOT EXISTS sandbox_listener_priority_seq
  START 1000
  INCREMENT 1
  MINVALUE 1000
  MAXVALUE 50000
  NO CYCLE;

-- Recorded so teardown and debugging can find the rule without calling AWS, and
-- so an audit can spot two rows claiming the same priority. Nullable: Docker
-- sandboxes have no load balancer.
ALTER TABLE sandbox_instances ADD COLUMN IF NOT EXISTS listener_priority INTEGER;

-- ── Verification ─────────────────────────────────────────────
-- Expect one row, 20 columns:
--   SELECT count(*) FROM information_schema.columns
--    WHERE table_name = 'sandbox_instances';
--
-- Expect no rows — two live sandboxes must never share a priority:
--   SELECT listener_priority, count(*) FROM sandbox_instances
--    WHERE deleted_at IS NULL AND listener_priority IS NOT NULL
--    GROUP BY listener_priority HAVING count(*) > 1;
