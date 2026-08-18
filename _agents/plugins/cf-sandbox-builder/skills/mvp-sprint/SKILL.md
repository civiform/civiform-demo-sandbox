---
name: mvp-sprint
description: >-
  Full 8-sprint plan for cf-sandbox-builder, from Docker MVP through Tom's complete
  demo vision (Burlington, VT sandbox with pre-loaded programs, persistent banner,
  role switcher, predicate rules, ROI benchmarks, JSON export). Incorporates all
  technical review fixes. Use this skill when planning, estimating, or sequencing work.
---

# cf-sandbox-builder — Full Sprint Plan

> Goal: Reach Tom's MVP demo vision (Burlington, VT sandbox with pre-loaded programs,
> persistent banner, role switcher, predicate rules, ROI benchmarks, JSON export).
> Team: 2 SWEs (1 FE, 1 BE) | 2-week sprints | ~60 hrs/sprint per SWE
> Technical review fixes incorporated (Aug 2026).

---

## At a Glance

| Sprint | Theme | End State |
|---|---|---|
| S1 | Docker MVP: Core Loop | Sales rep creates sandbox → live CiviForm via PIN |
| S2 | AWS ECS Fargate + Networking | Same loop, cloud-hosted, real subdomain + SSL |
| S3 | Seeding Engine + Real Programs | Sandbox pre-loaded with Burlington civic programs |
| S4 | Demo Shell: Banner + Role Switcher | Tom's full in-sandbox experience ✅ |
| S5 | Lifecycle: Expiry + Teardown | 30-day sandbox management |
| S6 | Creator Portal Polish + Graduation | Auth, template picker, JSON export |
| S7 | Discovery Engine + PDF Scaffolder | AI-assisted program discovery for any city |
| S8 | Testing + Launch Readiness | Production-hardened, E2E tested |

---

## Sprint 1 — Docker MVP: Core Loop

**Duration**: 2 weeks
**Goal**: Sales rep fills form → CiviForm container launches → accessible via 6-digit PIN.
Stock CiviForm with default data. Not pretty. Proves the loop.

### BLOCKER: Per-Sandbox Database Strategy (Resolve Before Day 1)

CiviForm requires a live Postgres connection to start. Decision for Sprint 1:
Use **Option A: one schema per sandbox on the shared builder Postgres**.

At sandbox creation, run before `docker run`:
```sql
CREATE USER sandbox_<id> WITH PASSWORD '<generated>';
CREATE SCHEMA sandbox_<id> AUTHORIZATION sandbox_<id>;
```

Pass to `docker run civiform/civiform:latest`:
```
DATABASE_URL=jdbc:postgresql://host.docker.internal:5432/sandbox_builder?currentSchema=sandbox_<id>
DATABASE_USERNAME=sandbox_<id>
DATABASE_PASSWORD=<generated>
APPLICATION_SECRET=<32-char random>
STAGING_DISABLE_DEMO_MODE_LOGINS=false
```

### Backend Tasks

| # | Task | Hrs | Notes |
|---|---|---|---|
| BE-1 | `sandbox_instances` DB schema | 4 | Use `TIMESTAMPTZ`. Port seq: `CREATE SEQUENCE sandbox_port_seq START 10000 MAXVALUE 11000` |
| BE-2 | Add docker-java 3.4.x to `build.sbt` + verify compile | 2 | Correct API: `ApacheDockerHttpClient.Builder` → `DockerClientImpl.getInstance(config, httpClient)` |
| BE-3 | `SandboxRepository` (JDBC DB layer) | 6 | CRUD against `sandbox_instances`. Injected into service. |
| BE-4 | `DockerSandboxService.createSandbox()` | 18 | Creates Postgres schema, launches container with env vars, returns PROVISIONING status immediately. Port via `SELECT nextval('sandbox_port_seq')` |
| BE-5 | `DockerSandboxService.getSandboxStatus()` | 4 | Polls Docker + CiviForm `/health` + 15s buffer post-health for DB migration completion |
| BE-6 | Add `validatePin` to `SandboxService` interface | 1 | Missing from interface: `CompletionStage<Optional<SandboxInstance>> validatePin(String sandboxId, String pin)` |
| BE-7 | 6-digit PIN generation + DB storage | 2 | Generate at request time (before async provisioning). Store in `pin` column. |
| BE-8 | PIN validation endpoint `POST /sandboxes/:id/access` | 3 | Validate PIN → redirect to CiviForm URL. `badRequest` on invalid. |
| BE-9 | HTMX status polling endpoint | 2 | `GET /sandboxes/:id/status` → partial HTML fragment. Stops polling when RUNNING. |
| BE-10 | Wire `SandboxController.create()` → detail page redirect | 2 | Redirect to `/sandboxes/:id` (not home). PIN visible immediately. |
| BE-11 | Rebind Guice: `SandboxService` → `DockerSandboxService` | 1 | In `MainModule.java` |

