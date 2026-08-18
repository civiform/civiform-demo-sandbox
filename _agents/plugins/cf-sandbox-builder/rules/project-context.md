---
title: cf-sandbox-builder — Project Context & Architecture Rules
trigger: always_on
---

# Project: Instant Sales Sandbox Provisioner (`cf-sandbox-builder`)

## What This Repo Is

`cf-sandbox-builder` is a **web platform and orchestration tool** to deploy, manage, and tear down on-demand
CiviForm demo/sandbox instances for Exygy's sales process. It is **not** a fork of CiviForm — it is a
separate service that provisions isolated CiviForm instances on demand.

Product name: **Instant Sales Sandbox Provisioner**
Client: Exygy / CiviForm  
Status: Active development (Aug 2026)  
PRD last updated: Aug 3, 2026 (v2 — DRAFT)
Sprint plan: 2-week MVP sprint (see skills/mvp-sprint for the full plan)

---

## Tech Stack (Locked)

| Layer | Technology |
|---|---|
| Backend framework | Play Framework 3.0 (Java) + Google Guice DI |
| Server-side templating | Thymeleaf (with PlayMessageResolver) |
| Frontend tooling | Vite + TypeScript + HTMX + USWDS 3.x + Tailwind CSS |
| Database | PostgreSQL (RDS on AWS in prod, Docker locally) |
| Container runtime | **Docker socket (MVP Sprint 1)** → ECS Fargate (Sprint 2+) |
| Infra-as-code | Terraform — AWS only (ECS Fargate + RDS Postgres) |
| Cloud | **AWS only** — GCP was explicitly rejected Aug 3, 2026 |
| Generator Portal (future) | React (Vite) + Node.js REST (separate from Play server) |

**Do not suggest GCP Cloud Run, Cloud SQL, or GCP Terraform.** The AWS-only decision is locked.
**For Sprint 1 MVP**: Use Docker socket + docker-java library. Do NOT attempt AWS ECS Fargate wiring in Sprint 1.

---

## Confirmed Architecture Decisions (as of Aug 3, 2026)

- **Cloud**: AWS ECS Fargate + RDS Postgres (Sprint 2+; MVP uses Docker socket)
- **GCP path**: Rejected. Tom's GCP Terraform modules are discarded.
- **Generator Portal stack**: React (Vite) + Node.js (separate service from Play backend)
- **Sandbox sharing**: 6-digit PIN gate per sandbox (generated at creation, validated on access)
- **Teardown**: EventBridge-based cron + Lambda + Dead-Letter Queue with Slack alerts (30-day expiry; manual for MVP)
- **PDF Scaffolder**: Reuse existing `pdf-to-civiform-ocr` Flask service — wire via API, don't rewrite
- **Discovery Engine**: Uses `pdf-to-civiform-ocr` web crawler as crawl layer + Gemini API for extraction + CiviForm `ProgramMigrationService` for seeding
- **Lead/CRM ingestion**: Pilot request endpoint with tiers (`discretionary_pilot`, `full_enterprise`, `partner_reseller`) — needs product sign-off

---

## MVP Sprint 1 — Container Runtime Decision

**CRITICAL DECISION (must confirm Day 1 of sprint):**

| Option | Recommendation | Reason |
|---|---|---|
| **Option A: Docker socket** | ✅ **USE THIS for MVP** | Works in 2 weeks. Mount `/var/run/docker.sock` into builder container; use `docker-java` library from Java |
| Option B: AWS ECS Fargate | ❌ Defer to Sprint 2 | IAM, VPC, ALB, SSL = minimum 3-4 weeks setup |

Application logic (`SandboxService`, PIN gate, seeding) is **identical** between Option A and B — only the runtime call changes. No work is thrown away.

**docker-java dependency to add to `build.sbt`:**
```scala
"com.github.docker-java" % "docker-java-core" % "3.4.0",
"com.github.docker-java" % "docker-java-transport-httpclient5" % "3.4.0",
```

---

## Repository Structure

