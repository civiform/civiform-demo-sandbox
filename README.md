# CiviForm Demo Sandbox Builder (`cf-sandbox-builder`)

A self-service web platform for Exygy BD leads to spin up isolated, fully-functional
[CiviForm](https://github.com/civiform/civiform/) demo instances in minutes —
pre-loaded with real civic safety-net programs, shareable with prospects via a 6-digit PIN.

**Cloud**: AWS ECS Fargate + RDS Postgres (Sprint 2+). Sprint 1 uses Docker socket locally.
**Status**: Sprint 2 in progress — demo wrapper, wizard redesign, database-per-sandbox isolation.

---

## What This Is

`cf-sandbox-builder` is a **separate service** that provisions isolated CiviForm instances
on demand. It is **not** a fork of CiviForm. The builder launches `civiform/civiform:latest`
Docker containers and passes environment variables to configure each instance.

**Core user flow:**
> Sales rep logs into the portal → fills out a wizard → container launches → PIN generated → prospect enters PIN → live CiviForm demo in an iframe wrapper with role switcher

---

## Architecture & Technology Stack

| Layer | Technology |
|---|---|
| Backend | Play Framework 3.0 (Java 21) + Google Guice DI |
| Templating | Thymeleaf + HTMX (reactive status polling) |
| Design system | USWDS 3.x + Tailwind CSS |
| Frontend tooling | Vite + TypeScript + Sass + PostCSS |
| Container runtime | Docker socket (Sprint 1) → AWS ECS Fargate (Sprint 2+) |
| Database | PostgreSQL — metadata store + per-sandbox **isolated databases** |
| Testing | JUnit 4 + AssertJ + Mockito + Playwright (browser tests) |
| Cloud | **AWS only** (ECS Fargate + RDS). GCP is not used. |

### Database Isolation

Each sandbox gets its own **Postgres database** (not just a schema) on the shared RDS instance.
This provides credential-level isolation: a misconfigured sandbox cannot access another's data.
CiviForm assumes database ownership for Play evolutions, and `DROP DATABASE` at expiry is an
atomic, provable teardown. See [#16](https://github.com/civiform/civiform-demo-sandbox/issues/16).

---

## Directory Structure

```
cf-sandbox-builder/
├── Dockerfile                  # Development container image
├── prod.Dockerfile             # Production multi-stage release image
├── docker-compose.yml          # Postgres 16 + builder service (ports 9001, 5174)
├── docker-compose.dev.yml      # Dev overrides (volume mounts, hot reload)
├── init_postgres.sql           # DB init: sandbox_instances table + port sequence
├── bin/                        # Developer CLI scripts
│   ├── run-dev                 # Start full dev stack (Postgres + builder)
│   ├── stop-dev                # Stop all containers
│   ├── build-dev               # Rebuild dev container image
│   ├── sbt                     # Run SBT commands inside the dev container
│   └── npm                     # Run npm commands inside the dev container
├── browser-test/               # Playwright E2E tests
│   ├── src/tests/              # Test specs (login, pin_gate, sandbox_list)
│   └── playwright.config.ts    # Playwright config
├── terraform/                  # AWS infrastructure (ECS, RDS, VPC, ALB)
│   ├── main.tf                 # Provider + backend config
│   ├── ecs.tf                  # ECS cluster + task definitions
│   ├── rds.tf                  # Shared RDS Postgres instance
│   ├── alb.tf                  # Application Load Balancer + wildcard cert
│   └── vpc.tf                  # VPC, subnets, security groups
└── server/                     # Play Framework Java application
    ├── build.sbt               # SBT build — JVM dependencies
    ├── conf/
    │   ├── application.conf    # Play config (DB, Docker socket, sandbox image)
    │   ├── routes              # HTTP route definitions
    │   └── messages            # i18n strings
    ├── app/
    │   ├── controllers/        # SandboxController, AuthController, HealthCheckController
    │   ├── models/             # SandboxInstance (@Data @Builder), SandboxStatus enum
    │   ├── services/           # SandboxService interface, DockerSandboxService,
    │   │                       # InMemorySandboxService, SandboxRepository
    │   ├── modules/            # Guice modules (MainModule, ThymeleafModule)
    │   └── views/              # Thymeleaf HTML templates + Java view models
    │       ├── layout/         # MainLayout, LoginLayout, Header, Footer
    │       ├── auth/           # LoginView
    │       └── sandboxes/      # SandboxList, SandboxDetails, PinGate, DemoWrapper
    └── test/
        ├── services/           # DockerSandboxServiceTest (22 tests)
        └── controllers/        # SandboxControllerTest, AuthControllerTest
```

---

## Getting Started

### Prerequisites

- [Docker](https://docs.docker.com/get-docker/) & Docker Compose
- (Optional, for running tests on host): OpenJDK 21+, SBT 1.10+, Node.js 20+

### Run locally

```bash
./bin/run-dev
```

Starts Postgres 16 and the builder app. On first run, SBT downloads dependencies
and Vite compiles frontend assets — allow ~3 minutes.

| Endpoint | URL |
|---|---|
| Login page | http://localhost:9001/login |
| Dashboard | http://localhost:9001/sandboxes |
| Health check | http://localhost:9001/health |
| Ready check | http://localhost:9001/ready |

Default login credentials: `admin@civiform.dev` / password set via `DEMO_PORTAL_PASSWORD` env var (default: `demo`).

```bash
./bin/stop-dev   # stop all containers
```

### Create your first sandbox

1. Open http://localhost:9001 → log in
2. Click **Create new demo** (opens wizard popup)
3. Fill in city name, subdomain, PIN, and expiration
4. Click **Create** — status shows PROVISIONING while the container launches
5. When status becomes RUNNING, click **Launch** to open the demo wrapper
6. Share the PIN gate URL (`/sandboxes/<id>/access`) + 6-digit PIN with prospects

> ⚠️ **Docker socket requirement**: the builder container must have access to the Docker socket.
> The `docker-compose.yml` mounts `/var/run/docker.sock` into the builder container.
> On Mac, Docker Desktop must be running. On Linux, the socket is available natively.

---

## HTTP Routes

```
GET  /login                      AuthController.login          (login page)
POST /login                      AuthController.authenticate   (password auth → session)
GET  /logout                     AuthController.logout         (clear session → /login)

GET  /                           SandboxController.index       (redirects to /sandboxes)
GET  /sandboxes                  SandboxController.index       (dashboard — sandbox list)
POST /sandboxes                  SandboxController.create      (form POST → 303 to detail)
GET  /sandboxes/:id              SandboxController.show        (sandbox detail page)
GET  /sandboxes/:id/status       SandboxController.statusFragment  (HTMX polling fragment)
POST /sandboxes/:id/delete       SandboxController.delete
POST /sandboxes/:id/extend       SandboxController.extend      (extend expiry by N days)

GET  /sandboxes/:id/access       SandboxController.pinGate     (PIN entry for prospects)
POST /sandboxes/:id/access       SandboxController.validateAccess  (PIN → session cookie)
GET  /sandboxes/:id/view         SandboxController.demoView    (iframe wrapper + banner)

GET  /health                     HealthCheckController.health
GET  /ready                      HealthCheckController.ready
```

---

## Demo Wrapper

When a prospect enters the correct PIN, they see the CiviForm instance wrapped in a
persistent demo banner with:

- **City name** and sandbox branding (e.g. "DEMO: Santa Cruz, CA")
- **Days remaining** countdown badge
- **Role switcher** buttons (Resident / CiviForm Admin / Program Admin)
- **Settings dropdown** (share link, open in new tab, contact sales)
- Full CiviForm UI in an iframe below the banner

---

## PIN Session Cookie

When a prospect enters the correct PIN:
- Cookie `sb_access_<id>` is set: **HTTP-only**, SameSite=Lax, path `/sandboxes/<id>`, 30-day max-age
- Returning visits to `/sandboxes/:id/access` skip the PIN form and redirect directly to the demo wrapper

---

## Environment Variables (builder)

| Variable | Default | Purpose |
|---|---|---|
| `APPLICATION_SECRET` | `changeme` | Play secret — override in production |
| `DB_JDBC_URL` | `jdbc:postgresql://localhost:5432/sandbox_builder` | Builder Postgres |
| `DB_USER` | `postgres` | Builder DB user |
| `DB_PASSWORD` | `example` | Builder DB password |
| `DOCKER_SOCKET_PATH` | `unix:///var/run/docker.sock` | Docker socket (Sprint 1) |
| `CIVIFORM_IMAGE` | `civiform/civiform:latest` | CiviForm image to launch |
| `SANDBOX_DB_HOST` | `host.docker.internal` | How CiviForm containers reach builder Postgres |
| `APP_BASE_URL` | `http://localhost:9000` | Used in share links |
| `DEMO_PORTAL_PASSWORD` | `demo` | Login password for the demo portal |

---

## Testing

```bash
./bin/sbt test                    # Unit tests (36 tests, no Docker socket needed)
./bin/run-browser-tests           # Playwright E2E tests
```

Unit tests use a testable subclass of `DockerSandboxService` that injects a Mockito mock —
no real Docker socket required in CI.

---

## Sprint Roadmap

| Sprint | Focus | Status |
|---|---|---|
| **S1** | Docker MVP — provisioning loop, PIN gate, dashboard UI | ✅ Complete |
| **S2** | Demo wrapper, wizard redesign, database-per-sandbox isolation, portal styling | 🔧 In Progress |
| S3 | CiviForm seeding engine — pre-load showcase programs + city-specific programs | Planned |
| S4 | Demo banner, role switcher, ROI panel, JSON export (PR to civiform/civiform) | Planned |
| S5 | 30-day teardown engine (EventBridge + Lambda + DLQ) | Planned |
| S6 | PDF Scaffolder + Discovery Engine (Gemini) | Planned |
| S7 | City name injection, SMTP, cost guardrails, security hardening | Planned |
| S8 | Integration tests, load tests, launch polish | Planned |

Full sprint plan: [`_agents/plugins/cf-sandbox-builder/skills/mvp-sprint/SKILL.md`](_agents/plugins/cf-sandbox-builder/skills/mvp-sprint/SKILL.md)

---

## Known Issues

- **Scala compiler bug**: `sbt compile` fails with `Error while emitting Routes.scala — assertion failed: bad position: [134:128]`. This is a Scala 2.13 backend bug triggered by Play's generated router code. Investigation ongoing.
- **`init_postgres.sql` out of sync**: Missing columns (`subdomain`, `target_group_arn`, `listener_rule_arn`, `google_analytics_id`, `google_analytics_url`, `deleted_at`). Must be updated before fresh DB init.
- **OIDC provider**: The `dev-oidc` Docker Compose service references `civiform-oidc-provider` image which is not publicly available. CiviForm sandboxes launched locally may fail OIDC discovery.

---

## Contributing

- **Cloud platform**: AWS only. Do not add GCP dependencies.
- **DI**: Guice only. No static singletons.
- **Async**: All controller actions return `CompletionStage`. No `.join()` in controllers.
- **Tests**: JUnit 4 + AssertJ + Mockito. Every new service method needs a unit test.
- **i18n**: All user-facing strings go in `server/conf/messages`.
- **PRs into `civiform/civiform`** (Sprint 4+): See [`pr-testing-standards.md`](_agents/plugins/cf-sandbox-builder/rules/pr-testing-standards.md) — high bar, full browser test coverage required.
