# CiviForm Demo Sandbox Builder — Deployment & Operations Guide

> **Audience**: CiviForm engineering team
> **Last Updated**: October 26, 2026
> **Repo**: [civiform/civiform-demo-sandbox](https://github.com/civiform/civiform-demo-sandbox)

---

## Table of Contents

1. [System Overview](#system-overview)
2. [Architecture](#architecture)
3. [Prerequisites](#prerequisites)
4. [Bootstrap: One-Time AWS Setup](#bootstrap-one-time-aws-setup)
5. [Platform Infrastructure (Terraform)](#platform-infrastructure-terraform)
6. [DNS & SSL Configuration](#dns--ssl-configuration)
7. [Auth0 Setup](#auth0-setup)
8. [Deploying the Builder Application](#deploying-the-builder-application)
9. [Environment Variables Reference](#environment-variables-reference)
10. [Per-Sandbox Lifecycle](#per-sandbox-lifecycle)
11. [Database Schema](#database-schema)
12. [Secrets Manager Pattern](#secrets-manager-pattern)
13. [Monitoring & Logs](#monitoring--logs)
14. [Local Development](#local-development)
15. [Troubleshooting](#troubleshooting)
16. [Operational Runbook](#operational-runbook)

---

## System Overview

The Sandbox Builder is a web portal that provisions isolated, single-tenant CiviForm instances on AWS ECS Fargate. Each sandbox gets its own database, S3 buckets, ECS service, and ALB routing rules — all created and destroyed via Terraform.

```
┌──────────────────────────────────────────────────────────────┐
│  sandbox.civiform.dev (Builder Portal)                       │
│  ├── Auth0 OIDC login for Exygy/CiviForm staff              │
│  ├── Dashboard: create, extend, delete sandboxes             │
│  └── PIN gate: prospects access sandboxes via 6-digit PIN    │
├──────────────────────────────────────────────────────────────┤
│  *.sandbox.civiform.dev (Sandbox Instances)                  │
│  ├── burlington-vt.sandbox.civiform.dev                      │
│  ├── santa-cruz.sandbox.civiform.dev                         │
│  └── Each is a full CiviForm instance on ECS Fargate         │
└──────────────────────────────────────────────────────────────┘
```

**Key design decisions:**
- **AWS only** — ECS Fargate + RDS Postgres. No GCP.
- **Wildcard DNS** — `*.sandbox.civiform.dev` points to a shared ALB. No per-sandbox DNS propagation delay.
- **Cookie-based PIN gate** — ALB listener rules enforce access. No application-level auth middleware on CiviForm itself.
- **Terraform per sandbox** — Each sandbox is a separate Terraform workspace with its own state file in S3.

---

## Architecture

```
                    ┌─────────────────┐
                    │   Cloudflare    │
                    │  *.sandbox.     │
                    │  civiform.dev   │
                    └────────┬────────┘
                             │ CNAME → ALB
                    ┌────────▼────────┐
                    │  AWS ALB (443)  │
                    │  Wildcard TLS   │
                    │  TLS 1.3        │
                    └──┬──────────┬───┘
        Priority 100   │          │   Priority N / N+1
     ┌─────────────────▼──┐  ┌───▼──────────────────────┐
     │  Builder Portal    │  │  Sandbox CiviForm         │
     │  (ECS Fargate)     │  │  (ECS Fargate per sandbox)│
     │  Port 9000         │  │  Port 9000                │
     │  Play Framework    │  │  civiform/civiform image   │
     └──────────┬─────────┘  └──────────┬────────────────┘
                │                       │
         ┌──────▼───────────────────────▼──────┐
         │  Amazon RDS PostgreSQL 16.3         │
         │  civiform_sandbox (builder metadata) │
         │  sb_<id> (one DB per sandbox)        │
         └─────────────────────────────────────┘
```

### AWS Resources (Platform Level)

| Resource | Name / ID | Purpose |
|---|---|---|
| VPC | `civiform-sandbox-vpc` (`10.100.0.0/16`) | Isolated network |
| Subnets | 2 public + 2 private across 2 AZs | ALB in public, ECS + RDS in private |
| NAT Gateway | In public subnet | Outbound internet for private subnets |
| ALB | `civiform-sandbox-alb` | Shared load balancer, wildcard TLS |
| ACM Cert | `*.sandbox.civiform.dev` | Wildcard TLS certificate |
| ECS Cluster | `civiform-sandbox-cluster` | FARGATE + FARGATE_SPOT |
| ECR | `civiform-sandbox-builder` | Builder container images |
| RDS | `civiform-sandbox-postgres` | PostgreSQL 16.3, `db.t3.micro` |
| KMS Key | `alias/civiform-sandbox-secrets` | Encrypts all secrets + S3 |
| S3 | `civiform-sandbox-tfstate` | Terraform state storage |

### AWS Resources (Per Sandbox)

| Resource | Naming Pattern | Purpose |
|---|---|---|
| Database | `sb_<8hex>` | Dedicated Postgres DB |
| DB User | `sb_<8hex>` | DB owner role |
| ECS Service | `<sandbox_id>-civiform` | CiviForm Fargate task |
| Target Group | `<app_prefix>-https-9000` | ALB routing target |
| S3 (private) | `civiform-sb-files-<id>-<account>` | Applicant file uploads |
| S3 (public) | `civiform-sb-public-<id>-<account>` | Program images |
| Secrets (×4) | `civiform-sandbox_<id>_*` | DB creds, app secret, API salt |
| ALB Rules (×2) | Priority N (allow), N+1 (redirect) | Cookie-gated access |

---

## Prerequisites

Before first deploy, ensure you have:

- [ ] **AWS account** with admin-level access (or scoped IAM for ECS, RDS, ALB, Route53, Secrets Manager, KMS, S3, IAM)
- [ ] **Terraform** ≥ 1.10.0 installed locally
- [ ] **AWS CLI v2** configured with appropriate credentials
- [ ] **Docker** (for building the builder image)
- [ ] **Cloudflare access** to the `civiform.dev` DNS zone (or whoever manages DNS)
- [ ] **Auth0 tenant** `civiform-sandbox-builder` (or create a new one)

---

## Bootstrap: One-Time AWS Setup

> [!IMPORTANT]
> This is run **once per AWS account**, not per sandbox. It creates the S3 bucket for Terraform state.

```bash
cd terraform/bootstrap

# Review variables (defaults are fine for production)
cat variables.tf

# Initialize and apply (state is LOCAL for bootstrap only)
terraform init
terraform apply
```

This creates:
- S3 bucket `civiform-sandbox-tfstate` (versioned, encrypted, `prevent_destroy = true`)
- KMS key `alias/civiform-sandbox-tfstate` for state encryption

> [!CAUTION]
> The bootstrap stack stores state locally. Do NOT delete the `terraform/bootstrap/terraform.tfstate` file. If lost, import the resources manually.

---

## Platform Infrastructure (Terraform)

The platform stack creates all shared infrastructure: VPC, ALB, RDS, ECS cluster, IAM roles, and the builder application itself.

### Deploy

```bash
cd terraform

terraform init \
  -backend-config="bucket=civiform-sandbox-tfstate" \
  -backend-config="key=platform/terraform.tfstate" \
  -backend-config="region=us-east-1" \
  -backend-config="use_lockfile=true"

# Review the plan
terraform plan

# Apply
terraform apply
```

### Key Variables (`terraform/variables.tf`)

| Variable | Default | Notes |
|---|---|---|
| `aws_region` | `us-east-1` | All resources in this region |
| `domain` | `sandbox.civiform.dev` | Base domain for portal + sandboxes |
| `civiform_image` | `civiform/civiform` | CiviForm Docker image |
| `db_instance_class` | `db.t3.micro` | RDS instance size — upgrade for production load |
| `vpc_cidr` | `10.100.0.0/16` | VPC address space |

### Capture Platform Outputs

After applying, the builder needs the platform outputs at runtime:

```bash
terraform output -json > platform-outputs.json
```

This file is baked into the builder container image (at `/app/platform-outputs.json`) or injected via the `PLATFORM_OUTPUTS_JSON` environment variable in the ECS task definition.

---

## DNS & SSL Configuration

The system uses **wildcard DNS** so that new sandboxes are instantly reachable — no per-sandbox DNS changes required.

### Cloudflare Setup (one-time)

Add these records to the `civiform.dev` zone in Cloudflare:

| Type | Name | Target | Proxy |
|---|---|---|---|
| CNAME | `sandbox` | `civiform-sandbox-alb-*.us-east-1.elb.amazonaws.com` | DNS Only (gray cloud) |
| CNAME | `*.sandbox` | `civiform-sandbox-alb-*.us-east-1.elb.amazonaws.com` | DNS Only (gray cloud) |

> [!WARNING]
> **Do NOT enable Cloudflare proxy (orange cloud)** — the ALB handles TLS termination with the ACM wildcard cert. Cloudflare proxy would cause certificate mismatch errors.

### ACM Certificate Validation

The Terraform `aws_acm_certificate` resource requires DNS validation. After `terraform apply`:

```bash
terraform output acm_validation_records
```

Add the CNAME validation record to Cloudflare. ACM will validate within ~5 minutes.

---

## Auth0 Setup

The builder portal uses Auth0 OIDC for login. Only users with the **"Sandbox builder users"** role can access the portal.

### 1. Create Auth0 Application

- **Type**: Regular Web Application
- **Grant Types**: Authorization Code
- **Allowed Callback URLs**: `https://sandbox.civiform.dev/callback`
- **Allowed Logout URLs**: `https://sandbox.civiform.dev/login`

### 2. Create the "Sandbox builder users" Role

In the Auth0 Dashboard:
1. Go to **User Management → Roles**
2. Create role: `Sandbox builder users`
3. Assign the role to authorized Exygy / CiviForm staff

### 3. Add Post-Login Action

In **Actions → Flows → Login**, add a custom action:

```javascript
exports.onExecutePostLogin = async (event, api) => {
  const allowedRoles = ['Sandbox builder users'];
  const userRoles = event.authorization?.roles || [];
  const hasRequiredRole = userRoles.some(role => allowedRoles.includes(role));
  if (!hasRequiredRole) {
    api.access.deny('Access denied: You do not have the required role to log in.');
  }
};
```

### 4. Set Environment Variables

| Variable | Value |
|---|---|
| `AUTH0_DOMAIN` | `civiform-sandbox-builder.us.auth0.com` |
| `AUTH0_CLIENT_ID` | *(from Auth0 dashboard)* |
| `AUTH0_CLIENT_SECRET` | *(stored in Secrets Manager: `civiform-sandbox/auth0-client-secret`)* |

### Fallback: Local Development Password

When Auth0 is not configured, the portal falls back to a simple email/password login:
- **Email**: `admin@civiform.dev`
- **Password**: value of `DEMO_PORTAL_PASSWORD` env var (default: `demo`)

---

## Deploying the Builder Application

### Build & Push to ECR

```bash
# From repo root:
./bin/deploy-prod latest
```

This script:
1. Builds a `linux/amd64` Docker image from `prod.Dockerfile`
2. Authenticates to ECR via `aws ecr get-login-password`
3. Pushes to `<account>.dkr.ecr.us-east-1.amazonaws.com/civiform-sandbox-builder:latest`
4. Triggers ECS rolling deployment via `aws ecs update-service --force-new-deployment`

> [!WARNING]
> **Apple Silicon (M1/M2/M3) Macs**: The production image must be `linux/amd64` for ECS Fargate.
> Cross-platform builds on arm64 Macs can produce corrupt layers that cause
> `CannotPullContainerError` on Fargate. See [Troubleshooting](#cannotpullcontainererror-on-ecs-fargate)
> below. **Strongly recommended**: Install Docker Buildx (`brew install docker-buildx`) for
> reliable cross-platform builds.

### Prerequisites for Deploying from Apple Silicon

Docker Buildx handles cross-platform builds correctly. The legacy Docker builder (`docker build`)
has known issues with `--platform linux/amd64` on arm64 hosts:

```bash
# Install buildx (one-time)
brew install docker-buildx

# Verify
docker buildx version
```

If you cannot install Buildx, pre-pull the base images for `linux/amd64` before building:

```bash
docker pull --platform linux/amd64 eclipse-temurin:21-jdk-alpine
docker pull --platform linux/amd64 eclipse-temurin:21-jre-alpine
./bin/deploy-prod latest
```

> [!CAUTION]
> **Never use `--no-cache` with the legacy Docker builder** when cross-building `linux/amd64`
> on an arm64 host. The legacy builder creates intermediate containers for the host architecture,
> then fails at `COPY` steps with: `image was found but does not provide the specified platform (linux/amd64)`.
> This is a fundamental limitation of the legacy builder — only Buildx can do clean cross-platform
> builds from scratch.

### What's in the Production Image

The `prod.Dockerfile` is a two-stage build:

| Stage | Base Image | Installs |
|---|---|---|
| `builder` | `eclipse-temurin:21-jdk-alpine` | SBT, npm, builds Play app + Vite frontend |
| `runner` | `eclipse-temurin:21-jre-alpine` | Terraform 1.15.8, Play dist, sandbox Terraform modules |

The runner image contains:
- Play Framework application (compiled JAR)
- Terraform binary (statically linked)
- `terraform/sandbox/` module directory (copied to `/app/terraform/sandbox`)

### Verify Deployment

```bash
# Check ECS service status
aws ecs describe-services \
  --cluster civiform-sandbox-cluster \
  --services civiform-sandbox-builder \
  --query 'services[0].{status:status,running:runningCount,desired:desiredCount}'

# Monitor rollout progress
aws ecs describe-services \
  --cluster civiform-sandbox-cluster \
  --services civiform-sandbox-builder \
  --query 'services[0].deployments[*].{status:status,running:runningCount,desired:desiredCount,rollout:rolloutState}' \
  --output table

# Check recent events (look for errors)
aws ecs describe-services \
  --cluster civiform-sandbox-cluster \
  --services civiform-sandbox-builder \
  --query 'services[0].events[:5].[createdAt,message]' \
  --output text

# Check health endpoint
curl -s https://sandbox.civiform.dev/health
```

A healthy rollout shows:
1. New deployment: `PRIMARY`, `IN_PROGRESS`, running 0 → 1
2. Old deployment: `ACTIVE`, `COMPLETED`, running 1 → 0
3. Once the new task passes the ALB health check (`/health`), old task drains (typically 2-5 min total)

---

## Environment Variables Reference

### Builder Application

| Variable | Default | Required | Description |
|---|---|---|---|
| `APPLICATION_SECRET` | `changeme` | **Yes (prod)** | Play session signing key |
| `APP_BASE_URL` | `http://localhost:9001` | **Yes (prod)** | Portal base URL (`https://sandbox.civiform.dev`) |
| `DB_JDBC_URL` | `jdbc:postgresql://localhost:5432/sandbox_builder` | **Yes** | Builder metadata DB |
| `DB_USER` | `postgres` | **Yes** | Builder DB user |
| `DB_PASSWORD` | `example` | **Yes** | Builder DB password |
| `SANDBOX_RUNTIME` | `docker` | **Yes (prod)** | `docker` (local) or `terraform` (AWS) |
| `SANDBOX_DOMAIN` | `sandbox.civiform.dev` | No | Base domain for sandboxes |
| `AUTH0_DOMAIN` | *(empty)* | For prod | Auth0 tenant domain |
| `AUTH0_CLIENT_ID` | *(empty)* | For prod | Auth0 client ID |
| `AUTH0_CLIENT_SECRET` | *(empty)* | For prod | Auth0 client secret |
| `PIN_GATE_MAX_ATTEMPTS` | `5` | No | Failed PIN attempts before rate limit |

### Terraform Runtime (only when `SANDBOX_RUNTIME=terraform`)

| Variable | Default | Description |
|---|---|---|
| `AWS_REGION` | `us-east-1` | AWS region |
| `TERRAFORM_BINARY` | `terraform` | Path to Terraform CLI |
| `TERRAFORM_MODULE_DIR` | `/app/terraform/sandbox` | Sandbox Terraform module template |
| `TERRAFORM_STATE_BUCKET` | `civiform-sandbox-tfstate` | S3 bucket for Terraform state |
| `PLATFORM_OUTPUTS_JSON` | *(empty)* | Inline JSON of platform Terraform outputs |
| `PLATFORM_OUTPUTS_FILE` | `/app/platform-outputs.json` | File path alternative to inline JSON |
| `CIVIFORM_IMAGE_TAG` | `latest` | CiviForm Docker image tag |
| `TERRAFORM_TIMEOUT_MINUTES` | `45` | Max time for `terraform apply/destroy` |
| `TERRAFORM_READINESS_TIMEOUT_MINUTES` | `10` | Max wait for CiviForm `/programs` to return 200 |
| `RDS_ADMIN_USER` | `sandbox_master` | RDS master username |
| `RDS_ADMIN_PASSWORD` | *(empty)* | RDS master password |
| `RDS_ADMIN_HOST` | *(empty)* | Override for local dev with `./bin/rds-tunnel` |

### Docker Runtime (only when `SANDBOX_RUNTIME=docker`)

| Variable | Default | Description |
|---|---|---|
| `DOCKER_SOCKET_PATH` | `unix:///var/run/docker.sock` | Docker daemon socket |
| `SANDBOX_DB_HOST` | `host.docker.internal` | How CiviForm containers reach Postgres |

---

## Per-Sandbox Lifecycle

### Creation Flow

```
POST /sandboxes (form submit)
    │
    ▼
1. Generate IDs
   ├── sandboxId: "sb-" + UUID[0..8]
   ├── databaseName: "sb_<8hex>"
   ├── accessToken: UUID (for ALB cookie)
   └── listenerPriority: nextval(sequence)
    │
    ▼
2. Persist row (status = PROVISIONING)
   Return 303 redirect immediately
    │
    ▼
3. Background thread:
   a. CREATE USER + CREATE DATABASE on RDS
   b. Install pg_trgm + btree_gin extensions
   c. Prepare temp Terraform workspace
   d. terraform init (S3 backend: sandboxes/<id>/terraform.tfstate)
   e. terraform apply (creates ECS service, ALB rules, secrets, S3 buckets)
   f. Extract outputs (sandbox URL, ARNs)
   g. Poll GET https://<subdomain>.sandbox.civiform.dev/programs
      until HTTP 200 (timeout: 10 min, interval: 10s)
   h. Mark status = RUNNING
   i. Cleanup temp workspace
```

### Deletion Flow

```
POST /sandboxes/:id/delete
    │
    ▼
1. Mark status = DELETING
    │
    ▼
2. Background thread:
   a. terraform init + terraform destroy
   b. DROP DATABASE IF EXISTS <db> WITH (FORCE)
   c. DROP USER IF EXISTS <db>
   d. softDelete(): status = DELETED, scrub PIN + accessToken + adminEmail
```

### Re-Provisioning Flow

```
POST /sandboxes/:id/reprovision
    │
    ▼
1. Guard: Only allowed when status is RUNNING or FAILED
2. Mark status = PROVISIONING
3. Re-run Terraform apply with updated env vars
4. Poll for readiness → mark RUNNING
```

### Extension Flow

```
POST /sandboxes/:id/extend
    │
    ▼
1. Update expires_at in database
2. Trigger reprovision (to update DEMO_BANNER_EXPIRATION_DATE env var)
```

---

## Database Schema

The builder metadata database (`civiform_sandbox` / `sandbox_builder`) contains one table:

```sql
CREATE TABLE sandbox_instances (
  id                   VARCHAR(64)   PRIMARY KEY,     -- sb-a1b2c3d4
  city_name            VARCHAR(255)  NOT NULL,
  subdomain            VARCHAR(63),                   -- DNS label
  civiform_version     VARCHAR(64)   DEFAULT 'latest',
  status               VARCHAR(32)   DEFAULT 'PROVISIONING',
  url                  VARCHAR(512)  DEFAULT '',
  admin_email          VARCHAR(255)  DEFAULT '',
  pin                  VARCHAR(6)    NOT NULL,         -- 6-digit PIN
  access_token         VARCHAR(64)   DEFAULT '',       -- ALB cookie secret
  container_id         VARCHAR(128),                   -- ECS task def ARN
  host_port            INTEGER       NOT NULL,
  database_name        VARCHAR(128)  NOT NULL,         -- sb_a1b2c3d4
  target_group_arn     VARCHAR(512),
  listener_rule_arn    VARCHAR(512),
  listener_priority    INTEGER,
  google_analytics_id  VARCHAR(64),
  google_analytics_url VARCHAR(512),
  created_at           TIMESTAMPTZ   DEFAULT NOW(),
  expires_at           TIMESTAMPTZ   NOT NULL,
  deleted_at           TIMESTAMPTZ                     -- NULL = active
);
```

### Status Enum

| Status | Meaning |
|---|---|
| `PROVISIONING` | Terraform apply in progress |
| `RUNNING` | CiviForm healthy and accessible |
| `FAILED` | Provisioning failed (retryable via reprovision) |
| `DELETING` | Terraform destroy in progress |
| `DELETED` | Soft-deleted tombstone (PIN/token scrubbed) |
| `DELETE_FAILED` | Destroy failed (requires manual cleanup) |
| `STOPPED` | Manually stopped |
| `DESTROYED` | Fully removed |

### Sequences

| Sequence | Range | Purpose |
|---|---|---|
| `sandbox_port_seq` | 10000–11000, CYCLE | Docker host port allocation (local dev only) |
| `sandbox_listener_priority_seq` | 1000–50000, +2, NO CYCLE | ALB rule priorities (pairs: N allow, N+1 redirect) |

---

## Secrets Manager Pattern

All secrets encrypted with `alias/civiform-sandbox-secrets` KMS key. All per-sandbox secrets use `recovery_window_in_days = 0` for immediate re-creation.

### Platform Secrets

| Secret Name | Content |
|---|---|
| `civiform-sandbox/rds-master-password` | JSON: `{username, password, host, port, dbname}` |
| `civiform-sandbox/builder-app-secret` | 48-char random (Play `APPLICATION_SECRET`) |
| `civiform-sandbox/auth0-client-secret` | Auth0 client secret |
| `civiform-sandbox_shared_ADFS_SECRET` | `"placeholder-not-used-sandboxes-use-fake-idp"` |
| `civiform-sandbox_shared_ADFS_CLIENT_ID` | *(same placeholder)* |
| `civiform-sandbox_shared_APPLICANT_OIDC_*` | *(same placeholder — 2 secrets)* |
| `civiform-sandbox_shared_ADMIN_OIDC_*` | *(same placeholder — 2 secrets)* |
| `civiform-sandbox_shared_ESRI_ARCGIS_API_TOKEN` | *(same placeholder)* |

> [!NOTE]
> The 7 placeholder secrets satisfy CiviForm's upstream `cloud-deploy-infra` task definition, which requires ARNs for OIDC/ADFS/ESRI secrets even when sandboxes use `FAKE_IDP` instead.

### Per-Sandbox Secrets (naming: `civiform-sandbox_<sandbox_id>_<name>`)

| Secret | Generated By | Content |
|---|---|---|
| `_db_username` | Java (derived from sandbox ID) | PostgreSQL role name |
| `_db_password` | Java (32-char random) | PostgreSQL password |
| `_app_secret_key` | Terraform (64-char random) | Play `SECRET_KEY` |
| `_api_secret_salt` | Terraform (64-char random) | CiviForm API salt |

---

## Monitoring & Logs

### CloudWatch Log Groups

| Log Group | Retention | Contents |
|---|---|---|
| `/ecs/civiform-sandbox-builder` | 30 days | Builder application logs |
| `/ecs/civiform-sandbox` | 30 days | All sandbox CiviForm container logs |

### Health Checks

| Endpoint | Expected |
|---|---|
| `GET https://sandbox.civiform.dev/health` | `200 OK` — builder is running |
| `GET https://sandbox.civiform.dev/ready` | `200 OK` — builder + DB connected |

### Key Metrics to Watch

- **ECS**: Running task count per service, CPU/memory utilization
- **RDS**: Connection count, freeable memory, CPU utilization
- **ALB**: 5XX error rate, target response time, healthy host count
- **Secrets Manager**: API throttling (shouldn't occur at normal scale)

---

## Local Development

### Quick Start

```bash
# Start Postgres + builder with hot reload
./bin/run-dev

# Portal:    http://localhost:9001
# Login:     admin@civiform.dev / demo
# Health:    http://localhost:9001/health
```

### Local Dev with AWS RDS (Terraform runtime)

For testing Terraform provisioning locally against real AWS:

```bash
# 1. Start SSM tunnel to RDS (in a separate terminal)
./bin/rds-tunnel

# 2. Set overrides and start
export SANDBOX_RUNTIME=terraform
export RDS_ADMIN_HOST=host.docker.internal
export RDS_ADMIN_PORT=15432
./bin/run-dev
```

### Running Tests

```bash
./bin/sbt test              # 36+ unit tests (no Docker/AWS needed)
./bin/run-browser-tests     # Playwright E2E tests
```

### CI

GitHub Actions workflow `.github/workflows/build-and-test.yml` runs on every push/PR to `main`:
- Java 21 + SBT
- `sbt Test/compile` + `sbt test`

---

## Troubleshooting

### `CannotPullContainerError` on ECS Fargate

**Symptom**: After deploying from an Apple Silicon Mac, ECS events show:
```
CannotPullContainerError: ref pull has been retried 1 time(s):
wrong diff id "sha256:..." calculated on extraction "sha256:..."
```

The new task stays at `running: 0` and ECS keeps retrying. The old task continues serving traffic.

**Root cause**: The legacy Docker builder (`docker build --platform linux/amd64`) on arm64 Macs
uses QEMU emulation which can produce layers with mismatched checksums. ECR accepts the push
but Fargate rejects the layers during pull when the checksums don't match.

**Fix (recommended)**:
```bash
# Install Docker Buildx for reliable cross-platform builds
brew install docker-buildx

# Rebuild and deploy
./bin/deploy-prod latest
```

**Fix (without Buildx)**:
```bash
# 1. Pre-pull base images for the correct platform
docker pull --platform linux/amd64 eclipse-temurin:21-jdk-alpine
docker pull --platform linux/amd64 eclipse-temurin:21-jre-alpine

# 2. Rebuild (uses the correctly-pulled base layers)
./bin/deploy-prod latest
```

**Fix (nuclear option — if above doesn't work)**:
```bash
# Remove all cached images and rebuild from scratch
docker system prune -af
docker pull --platform linux/amd64 eclipse-temurin:21-jdk-alpine
docker pull --platform linux/amd64 eclipse-temurin:21-jre-alpine
./bin/deploy-prod latest
```

> [!IMPORTANT]
> **Do NOT use `docker build --no-cache --platform linux/amd64`** with the legacy builder.
> It will fail at `COPY` steps with `does not provide the specified platform (linux/amd64)`.
> This is a known limitation — only Buildx supports clean cross-platform rebuilds.

### Sandbox stuck in PROVISIONING

1. Check builder logs: `aws logs tail /ecs/civiform-sandbox-builder --follow`
2. Common cause: Terraform state lock. Check S3 for `.tflock` files under `sandboxes/<id>/`
3. If Terraform apply failed mid-way, the sandbox row shows `FAILED`. Use **Reprovision** to retry.

### Sandbox shows FAILED after reprovision

The `reconcileDatabase()` method rotates the DB password before `terraform apply`. If apply fails, the secret in Secrets Manager holds the old password while the DB has the new one. **Retry reprovision** — it will rotate again and succeed.

### DELETE_FAILED

Terraform destroy failed (e.g., S3 bucket not empty, resource in use).

```bash
# Manual cleanup:
# 1. Find the sandbox's Terraform state
aws s3 ls s3://civiform-sandbox-tfstate/sandboxes/<sandbox-id>/

# 2. Run destroy manually
cd terraform/sandbox
terraform init \
  -backend-config="bucket=civiform-sandbox-tfstate" \
  -backend-config="key=sandboxes/<sandbox-id>/terraform.tfstate" \
  -backend-config="region=us-east-1"
terraform destroy -var-file=<reconstruct vars>

# 3. Drop the database manually
psql -h <rds-endpoint> -U sandbox_master -d postgres \
  -c "DROP DATABASE IF EXISTS sb_<hex> WITH (FORCE);"
psql -h <rds-endpoint> -U sandbox_master -d postgres \
  -c "DROP USER IF EXISTS sb_<hex>;"
```

### Stale PROVISIONING/DELETING on startup

The builder automatically calls `SandboxRepository.reconcileStaleStatuses()` at startup, which transitions:
- `PROVISIONING` → `FAILED`
- `DELETING` → `DELETE_FAILED`

This handles cases where the builder restarted mid-operation.

### Prospect can't access sandbox (302 redirect loop)

The ALB uses cookie-based routing. Check:
1. Cookie `sb_access_<sandbox_id_underscored>=<access_token>` is being set correctly
2. The ALB listener rule priority is correct (allow rule must be lower number than redirect rule)
3. The sandbox ECS service has at least one healthy target in the target group

### RDS connection limit

Each sandbox CiviForm instance opens its own connection pool. With many sandboxes:
- Check `max_connections` on the RDS instance
- Consider upgrading from `db.t3.micro` if running >5 concurrent sandboxes

---

## Operational Runbook

### Add a new user to the portal

1. Go to **Auth0 Dashboard → User Management → Users**
2. Create or invite the user
3. Assign the **"Sandbox builder users"** role
4. User can now log in at `https://sandbox.civiform.dev`

### Extend a sandbox expiry

1. Log into the portal → find the sandbox → click **Extend**
2. Enter number of days → submit
3. This triggers a reprovision to update the `DEMO_BANNER_EXPIRATION_DATE` env var

### Manually delete a sandbox

1. Portal → sandbox row → **Delete** button
2. If deletion fails (status `DELETE_FAILED`), see [Troubleshooting](#delete_failed)

### Update CiviForm image version

1. Change `CIVIFORM_IMAGE_TAG` in the ECS task definition (or `terraform/variables.tf`)
2. New sandboxes will use the updated tag
3. Existing sandboxes are **not** auto-upgraded — use **Reprovision** to update individual sandboxes

### Rotate RDS master password

1. Generate new password
2. Update Secrets Manager: `civiform-sandbox/rds-master-password`
3. Update RDS: `aws rds modify-db-instance --master-user-password <new>`
4. Restart builder ECS service: `aws ecs update-service --force-new-deployment`

### Full platform tear-down

> [!CAUTION]
> This destroys **all sandboxes and all data**. Only do this for decommissioning.

```bash
# 1. Delete all sandboxes via portal first (to clean up per-sandbox resources)

# 2. Destroy platform stack
cd terraform
terraform destroy

# 3. Destroy bootstrap (removes state bucket — cannot be undone)
cd terraform/bootstrap
terraform destroy
```

---

## Appendix: Terraform File Map

| File | Layer | Purpose |
|---|---|---|
| `terraform/bootstrap/main.tf` | Bootstrap | S3 state bucket + KMS key (run once) |
| `terraform/main.tf` | Platform | Provider config, S3 backend |
| `terraform/vpc.tf` | Platform | VPC, subnets, IGW, NAT, security groups |
| `terraform/rds.tf` | Platform | Shared RDS PostgreSQL 16.3 |
| `terraform/alb.tf` | Platform | ALB, ACM wildcard cert, HTTP→HTTPS redirect |
| `terraform/ecs.tf` | Platform | ECS cluster, builder task/service, IAM roles, ECR |
| `terraform/kms.tf` | Platform | Shared KMS key for secrets |
| `terraform/placeholder_secrets.tf` | Platform | 7 inert IdP placeholder secrets |
| `terraform/sandbox/main.tf` | Per-Sandbox | CiviForm ECS service, ALB rules (via upstream modules) |
| `terraform/sandbox/secrets.tf` | Per-Sandbox | 4 sandbox-specific secrets |
| `terraform/sandbox/filestorage.tf` | Per-Sandbox | 2 S3 buckets (private + public) |
| `terraform/sandbox/outputs.tf` | Per-Sandbox | URL, ARNs parsed by Java after apply |