```
cf-sandbox-builder/
├── Dockerfile / prod.Dockerfile    # Dev + production container images
├── docker-compose.yml              # Postgres 16-alpine + builder service (port 9000, 5173)
├── docker-compose.dev.yml          # Dev overrides (volume mounts)
├── init_postgres.sql               # DB init script
├── bin/                            # Developer CLI scripts (build-dev, run-dev, stop-dev, sbt, npm)
└── server/                         # Play Framework Java app
    ├── build.sbt                   # SBT build — add docker-java deps here for Sprint 1
    ├── conf/
    │   ├── application.conf        # Main Play config
    │   ├── routes                  # HTTP route definitions
    │   └── messages                # i18n strings
    └── app/
        ├── controllers/            # HomeController, SandboxController, HealthCheckController
        ├── models/                 # SandboxInstance, SandboxStatus
        ├── modules/                # Guice modules (MainModule, ThymeleafModule, ObjectMapperModule)
        ├── services/               # SandboxService interface + InMemorySandboxService impl
        └── views/                  # BaseView, Thymeleaf view models, layout templates
```

---

## Current Implementation State (as of Aug 2026 — VERIFIED from code)

**What is fully wired (not just stubs):**

| Component | State | Notes |
|---|---|---|
| `SandboxController` | ✅ Fully wired | Handles JSON + HTML, form parsing, all CRUD routes |
| `InMemorySandboxService` | ✅ In-memory impl with seeded demo data | UUID-based IDs, 24hr expiry, full CRUD |
| `SandboxInstance` | ✅ Lombok `@Data @Builder` model | id, name, civiformVersion, status, url, adminEmail, notes, createdAt, expiresAt |
| `SandboxStatus` enum | ✅ Done | PROVISIONING, RUNNING, STOPPED, FAILED, DESTROYED |
| Play routes | ✅ All defined | GET/POST /sandboxes, GET /sandboxes/:id, POST /sandboxes/:id/delete |
| `build.sbt` | ✅ Has Guice, javaJdbc, javaWs, Guava, Thymeleaf, Postgres JDBC, Lombok | Missing: docker-java, Ebean/ORM |

**What is NOT yet implemented (true gaps for Sprint 1):**
- `sandbox_instances` DB table + Ebean/JPA model (currently in-memory only)
- Docker socket integration (docker-java not in build.sbt)
- `SandboxService.createSandbox()` does not launch real containers
- PIN generation, storage, and validation
- HTMX polling status endpoint
- PIN entry gate page (`/access/:id`)

---

## Current API Routes

```
GET  /                          HomeController.index
GET  /health                    HealthCheckController.health
GET  /ready                     HealthCheckController.ready
GET  /sandboxes                 SandboxController.index      (returns JSON or HTML list)
POST /sandboxes                 SandboxController.create     (accepts JSON or form POST)
GET  /sandboxes/:id             SandboxController.show       (returns JSON or HTML detail)
POST /sandboxes/:id/delete      SandboxController.delete
```

---

## `SandboxInstance` Model Fields

```java
String id, name, civiformVersion, url, adminEmail, notes;
SandboxStatus status;  // PROVISIONING | RUNNING | STOPPED | FAILED | DESTROYED
Instant createdAt, expiresAt;
// TODO Sprint 1: add pin (String), containerID (String), hostPort (int)
```

---

## `SandboxService` Interface

```java
CompletionStage<ImmutableList<SandboxInstance>> listSandboxes();
CompletionStage<Optional<SandboxInstance>> getSandbox(String id);
CompletionStage<SandboxInstance> createSandbox(String name, String version, String adminEmail, String notes);
CompletionStage<Boolean> deleteSandbox(String id);
CompletionStage<Optional<SandboxInstance>> validatePin(String sandboxId, String pin); // Sprint 1 — must add
```

> ⚠️ `InMemorySandboxService` sets status to `RUNNING` immediately — this must change to `PROVISIONING`
> in the real implementation. PIN is generated at request time (before async provisioning starts).

Current implementation is `InMemorySandboxService` — real implementation will provision Docker containers (Sprint 1) then ECS Fargate (Sprint 2).

**Sprint 1 DB note**: Each CiviForm container needs its own Postgres schema.
`DockerSandboxService` creates a schema + user before `docker run`, then passes
`DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`, `APPLICATION_SECRET`,
and `STAGING_DISABLE_DEMO_MODE_LOGINS=false` as env vars to the container.

