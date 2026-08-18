# CiviForm Demo Sandbox Builder (`cf-sandbox-builder`)

A self-service web platform for Exygy BD leads to spin up isolated, fully-functional
[CiviForm](https://github.com/civiform/civiform/) demo instances in minutes —
pre-loaded with real civic safety-net programs, shareable with prospects via a 6-digit PIN.

**Cloud**: AWS ECS Fargate + RDS Postgres (Sprint 2+). Sprint 1 uses Docker socket locally.
**Status**: Sprint 1 complete — full provisioning loop, PIN gate, dashboard UI.

---

## What This Is

`cf-sandbox-builder` is a **separate service** that provisions isolated CiviForm instances
on demand. It is **not** a fork of CiviForm. The builder launches `civiform/civiform:latest`
Docker containers and passes environment variables to configure each instance.

Sprint 1 delivers the minimum vertical slice:
> Sales rep fills out a form → container launches → PIN generated → prospect enters PIN → live CiviForm demo

---

## Architecture & Technology Stack

| Layer | Technology |
|---|---|
| Backend | Play Framework 3.0 (Java 21) + Google Guice DI |
| Templating | Thymeleaf + HTMX (reactive status polling) |
| Design system | USWDS 3.x + Tailwind CSS |
| Frontend tooling | Vite + TypeScript + Sass + PostCSS |
| Container runtime | Docker socket (Sprint 1) → AWS ECS Fargate (Sprint 2+) |
| Database | PostgreSQL — metadata store + per-sandbox isolated schemas |
| Testing | JUnit 4 + AssertJ + Mockito + Play test helpers |
| Cloud | **AWS only** (ECS Fargate + RDS). GCP is not used. |

---

## Directory Structure

```
cf-sandbox-builder/
├── Dockerfile                  # Development container image
├── prod.Dockerfile             # Production multi-stage release image
├── docker-compose.yml          # Postgres 16 + builder service (ports 9000, 5173)
├── docker-compose.dev.yml      # Dev overrides (volume mounts, hot reload)
├── init_postgres.sql           # DB init: sandbox_instances table + port sequence
├── bin/                        # Developer CLI scripts
│   ├── run-dev                 # Start full dev stack (Postgres + builder)
│   ├── stop-dev                # Stop all containers
│   ├── build-dev               # Rebuild dev container image
│   ├── sbt                     # Run SBT commands inside the dev container
│   └── npm                     # Run npm commands inside the dev container
└── server/                     # Play Framework Java application
    ├── build.sbt               # SBT build — JVM dependencies
    ├── conf/
    │   ├── application.conf    # Play config (DB, Docker socket, sandbox image)
    │   ├── routes              # HTTP route definitions
    │   └── messages            # i18n strings
    ├── app/
    │   ├── controllers/        # SandboxController (all 7 actions), HomeController, HealthCheck
    │   ├── models/             # SandboxInstance (@Data @Builder), SandboxStatus enum
    │   ├── services/           # SandboxService interface, InMemorySandboxService,
    │   │                       # DockerSandboxService (Sprint 1), SandboxRepository
    │   ├── modules/            # Guice modules (MainModule, ThymeleafModule, ObjectMapperModule)
    │   └── views/              # Thymeleaf HTML templates + Java view models
    └── test/
        ├── services/           # DockerSandboxServiceTest (22 tests)
        └── controllers/        # SandboxControllerTest (14 tests)
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
| Dashboard | http://localhost:9000/sandboxes |
| Health check | http://localhost:9000/health |
| Ready check | http://localhost:9000/ready |

```bash
./bin/stop-dev   # stop all containers
```

### Create your first sandbox

1. Open http://localhost:9000/sandboxes
2. Click **Create new demo**
3. Enter a city name (e.g. "Burlington, VT") and your email
4. Click **Create Sandbox** — status shows PROVISIONING while the container launches
5. When status becomes RUNNING, share `/sandboxes/<id>/access` + the 6-digit PIN with a prospect

> ⚠️ **Sprint 1 requirement**: the builder container must have access to the Docker socket.
> The `docker-compose.yml` mounts `/var/run/docker.sock` into the builder container.
> On Mac, Docker Desktop must be running. On Linux, the socket is available natively.

---

## HTTP Routes

```
GET  /                           HomeController.index          (redirects to /sandboxes)
GET  /health                     HealthCheckController.health
GET  /ready                      HealthCheckController.ready
GET  /sandboxes                  SandboxController.index       (dashboard — HTML or JSON)
POST /sandboxes                  SandboxController.create      (form POST → 303 to /sandboxes/:id)
GET  /sandboxes/:id              SandboxController.show        (sandbox detail page)
GET  /sandboxes/:id/status       SandboxController.statusFragment  (HTMX polling fragment)
POST /sandboxes/:id/delete       SandboxController.delete
GET  /sandboxes/:id/access       SandboxController.pinGate     (PIN entry for prospects)
POST /sandboxes/:id/access       SandboxController.validateAccess  (PIN validation → cookie)
```

---

## PIN Session Cookie

When a prospect enters the correct PIN:
- Cookie `sb_access_<id>` is set: **HTTP-only**, SameSite=Lax, path `/sandboxes/<id>`, 30-day max-age
- Returning visits to `/sandboxes/:id/access` skip the PIN form and redirect directly to CiviForm

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

---

## Testing

```bash
./bin/sbt test
```

36 unit tests — no real Docker socket required. `DockerSandboxServiceTest` uses a
testable subclass that overrides `buildDockerClient()` to inject a Mockito mock.

---

## Sprint Roadmap

| Sprint | Focus | Status |
|---|---|---|
| **S1** | Docker MVP — provisioning loop, PIN gate, dashboard UI | ✅ Complete |
| S2 | AWS ECS Fargate + RDS — replace Docker socket with cloud runtime | 🔜 Next |
| S3 | CiviForm seeding engine — pre-load showcase programs + city-specific programs | Planned |
| S4 | Demo banner, role switcher, ROI panel, JSON export (PR to civiform/civiform) | Planned |
| S5 | 30-day teardown engine (EventBridge + Lambda + DLQ) | Planned |
| S6 | PDF Scaffolder + Discovery Engine (Gemini) | Planned |
| S7 | City name injection, SMTP, cost guardrails, security hardening | Planned |
| S8 | Integration tests, load tests, launch polish | Planned |

Full sprint plan: [`_agents/plugins/cf-sandbox-builder/skills/mvp-sprint/SKILL.md`](_agents/plugins/cf-sandbox-builder/skills/mvp-sprint/SKILL.md)

---

## Contributing

- **Cloud platform**: AWS only. Do not add GCP dependencies.
- **DI**: Guice only. No static singletons.
- **Async**: All controller actions return `CompletionStage`. No `.join()` in controllers.
- **Tests**: JUnit 4 + AssertJ + Mockito. Every new service method needs a unit test.
- **i18n**: All user-facing strings go in `server/conf/messages`.
- **PRs into `civiform/civiform`** (Sprint 4+): See [`pr-testing-standards.md`](_agents/plugins/cf-sandbox-builder/rules/pr-testing-standards.md) — high bar, full browser test coverage required.
