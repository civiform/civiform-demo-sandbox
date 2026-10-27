# CiviForm Demo Sandbox Builder (`cf-sandbox-builder`)

A self-service web platform for Exygy BD leads and CiviForm staff to spin up isolated, fully-functional
[CiviForm](https://github.com/civiform/civiform/) demo instances on AWS —
shareable with prospects via a 6-digit PIN and a branded subdomain.

**Cloud**: AWS ECS Fargate + RDS Postgres.
**Status**: Core provisioning and AWS infrastructure complete. Seeding engine and CiviForm demo enhancements in progress.

---

## What This Is

`cf-sandbox-builder` is a **separate service** that provisions isolated CiviForm instances
on demand. It is **not** a fork of CiviForm. Each sandbox gets its own Postgres database,
ECS Fargate service, S3 buckets, ALB routing rules, and Secrets Manager entries — all created
and destroyed via Terraform.

**Core user flow:**
> Sales rep logs into the portal → fills out a create wizard (city name, subdomain, PIN, expiry)
> → Terraform provisions a full CiviForm instance on ECS Fargate → prospect enters 6-digit PIN
> at `/sandboxes/:id/access` → access cookie set → redirects directly to live CiviForm instance
> at `https://<subdomain>.sandbox.civiform.dev`

---

## Architecture & Technology Stack

| Layer | Technology |
|---|---|
| Backend | Play Framework 3.0 (Java 21) + Google Guice DI |
| Templating | Thymeleaf + HTMX (reactive status polling) |
| Design system | USWDS 3.x + Tailwind CSS |
| Frontend tooling | Vite + TypeScript + Sass + PostCSS |
| Container runtime | AWS ECS Fargate (production) / Docker socket (local dev) |
| Database | PostgreSQL 16 — builder metadata + per-sandbox isolated databases on shared RDS |
| Testing | JUnit 4 + AssertJ + Mockito (164+ unit tests) + Playwright (browser tests) |
| Cloud | **AWS only** (ECS Fargate + RDS). GCP is not used. |
| Auth | Auth0 OIDC (production) / email+password fallback (local dev) |

### Database Isolation

Each sandbox gets its own **Postgres database** (not just a schema) on the shared RDS instance.
This provides credential-level isolation: a misconfigured sandbox cannot access another's data.
CiviForm assumes database ownership for Play evolutions, and `DROP DATABASE` at expiry is an
atomic, provable teardown. See [#16](https://github.com/civiform/civiform-demo-sandbox/issues/16).

### Wildcard DNS + ALB Cookie Routing

All sandboxes share a single ALB with a wildcard TLS certificate (`*.sandbox.civiform.dev`).
No per-sandbox DNS changes are needed — new sandboxes are instantly reachable as soon as
Terraform creates their ALB listener rules. Access is gated by a cookie set after PIN
validation, enforced at the ALB level (not in CiviForm application code).

---

## Directory Structure

```
cf-sandbox-builder/
├── Dockerfile                  # Development container image
├── prod.Dockerfile             # Production multi-stage release image (JDK build + JRE + Terraform)
├── docker-compose.yml          # Postgres 16 + builder service (ports 9001, 5174)
├── docker-compose.dev.yml      # Dev overrides (volume mounts, hot reload)
├── init_postgres.sql           # DB init: sandbox_instances table + sequences
├── migrations/                 # One-time SQL for upgrading pre-existing environments
│   ├── 2026-09-database-per-sandbox.sql
│   ├── 2026-09-add-missing-sandbox-columns.sql
│   ├── 2026-09-terraform-runtime.sql
│   └── 2026-10-pin-gate-access-token.sql
├── bin/                        # Developer CLI scripts
│   ├── run-dev                 # Start full dev stack (Postgres + builder)
│   ├── stop-dev                # Stop all containers
│   ├── build-dev               # Rebuild dev container image
│   ├── deploy-prod             # Build, push to ECR, trigger ECS rolling deploy
│   ├── rds-tunnel              # SSM tunnel to RDS for local Terraform testing
│   ├── sbt                     # Run SBT commands inside the dev container
│   └── npm                     # Run npm commands inside the dev container
├── browser-test/               # Playwright E2E tests
│   ├── src/tests/              # Test specs (login, pin_gate, dashboard, create_sandbox_wizard)
│   └── playwright.config.ts    # Playwright config
├── terraform/                  # AWS infrastructure (3 layers)
│   ├── bootstrap/              # One-time: S3 state bucket + KMS key
│   ├── main.tf                 # Platform root: provider, S3 backend config
│   ├── vpc.tf                  # VPC, subnets, IGW, NAT, security groups
│   ├── rds.tf                  # Shared RDS PostgreSQL 16.3 instance
│   ├── alb.tf                  # ALB, ACM wildcard cert, HTTP→HTTPS redirect
│   ├── ecs.tf                  # ECS cluster, builder task/service, IAM, ECR
│   ├── kms.tf                  # Shared KMS key for secrets + S3
│   ├── placeholder_secrets.tf  # 7 inert IdP placeholder secrets for CiviForm
│   └── sandbox/                # Per-sandbox: ECS service, ALB rules, secrets, S3
│       ├── main.tf             # CiviForm ECS task via upstream cloud-deploy-infra modules
│       ├── secrets.tf          # 4 per-sandbox secrets (DB creds, app key, API salt)
│       ├── filestorage.tf      # 2 S3 buckets (private uploads + public program images)
│       └── outputs.tf          # URL, ARNs parsed by Java after apply
└── server/                     # Play Framework Java application
    ├── build.sbt               # SBT build — JVM dependencies
    ├── conf/
    │   ├── application.conf    # Play config (DB, runtime, Auth0, Terraform, AWS)
    │   ├── routes              # HTTP route definitions
    │   └── messages            # i18n strings
    ├── app/
    │   ├── controllers/        # SandboxController, AuthController, HealthCheckController,
    │   │                       # UntrailingController
    │   ├── models/             # SandboxInstance (@Data @Builder), SandboxStatus enum
    │   ├── services/           # SandboxService interface, TerraformSandboxService,
    │   │                       # DockerSandboxService, InMemorySandboxService,
    │   │                       # SandboxRepository, TerraformCli, PinAttemptLimiter,
    │   │                       # CreateSandboxRequest
    │   ├── filters/            # AuthFilter (session-based route protection)
    │   ├── modules/            # Guice modules (MainModule, ThymeleafModule)
    │   └── views/              # Thymeleaf HTML templates + Java view models
    │       ├── layout/         # MainLayout, LoginLayout, Header, Footer
    │       ├── auth/           # LoginView
    │       └── sandboxes/      # SandboxListView, SandboxDetailsView, PinGateView
    └── test/
        ├── services/           # DockerSandboxServiceTest (25), TerraformSandboxServiceTest (37),
        │                       # SandboxRepositoryTest (21), SandboxRepositoryIntegrationTest (18),
        │                       # InMemorySandboxServiceTest (6), PinAttemptLimiterTest (8)
        ├── controllers/        # SandboxControllerTest (40), AuthControllerTest (22)
        └── filters/            # AuthFilterTest (5)
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
2. Click **Create new demo** (opens wizard modal)
3. Fill in city name, subdomain, PIN, and expiration
4. Click **Create** — status shows PROVISIONING while the container launches
5. When status becomes RUNNING, the sandbox is live at its subdomain URL
6. Share the PIN gate URL (`/sandboxes/<id>/access`) + 6-digit PIN with prospects

> ⚠️ **Docker socket requirement** (local dev only): the builder container must have access to the Docker socket.
> The `docker-compose.yml` mounts `/var/run/docker.sock` into the builder container.
> On Mac, Docker Desktop must be running. On Linux, the socket is available natively.
> In production, `SANDBOX_RUNTIME=terraform` uses ECS Fargate instead.

---

## HTTP Routes

```
GET  /login                      AuthController.login          (login page)
POST /login                      AuthController.authenticate   (local password fallback → session)
GET  /login/auth0                AuthController.loginWithAuth0 (redirect to Auth0 Universal Login)
GET  /callback                   AuthController.callback       (Auth0 OIDC callback → session)
GET  /logout                     AuthController.logout         (clear session → Auth0/login)

GET  /                           SandboxController.index       (redirects to /sandboxes)
GET  /sandboxes                  SandboxController.index       (dashboard — sandbox list)
POST /sandboxes                  SandboxController.create      (form POST → 303 to dashboard)
GET  /sandboxes/:id              SandboxController.show        (sandbox detail page)
GET  /sandboxes/:id/status       SandboxController.statusFragment  (HTMX polling fragment)
POST /sandboxes/:id/delete       SandboxController.delete
POST /sandboxes/:id/extend       SandboxController.extend      (extend expiry by N days)
POST /sandboxes/:id/reprovision  SandboxController.reprovision (re-run Terraform apply)

GET  /sandboxes/:id/access       SandboxController.pinGate     (PIN entry for prospects)
POST /sandboxes/:id/access       SandboxController.validateAccess  (PIN → cookie → redirect to CiviForm)

GET  /health                     HealthCheckController.health
GET  /ready                      HealthCheckController.ready
```

---

## Portal Authentication (Auth0 or Development Password)

Only **one** authentication method is active at a time in `AuthController`:

1. **Auth0 OIDC (Active when `AUTH0_DOMAIN`, `AUTH0_CLIENT_ID`, and `AUTH0_CLIENT_SECRET` are set)**:
   - Displays only the **Continue with Auth0** button on `/login` and disables `POST /login` password authentication.
   - **Tenant**: `civiform-sandbox-builder`
   - **Role-based Allowlist**: Access is restricted to users assigned the **`Sandbox builder users`** role in the Auth0 Dashboard (**User Management → Roles**).
   - **Auth0 Post-Login Action**: A custom Post-Login Action checks that the authenticating user has the `Sandbox builder users` role before issuing a code. If the user lacks the role, Auth0 denies login and redirects back to `/callback?error=access_denied`, which the portal surfaces on the login page:
     ```javascript
     exports.onExecutePostLogin = async (event, api) => {
       // Define the required role(s) to allow login
       const allowedRoles = ['Sandbox builder users'];

       // Check if the user has any of the roles
       const userRoles = event.authorization?.roles || [];
       const hasRequiredRole = userRoles.some(role => allowedRoles.includes(role));

       // If the user does not have the required role, deny access
       if (!hasRequiredRole) {
         api.access.deny('Access denied: You do not have the required role to log in.');
       }
     };
     ```
2. **Development Email/Password (Active when Auth0 is not configured)**:
   - Displays only the email/password form on `/login` (`admin@civiform.dev` + `DEMO_PORTAL_PASSWORD`) and disables `/login/auth0` and `/callback`.

---

## PIN Gate & Access Cookie

When a prospect enters the correct PIN at `/sandboxes/:id/access`:

- Cookie `sb_access_<id>` is set (hyphens in the sandbox ID are replaced with underscores)
  - **Value**: cryptographically generated `accessToken` (UUID, unique per sandbox)
  - **Domain**: `.sandbox.civiform.dev` (so the ALB can inspect it on subdomain requests)
  - **Path**: `/`
  - **Flags**: `HttpOnly`, `Secure`, `SameSite=Lax`
  - **Max-Age**: 30 days
- The response **redirects directly** to the sandbox CiviForm URL (e.g. `https://burlington-vt.sandbox.civiform.dev`)
- The ALB has two listener rules per sandbox:
  1. **Allow rule** (priority N): If host matches subdomain AND cookie matches `sb_access_<id>=<token>` → forward to sandbox target group
  2. **Redirect rule** (priority N+1): If host matches subdomain (no cookie) → 302 redirect to PIN gate

Returning visits to `/sandboxes/:id/access` with a valid cookie skip the PIN form and redirect directly.

Rate limiting: After `PIN_GATE_MAX_ATTEMPTS` (default: 5) failed guesses within a 10-minute window, further attempts return HTTP 429 with `Retry-After` header without checking the PIN.

---

## Environment Variables

### Builder Application

| Variable | Default | Purpose |
|---|---|---|
| `APPLICATION_SECRET` | `changeme` | Play secret — override in production |
| `APP_BASE_URL` | `http://localhost:9001` | Base URL for share links and Auth0 redirects |
| `DB_JDBC_URL` | `jdbc:postgresql://localhost:5432/sandbox_builder` | Builder metadata DB |
| `DB_USER` | `postgres` | Builder DB user |
| `DB_PASSWORD` | `example` | Builder DB password |
| `SANDBOX_RUNTIME` | `docker` | Runtime engine: `docker` (local) or `terraform` (AWS) |
| `SANDBOX_DOMAIN` | `sandbox.civiform.dev` | Base domain for sandbox subdomains and cookie scoping |
| `DEMO_PORTAL_PASSWORD` | `demo` (in `docker-compose.yml`) | Local fallback password for `admin@civiform.dev` |
| `PIN_GATE_MAX_ATTEMPTS` | `5` | Max failed PIN guesses before rate limiting (10-min window) |
| `AUTH0_DOMAIN` | *(empty)* | Auth0 tenant domain (e.g. `civiform-sandbox-builder.us.auth0.com`) |
| `AUTH0_CLIENT_ID` | *(empty)* | Auth0 application Client ID |
| `AUTH0_CLIENT_SECRET` | *(empty)* | Auth0 application Client Secret |
| `AUTH0_CALLBACK_URL` | `${APP_BASE_URL}/callback` | Auth0 OIDC callback URL |

### Docker Runtime (`SANDBOX_RUNTIME=docker`)

| Variable | Default | Purpose |
|---|---|---|
| `DOCKER_SOCKET_PATH` | `unix:///var/run/docker.sock` | Docker daemon socket |
| `SANDBOX_DB_HOST` | `host.docker.internal` | How CiviForm containers reach builder Postgres |
| `CIVIFORM_IMAGE` | `civiform/civiform:latest` | CiviForm Docker image to launch |

### Terraform Runtime (`SANDBOX_RUNTIME=terraform`)

| Variable | Default | Purpose |
|---|---|---|
| `AWS_REGION` | `us-east-1` | AWS region for all resources |
| `TERRAFORM_BINARY` | `terraform` | Path to Terraform CLI binary |
| `TERRAFORM_MODULE_DIR` | `/app/terraform/sandbox` | Sandbox Terraform module template path |
| `TERRAFORM_STATE_BUCKET` | `civiform-sandbox-tfstate` | S3 bucket for Terraform state |
| `PLATFORM_OUTPUTS_FILE` | `/app/platform-outputs.json` | Platform Terraform outputs (file path) |
| `PLATFORM_OUTPUTS_JSON` | *(empty)* | Platform Terraform outputs (inline JSON, alternative to file) |
| `CIVIFORM_IMAGE_TAG` | `latest` | CiviForm Docker image tag pinned for new sandboxes |
| `TERRAFORM_TIMEOUT_MINUTES` | `45` | Max time for `terraform apply/destroy` |
| `TERRAFORM_READINESS_TIMEOUT_MINUTES` | `10` | Max wait for CiviForm `/programs` to return 200 |
| `TERRAFORM_READINESS_POLL_INTERVAL_SECONDS` | `10` | Readiness polling frequency |
| `RDS_ADMIN_DATABASE` | `postgres` | Default DB for admin connections |
| `RDS_ADMIN_USER` | `sandbox_master` | RDS master username |
| `RDS_ADMIN_PASSWORD` | *(empty)* | RDS master password (from Secrets Manager in prod) |
| `RDS_ADMIN_HOST` | *(empty)* | Override for `./bin/rds-tunnel` local dev |
| `RDS_ADMIN_PORT` | `5432` | Override to `15432` with tunnel |

---

## Testing

```bash
./bin/sbt test                    # Unit tests (164+ tests, no Docker/AWS needed)
./bin/run-browser-tests           # Playwright E2E tests
```

### Test Coverage

| Test Class | Count | Notes |
|---|---|---|
| `SandboxControllerTest` | 40 | All controller actions, validation, auth |
| `TerraformSandboxServiceTest` | 37 | Terraform provisioning, deletion, readiness |
| `DockerSandboxServiceTest` | 25 | Docker socket provisioning |
| `AuthControllerTest` | 22 | Auth0 OIDC + password fallback |
| `SandboxRepositoryTest` | 21 | DB CRUD, stale state reconciliation |
| `SandboxRepositoryIntegrationTest` | 18 | Real Postgres via `docker compose` (`bin/run-dev`); excluded from default `sbt test` |
| `PinAttemptLimiterTest` | 8 | Rate limiting logic |
| `InMemorySandboxServiceTest` | 6 | In-memory stub |
| `AuthFilterTest` | 5 | Route protection |

Unit tests use a testable subclass of `DockerSandboxService` that injects a Mockito mock —
no real Docker socket required in CI.

---

## Roadmap

| Feature | Status |
|---|---|
| Docker MVP — provisioning loop, PIN gate, dashboard UI | ✅ Complete |
| AWS ECS Fargate, Terraform provisioning, Auth0, wildcard DNS/SSL | ✅ Complete |
| CiviForm seeding engine — pre-load showcase programs | 🔧 In Progress |
| Demo banner, role switcher, ROI panel, JSON export (PRs to civiform/civiform) | 🔧 In Progress |
| Lifecycle: 30-day auto-expire, teardown engine, usage tracking, cost guardrails | Planned |
| Creator portal graduation flow, Lead/CRM pilot request, PDF Scaffolder | Planned |
| Discovery Engine (Gemini .gov crawler), mobile polish, error states | Planned |
| E2E integration tests, load tests, security hardening, launch polish | Planned |

---

## Known Issues

- **OIDC provider**: The `dev-oidc` Docker Compose service references `civiform-oidc-provider` image which is not publicly available. CiviForm sandboxes launched locally use `FAKE_IDP` mode (`STAGING_DISABLE_DEMO_MODE_LOGINS=false`) instead of a real OIDC provider. The `dev-oidc` service is commented out in `docker-compose.yml`.

---

## Contributing

- **Cloud platform**: AWS only. Do not add GCP dependencies.
- **DI**: Guice only. No static singletons.
- **Async**: All controller actions return `CompletionStage`. No `.join()` in controllers.
- **Tests**: JUnit 4 + AssertJ + Mockito. Every new service method needs a unit test.
- **i18n**: All user-facing strings go in `server/conf/messages`.
- **Branding**: Always "CiviForm" (capital F), never "Civiform".
- **PRs into `civiform/civiform`**: See [`pr-testing-standards.md`](_agents/plugins/cf-sandbox-builder/rules/pr-testing-standards.md) — high bar, full browser test coverage required.