**BE Total: ~45 hrs**

### Frontend Tasks

| # | Task | Hrs | Notes |
|---|---|---|---|
| FE-1 | "Create Sandbox" form page | 6 | City name + submit. USWDS form. `POST /sandboxes` |
| FE-2 | Provisioning progress view | 6 | HTMX polling every 3s → animated status badge |
| FE-3 | Sandbox detail page | 6 | CiviForm URL (clickable), 6-digit PIN (large), copy button |
| FE-4 | PIN entry gate `/access/:id` | 4 | PIN input for prospects → redirect to CiviForm URL |
| FE-5 | Basic sandbox list dashboard | 4 | Table: city, status, created at, link to detail |

**FE Total: ~26 hrs**

### Definition of Done
1. Sales rep: form → "Burlington, VT" → Create → progress spinner → "Running"
2. Detail page: URL + PIN `482917` + copy button
3. Incognito: `/access/<id>` → PIN → live CiviForm loads with FAKE_IDP login

---

## Sprint 2 — AWS ECS Fargate + Real Networking

**Duration**: 2 weeks
**Goal**: Replace Docker socket with ECS Fargate. Real `burlington-vt.sandbox.civiform.org` URL with TLS.

Note: `SandboxService` interface is identical — only `DockerSandboxService` is replaced by `EcsFargateSandboxService`. No controller, view, or PIN logic changes.

### Backend Tasks

| # | Task | Hrs | Notes |
|---|---|---|---|
| BE-12 | Terraform: VPC, subnets, security groups, ALB | 10 | Base networking. ALB routes by subdomain. |
| BE-13 | Wildcard ACM cert `*.sandbox.civiform.org` | 3 | Pre-provisioned once. Shared across all sandboxes. |
| BE-14 | Cloudflare CNAME per sandbox via Terraform | 4 | `burlington-vt.sandbox.civiform.org → ALB` |
| BE-15 | ECS Task Definition for CiviForm image | 8 | Env vars, task role, Fargate sizing |
| BE-16 | `EcsFargateSandboxService` | 14 | Replaces `DockerSandboxService`. Same interface. |
| BE-17 | RDS Postgres: shared instance + per-sandbox schema | 8 | Same schema-per-sandbox pattern as S1 |
| BE-18 | IAM: `CiviformControlPlaneTaskRole` + policies | 4 | No hardcoded keys. Task role only. |
| BE-19 | Inject city name env vars at launch | 2 | `WHITELABEL_CIVIC_ENTITY_SHORT_NAME`, `WHITELABEL_CIVIC_ENTITY_FULL_NAME` |
| BE-20 | Mailpit mock email container per sandbox | 4 | Captures outgoing emails for evaluators |
| BE-21 | Scheduled provisioning: `scheduledStartTime` | 6 | Sales rep pre-provisions before a meeting |

**BE Total: ~63 hrs** ⚠️ BE-20 slides to S3 if tight

### Frontend Tasks

| # | Task | Hrs | Notes |
|---|---|---|---|
| FE-6 | Show real subdomain URL on detail page | 3 | `https://burlington-vt.sandbox.civiform.org` |
| FE-7 | Scheduled start datetime picker on Create form | 6 | |

**FE Total: ~9 hrs**

### Definition of Done
1. Sandbox gets `https://burlington-vt.sandbox.civiform.org` with valid TLS
2. CiviForm header shows "Burlington"
3. PIN gate works on real HTTPS URL

---

## Sprint 3 — Seeding Engine + Real Civic Programs

**Duration**: 2 weeks
**Goal**: Every sandbox pre-loaded with real Burlington, VT civic programs.

> ⚠️ BLOCKER: Program JSON templates must be authored (exported from live CiviForm)
> before this sprint starts. Who authors them?

### Backend Tasks

