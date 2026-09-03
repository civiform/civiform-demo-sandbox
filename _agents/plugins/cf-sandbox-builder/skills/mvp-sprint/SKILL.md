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
**Goal**: Replace Docker socket with ECS Fargate. Real `burlington-vt.sandbox.civiform.dev` URL with TLS.

Note: `SandboxService` interface is identical — only `DockerSandboxService` is replaced by `EcsFargateSandboxService`. No controller, view, or PIN logic changes.

> ✅ **Decisions locked (Rocky, Sep 2026):**
> - **Domain**: Use `civiform.dev` (CiviForm already owns this). Sandbox pattern: `burlington-vt.sandbox.civiform.dev`
> - **SSL**: Request wildcard ACM cert `*.sandbox.civiform.dev` in AWS — pre-provisioned once, shared across all sandboxes
> - **Deploy process**: Follow Rocky’s existing CiviForm deploy process (`civiform-staging-deploy`) — do not reinvent Terraform from scratch
> - **Cloudflare**: NOT needed — `civiform.dev` handles DNS (this question is moot)

### Backend Tasks

| # | Task | Hrs | Notes |
|---|---|---|---|
| BE-12 | Terraform: VPC, subnets, security groups, ALB | 10 | Reuse/fork Rocky’s `civiform-staging-deploy` Terraform. ALB routes by subdomain. |
| BE-13 | Wildcard ACM cert `*.sandbox.civiform.dev` | 3 | Requested in AWS once. Shared across all sandboxes. |
| BE-14 | Route 53 CNAME per sandbox | 4 | `burlington-vt.sandbox.civiform.dev → ALB`. Coordinate with Rocky for DNS access. |
| BE-15 | ECS Task Definition for CiviForm image | 8 | Follow Rocky’s existing task definition pattern. Env vars, task role, Fargate sizing. |
| BE-16 | `EcsFargateSandboxService` | 14 | Replaces `DockerSandboxService`. Same interface. |
| BE-17 | RDS Postgres: shared instance + per-sandbox schema | 8 | Same schema-per-sandbox pattern as S1. |
| BE-18 | IAM: `CiviformSandboxTaskRole` + policies | 4 | `secretsmanager:GetSecretValue` on `civiform-sandbox_*` only. No OIDC/ADFS/ESRI needed (using FAKE_IDP). Follow Rocky's existing role pattern. |
| BE-18a | Generate + store 3 Secrets Manager secrets per sandbox | 3 | At creation: `civiform-sandbox_{id}_postgres_username`, `_postgres_password`, `_app_secret_key`. Done in `EcsFargateSandboxService.createSandbox()`. |
| BE-19 | Inject city name env vars at launch | 2 | `WHITELABEL_CIVIC_ENTITY_SHORT_NAME`, `WHITELABEL_CIVIC_ENTITY_FULL_NAME` |
| BE-20 | Mailpit mock email container per sandbox | 4 | Slides to S3 if tight. |
| BE-21 | Scheduled provisioning: `scheduledStartTime` | 6 | Sales rep pre-provisions before a meeting. Slides to S3 if tight. |

**BE Total: ~63 hrs** ⚠️ BE-20 and BE-21 slide to S3 if tight

### Frontend Tasks

| # | Task | Hrs | Notes |
|---|---|---|---|
| FE-6 | Show real subdomain URL on detail page | 3 | `https://burlington-vt.sandbox.civiform.dev` |
| FE-7 | Scheduled start datetime picker on Create form | 6 | |

**FE Total: ~9 hrs**

### Definition of Done
1. Sandbox gets `https://burlington-vt.sandbox.civiform.dev` with valid TLS
2. CiviForm header shows "Burlington"
3. PIN gate works on real HTTPS URL

### Still Open (coordinate with Rocky before sprint starts)
- [ ] Who grants Route 53 / DNS access for `sandbox.civiform.dev` subdomain delegation?
- [ ] Which specific IAM policies does the ECS task role need? (check Rocky’s civiform-staging-deploy IAM docs)
- [ ] Single AWS account or separate sandbox account? (TDD says single-account for MVP)

---

## Sprint 3 — Seeding Engine + Two Programs

**Duration**: 2 weeks
**Goal**: Every sandbox pre-loaded with exactly 2 programs matching the Zipline prototype,
branded with the city name the sales rep entered at creation time.

### Seeding Design (decided Sep 2026)

**Scope locked to what the prototype shows.** Two programs, same every sandbox.
City name is dynamic — whatever the sales rep typed (e.g. "Burlington, VT").

> ✅ Decisions locked:
> - **2 programs only** — Comprehensive Sample + Minimal Sample (matching Zipline `index.html`)
> - **City name**: dynamically from `cityName` field entered at creation → injected as
>   `WHITELABEL_CIVIC_ENTITY_SHORT_NAME` + `WHITELABEL_CIVIC_ENTITY_FULL_NAME` env vars
> - No city-specific Layer 2 JSONs (deferred to Sprint 7 Discovery Engine if needed)
> - No complex enumerators, predicates, or cross-program prefill in this sprint
>   (can add back in S4 if Tom wants them for the demo shell)
> - No synthetic applicant profiles

### The Two Programs (authored once, ship with repo)

#### Program 1: "Comprehensive Sample Program"
_"A comprehensive multi-part assistance program providing support for healthcare,
food assistance, and childcare subsidies."_
- Public Assistance category
- Multiple question types to demonstrate breadth
- Store as `/server/conf/seed-data/comprehensive-sample.json`

#### Program 2: "Minimal Sample Program"
_"A streamlined quick-application program designed for emergency utility relief
and transit voucher subsidies."_
- Community Services category
- Short application — demonstrates fast path
- Store as `/server/conf/seed-data/minimal-sample.json`

**Authorship**: BE team creates these as CiviForm `ProgramDefinition` JSON exports
using a running CiviForm dev instance. One-time work, never changes sandbox to sandbox.

### Backend Tasks

| # | Task | Hrs | Notes |
|---|---|---|---|
| BE-22 | Author 2 program JSONs (Comprehensive + Minimal) | 8 | Export from running CiviForm dev instance. Store in `/server/conf/seed-data/`. |
| BE-23 | `SeedingEngine.seedSandbox(sandboxId)` | 10 | Loads both programs via `ProgramMigrationService.saveImportedProgram()`. Runs after container RUNNING. |
| BE-24 | City name injection at container launch | 4 | Pass `WHITELABEL_CIVIC_ENTITY_SHORT_NAME` + `WHITELABEL_CIVIC_ENTITY_FULL_NAME` from `cityName` to ECS task / docker run. |
| BE-25 | Seeding status tracking | 4 | Add `SEEDING` status to `SandboxStatus` enum. SeedingEngine sets RUNNING when complete. |
| BE-26 | Dev tools "Reset demo" endpoint | 4 | Re-runs `SeedingEngine.seedSandbox()`. Matches prototype footer button. |

**BE Total: ~30 hrs**

### Frontend Tasks

| # | Task | Hrs | Notes |
|---|---|---|---|
| FE-8 | Seeding progress phase in status view | 4 | HTMX second phase after RUNNING: "Seeding programs..." badge before final RUNNING state. |

**FE Total: ~4 hrs**

### Definition of Done
1. Create sandbox for "Burlington, VT" → CiviForm header shows "Burlington, VT"
2. Two programs visible: "Comprehensive Sample Program" + "Minimal Sample Program"
3. Both programs are clickable and show the correct description
4. "Reset demo" dev tools button re-seeds both programs

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