---

## MVP Sprint 1 Scope — What to Build (2 weeks)

**Goal**: Sales rep creates sandbox for a named city, gets a PIN, prospect enters PIN and gets a live CiviForm instance.

### Backend tasks
| Task | Effort | Key detail |
|---|---|---|
| BE-1: `sandbox_instances` DB schema + Ebean model | 4 hrs | Add `pin`, `container_id`, `host_port` fields |
| BE-2: Add docker-java to `build.sbt` | 4 hrs | `docker-java-core` + `docker-java-transport-httpclient5` |
| BE-3: `SandboxService.createSandbox()` → Docker socket | 16 hrs | Launch `civiform/civiform:latest`; assign port from 10000–11000 range |
| BE-4: `getSandboxStatus()` → poll container health | 4 hrs | Check container running + CiviForm `/health` |
| BE-5: 6-digit PIN generation + DB storage | 2 hrs | Random, scoped to sandbox |
| BE-6: PIN validation `POST /sandbox/access` | 4 hrs | Validate PIN → redirect to sandbox URL |
| BE-7: Wire `SandboxController.create()` → detail view redirect | 4 hrs | Already has redirect skeleton |
| BE-8: HTMX polling endpoint → partial HTML status badge | 2 hrs | Returns fragment, not full page |

### Frontend tasks
| Task | Effort | Key detail |
|---|---|---|
| FE-1: "Create Sandbox" form page | 6 hrs | City name input, program type dropdown, USWDS form |
| FE-2: Provisioning progress view | 6 hrs | HTMX polling → animated status badge |
| FE-3: Sandbox detail page | 6 hrs | URL, PIN (prominent), copy button |
| FE-4: PIN entry gate page `/access/:id` | 4 hrs | Simple PIN input form |
| FE-5: Basic sandbox list dashboard | 4 hrs | Table with status + links |

### Deferred to Sprint 2+ (do NOT implement in Sprint 1)
- Auth/RBAC for sales reps (use shared password or IP allowlist for MVP)
- AWS ECS Fargate (replaces Docker socket — logic is identical)
- CiviForm DB seeding with real programs
- City name branding (`WHITELABEL_CIVIC_ENTITY_SHORT_NAME` env var)
- 30-day expiry/teardown (manual container stop for MVP)
- Usage tracking, PDF Scaffolder, Discovery Engine, Lead/CRM

---

## Roadmap Open Items (From PRD v2)

**Needs product decision / sign-off before building:**
- [ ] Gemini Discovery Engine (crawls .gov sites) — new scope item, needs product green-light
- [x] ~~Dual-cloud GCP Terraform~~ — ✅ **AWS-only confirmed Aug 2026. GCP discarded.**
- [ ] Pilot tier definitions (`discretionary_pilot` vs `full_enterprise` vs `partner_reseller`)
- [ ] External CRM integration (HubSpot/Salesforce) vs. portal-native leads table

**Still unanswered:**
- [ ] Auth mechanism for Generator Portal (how do Exygy sales reps log in?)
- [ ] Single portal or two portals (sales rep vs. city admin)?
- [ ] Who authors the initial real program JSON templates?
- [ ] PIN expiry: same as sandbox (30-day) or independently configurable?
- [ ] Who triggers the graduation flow — Exygy manually or a portal button?

---

## Items Explicitly OUT of Scope for V1

- Real SMTP from sandbox (mock only)
- Multi-language seeding
- Custom program branding (logos/colors) per city
- Billing/metering
- Production traffic support
- Dual-cloud (GCP) Terraform
- Tom's "PM Twin" Node.js prototype as production code (reference only)

---

## Coding Conventions

- Follow Play Framework 3.0 / Java 21 idioms
- Use Guice for dependency injection — do not use static singletons
- All async operations must use `CompletionStage` (not blocking calls)
- UI strings go in `server/conf/messages` for i18n support
- Frontend assets compiled by Vite into `server/public/dist/`
- Use USWDS 3.x components before reaching for raw Tailwind
- Use Lombok `@Data @Builder` on model classes
- Tests: JUnit 4 + AssertJ + Mockito