| # | Task | Hrs | Notes |
|---|---|---|---|
| BE-22 | Program template library: 5+ exported program JSONs | 12 | Housing, utilities, workforce, childcare, senior tax relief |
| BE-23 | `SeedingEngine.seedSandbox(sandboxId, templateId)` | 12 | Calls `ProgramMigrationService.saveImportedProgram()` after container RUNNING + 15s |
| BE-24 | Cross-program data pre-fill (4 shared fields) | 8 | Name, DOB, address, household size |
| BE-25 | Predicate rule: Show Block 3 IF Income < $50k | 8 | One working predicate rule per sandbox |
| BE-26 | Synthetic applicant data seeder (3–5 mock profiles) | 8 | Pre-populated in Admin view |
| BE-27 | Program template registry `GET /api/v1/templates` | 4 | List with metadata |

**BE Total: ~52 hrs**

### Frontend Tasks

| # | Task | Hrs | Notes |
|---|---|---|---|
| FE-8 | Program template picker on Create form | 10 | Card grid: "Standard Mid-Size City", "Housing Focus", etc. |
| FE-9 | Seeding progress phase | 4 | Second HTMX phase after container: "Seeding programs..." |

**FE Total: ~14 hrs**

### Definition of Done
1. Pick "Burlington Standard" template → programs appear automatically
2. 3+ programs visible to Resident
3. Cross-program prefill works for 4 fields
4. Income predicate rule shows/hides correctly

---

## Sprint 4 — Demo Shell: Persistent Banner + Role Switcher

**Duration**: 2 weeks
**Goal**: In-sandbox experience matches Tom's prototype. All 6 key demo features live.

> ⚠️ Requires changes inside the CiviForm main repo. Must be PR'd and merged,
> then sandbox image rebuilt.

### Backend Tasks (CiviForm-side)

| # | Task | Hrs | Notes |
|---|---|---|---|
| BE-28 | Persistent demo banner in `BaseHtmlLayout.java` | 8 | Not dismissible. City name + days remaining from env vars. |
| BE-29 | 1-click Role Switcher | 12 | `[👤 Resident] [🛠️ CiviForm Admin] [⚙️ Program Admin]` switches FAKE_IDP persona |
| BE-30 | ROI benchmarks panel | 6 | "Legacy: 47 min → CiviForm: 12 min". Styled content. |
| BE-31 | "Reset to Baseline" endpoint | 6 | Re-seeds programs via `SeedingEngine.seedSandbox()` |
| BE-32 | "Export Program Schema (JSON)" | 8 | Calls `AdminExportController`. PII-excluded. ZIP download. |

**BE Total: ~40 hrs**

### Frontend Tasks

| # | Task | Hrs | Notes |
|---|---|---|---|
| FE-10 | "30 Days Remaining" countdown on detail page | 3 | Calculated from `expiresAt` |
| FE-11 | "Contact CiviForm Sales" link | 1 | |

**FE Total: ~4 hrs**

### ✅ Definition of Done — Tom's MVP Achieved

All 6 key demo features verified:
- [x] Cross-program data pre-fill (4 fields) — Sprint 3
- [x] Predicate rules logic (Show Block 3 IF Income < $50k) — Sprint 3
- [x] Persistent demo banner (not dismissible) — this sprint
- [x] 1-click Role Switcher [Resident | CiviForm Admin | Program Admin] — this sprint
- [x] ROI benchmarks (75% completion time reduction) — this sprint
- [x] Export Program Schema (JSON) — this sprint

---

## Sprint 5 — Lifecycle: Expiry + Teardown + Usage Tracking

**Duration**: 2 weeks

### Backend Tasks

| # | Task | Hrs | Notes |
|---|---|---|---|
| BE-33 | EventBridge cron at 02:00 UTC | 6 | Evaluates `expiresAt` across all `sandbox_instances` |
| BE-34 | Lambda teardown: drop schema → terminate ECS task → delete S3 | 12 | DLQ: 3 retries. Slack alert on 3rd failure. |
| BE-35 | PgBouncer: max 15 connections per sandbox schema | 4 | Prevents RDS pool exhaustion |
| BE-36 | Usage tracking: last-login + page view count | 8 | CloudWatch metrics |
| BE-37 | Cost guardrail: max 10 concurrent sandboxes | 4 | Reject create if at cap. CloudWatch alert. |
| BE-38 | Mock auth pre-config: `FakeAdminClient` + guest login | 8 | Pre-configured per sandbox image |
| BE-39 | PIN gate BE hardening: rate limiting + lockout | 4 | |

**BE Total: ~46 hrs**

### Frontend Tasks

