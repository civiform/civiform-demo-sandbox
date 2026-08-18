# cf-sandbox-builder

This is the **Instant Sales Sandbox Provisioner** — a tool to deploy and manage on-demand CiviForm
demo instances for Exygy's sales team.

**Cloud platform**: AWS only (ECS Fargate + RDS Postgres). Do not suggest GCP.

**Current sprint**: Sprint 1 MVP (2-week). Goal: sales rep creates sandbox for a named city, gets a
6-digit PIN, prospect enters PIN and gets a live CiviForm instance. Docker socket approach for container
runtime (NOT ECS Fargate yet — that is Sprint 2).

**Key rules and context** are bundled in `_agents/plugins/cf-sandbox-builder/rules/project-context.md`.
Load that file for full architecture decisions, PRD status, open questions, and coding conventions.

**Useful skills**:
- `run-dev`: Start/stop/interact with the local Docker dev environment
- `add-feature`: Step-by-step guide for adding new Play Framework features, including Sprint 1 Docker socket pattern
- `civiform-deploy-reference`: The current manual CiviForm demo deployment process (annotated with what cf-sandbox-builder automates and which sprint owns each step)
- `mvp-sprint`: Full 8-sprint plan from Docker MVP through Tom's complete demo vision. Incorporates all technical review fixes.