| # | Task | Hrs | Notes |
|---|---|---|---|
| FE-12 | "Manage Sandboxes" table | 8 | Sortable: status, city, created, expires, usage |
| FE-13 | Expiration warning banners in portal | 4 | "5 days remaining" |
| FE-14 | Manual teardown button + confirmation modal | 3 | |
| FE-15 | Leads admin view | 5 | Read-only table of inbound pilot requests |

**FE Total: ~20 hrs**

---

## Sprint 6 — Creator Portal Auth + Graduation + Lead/CRM

**Duration**: 2 weeks

> ❓ Auth decision must be made before this sprint: IP allowlist / shared password / Google OAuth

### Backend Tasks

| # | Task | Hrs | Notes |
|---|---|---|---|
| BE-40 | Creator portal auth | 8–16 | Scope depends on auth decision |
| BE-41 | Lead/CRM: `POST /api/v1/leads/pilot-request` | 8 | Email + Slack on new lead |
| BE-42 | Leads DB table + read API `GET /api/v1/leads` | 4 | |
| BE-43 | Graduation flow: export ZIP via `AdminExportController` | 8 | |
| BE-44 | PDF Scaffolder: wire `pdf-to-civiform-ocr` Flask API | 10 | |

**BE Total: ~38–46 hrs**

### Frontend Tasks

| # | Task | Hrs | Notes |
|---|---|---|---|
| FE-16 | Creator portal login page | 6 | |
| FE-17 | Pilot request form (city, contact, tier) | 6 | |
| FE-18 | PDF Scaffolder upload panel + OCR results preview | 10 | |
| FE-19 | WCAG / keyboard navigation audit | 12 | |

**FE Total: ~34 hrs**

---

## Sprint 7 — Discovery Engine + Polish

**Duration**: 2 weeks
> ⚠️ Discovery Engine needs product sign-off. If not approved, use sprint for mobile polish + error states.

### Backend Tasks

| # | Task | Hrs | Notes |
|---|---|---|---|
| BE-45 | Discovery Engine: wire `pdf-to-civiform-ocr` crawler | 8 | Saves ~17 hrs vs building from scratch |
| BE-46 | Gemini API: extract programs from crawled .gov content | 14 | Handle rate limiting, robots.txt |
| BE-47 | Schema mapper: Gemini output → CiviForm JSON | 12 | |
| BE-48 | Network isolation security audit | 10 | No cross-sandbox ECS traffic |
| BE-49 | SMTP pre-config (Mailpit wiring) | 4 | |

**BE Total: ~48 hrs**

### Frontend Tasks

| # | Task | Hrs | Notes |
|---|---|---|---|
| FE-20 | Discovery Engine: city input + crawl progress | 12 | |
| FE-21 | Extracted program selection before seeding | 8 | |
| FE-22 | Mobile-responsive polish | 8 | |
| FE-23 | Error states: provisioning failure, timeout, invalid PIN | 10 | |
| FE-24 | Visual regression screenshots (Playwright) | 6 | |

**FE Total: ~44 hrs**

---

## Sprint 8 — Testing + Launch Readiness

**Duration**: 2 weeks

### Tasks

| # | Task | Hrs | Notes |
|---|---|---|---|
| BE-50 | Integration tests: provision → seed → verify → teardown | 16 | Against real AWS |
| BE-51 | Load test: 3 concurrent sandboxes, each < 30 min | 10 | |
| BE-52 | Security hardening: dependency scan, secrets audit | 8 | |
| FE-25 | E2E: full sales rep → prospect CUJ (Playwright) | 16 | |
| FE-26 | Buffer / polish / bug fixes | 10 | |

**Total: ~60 hrs**

---

## Summary

| Sprint | Theme | BE hrs | FE hrs | Total | Milestone |
|---|---|---|---|---|---|
| S1 | Docker MVP | 45 | 26 | **71** | Live CiviForm via PIN |
| S2 | AWS Fargate | 63 | 9 | **72** | Real subdomain + SSL |
| S3 | Seeding | 52 | 14 | **66** | Real civic programs |
| **S4** | **Demo Shell** | **40** | **4** | **44** | ✅ **Tom's full MVP** |
| S5 | Lifecycle | 46 | 20 | **66** | 30-day teardown |
| S6 | Auth + Grad | 42 | 34 | **76** | Secured portal |
| S7 | Discovery | 48 | 44 | **92** | AI program discovery |
| S8 | Testing | 34 | 26 | **60** | Launch-ready |
| | **Totals** | **370** | **177** | **547** | |
