---
name: civiform-deploy-reference
description: >-
  Reference guide for the current manual CiviForm demo deployment process
  (from the Exygy wiki). Use this skill when working on the provisioning
  engine (SandboxService), Terraform wiring, or any Sprint 2+ AWS infrastructure
  work. Shows exactly what cf-sandbox-builder must automate.
---

# CiviForm Demo Deployment — Current Manual Process

Source: https://github.com/civiform/civiform/wiki/Exygy-Admin-Info#setting-up-new-demo-site

> **Purpose of this document**: Every step below is a manual task today.
> `cf-sandbox-builder` exists to automate all of them. Each step is annotated
> with which sprint task owns its automation.

---

## Current Manual Steps → Automated Equivalents

### Step 1 — Create AWS Sub-Account
**Today**: Manually create account in AWS Organizations, set email to
`civiform-instances+<instance-name>@googlegroups.com`, move into `civiform-dev` folder.

**Automation target**: Sprint 2 (BE-2: ECS Fargate provisioning)
**Approach**: Use a **single shared AWS account** for all sandbox instances (not one account per sandbox).
The `SandboxService` uses IAM roles + ECS task isolation instead of account-per-instance.

> 🔵 Open question from wiki: "Do we need one account per instance? Can we have a single service account?"
> **Decision for cf-sandbox-builder**: Single account + IAM task roles. Account-per-instance is too slow for < 30 min provisioning.

---

### Step 2 — Assume OrganizationAccountAccessRole
**Today**: Manual AWS console role switch via browser bookmark.

**Automation target**: Sprint 2  
**Approach**: ECS Task Role (`CiviformControlPlaneTaskRole`) + STS assume-role for sub-accounts if needed.
Never hardcode `AWS_ACCESS_KEY_ID`.

---

### Step 3 — Request SSL Certificate
**Today**: Manually request ACM cert for the full FQDN, add CNAME to Cloudflare, wait for "Issued" status.

**Automation target**: Sprint 2  
**Approach**: Pre-provisioned **wildcard cert** `*.sandbox.civiform.org` (or `*.demos.civiform.dev`) via AWS ACM.
One cert covers all sandboxes — no per-sandbox cert request.

> 🔵 Open question from wiki: "Can we have a wildcard certificate? Could we use terraform to automate cert request + CNAME update?"
> **Decision**: Yes — wildcard cert is confirmed in TDD. Cloudflare CNAME automation via Terraform.

---

### Step 4 — Copy & Modify Config File
**Today**: Manually copy the most recent `.sh` config from `civiform-staging-deploy` repo and edit:
- `ACCOUNT_ID`
- `SENDER_EMAIL_ADDRESS`
- `STAGING_PROGRAM_ADMIN_NOTIFICATION_MAILING_LIST`
- `STAGING_TI_NOTIFICATION_MAILING_LIST`
- `STAGING_APPLICANT_NOTIFICATION_MAILING_LIST`
- `BASE_URL`
- `STAGING_HOSTNAME`
- `APP_PREFIX`
- `SSL_CERTIFICATE_ARN`

**Automation target**: Sprint 2–3  
**Approach**: `SandboxService.createSandbox(cityName)` generates all config values programmatically.
Store in `sandbox_instances` DB table — not in `.sh` files.

> 🔵 Open question from wiki: "We'd store all instance configs in a database instead of individual sh files."
> **Decision**: Yes — this is the `sandbox_instances` table (BE-1 in Sprint 1).

---

### Step 5 — Configure AWS CLI Profile
**Today**: Manually edit `~/.aws/credentials` and `~/.aws/config`, set `AWS_PROFILE` env var.

**Automation target**: Sprint 2  
**Approach**: ECS Task Role. No local AWS CLI config needed — IAM role attached to Fargate task.

---

### Step 6 — Run `bin/setup-instance`
**Today**: Clone `civiform-staging-deploy`, run `bin/setup-instance` interactively. When prompted:
- ADFS secrets → `none`
- ESRI secrets → `none`
- OIDC → use Auth0 client ID + secret from CiviForm demo Auth0 application

**Automation target**: Sprint 2–3 (BE-3 → BE for Docker socket in Sprint 1, Fargate in Sprint 2)  
**Approach**: `DockerSandboxService.createSandbox()` calls the equivalent of `setup-instance` non-interactively.
Secrets injected via AWS Secrets Manager (no interactive prompts).
OIDC pre-configured using `FAKE_IDP` for sandboxes (not Auth0).

---

### Step 7 — Add Cloudflare DNS Record
**Today**: Manually add CNAME in Cloudflare pointing to ALB after deployment.

**Automation target**: Sprint 2  
**Approach**: Terraform Cloudflare provider creates DNS record automatically.
Pattern: `burlington-vt.sandbox.civiform.org → <ALB-hostname>`

> 🔵 Open question from wiki: "Could this be automated with terraform?"
> **Decision**: Yes — Cloudflare Terraform provider is the path.

---

### Step 8 — Auth0 Setup
**Today**: Follow "Add Auth0 to demo site" wiki instructions manually.

**Automation target**: Sprint 2–3  
**Approach**: Sandboxes use CiviForm's built-in `FAKE_IDP` (no Auth0 needed for MVP demos).
Auth0 Terraform connector exists if needed later.

> 🔵 Open question from wiki: "There's an Auth0 terraform connector, maybe we could use it."

---

### Step 9 — Set Feature Flags & CiviForm Config
**Today**: Manually set inside running CiviForm instance:
```
SHOW_NOT_PRODUCTION_BANNER_ENABLED = true
STAGING_DISABLE_DEMO_MODE_LOGINS = false
WHITELABEL_CIVIC_ENTITY_FULL_NAME = "Burlington, Vermont"
WHITELABEL_CIVIC_ENTITY_SHORT_NAME = "Burlington"
```

**Automation target**: Sprint 2 (city name injection) / Sprint 3 (demo banner)  
**Approach**: Pass as environment variables to ECS task at container launch time.
In Sprint 1 Docker socket MVP: pass as `--env` flags to `docker run`.

> 🔵 Open question from wiki: "We could set those flags directly in the database."
> **Decision**: Env vars at container launch is simpler and avoids DB migration risk.

---

### Step 10 — Seed Programs & Questions
**Today**: Manually seed programs and questions inside the running CiviForm instance, then publish them.

**Automation target**: Sprint 3–4  
**Approach**: `SeedingEngine` calls `ProgramMigrationService.saveImportedProgram()` with pre-authored
program JSON templates. Sprint 1 uses CiviForm's default demo data.

---

### Step 11 — CI/CD Auto-Deploy Setup
**Today**: Add instance to `aws_deploy_all_demos.yaml` GitHub Action, create environment in
`civiform-staging-deploy`, set `CONFIG_FILE`, `DEMO_NAME`, `ROLE_ARN` secrets.

**Automation target**: Sprint 2+  
**Approach**: `cf-sandbox-builder` manages its own deployment — no per-sandbox GitHub environment needed.
Teardown is handled by EventBridge + Lambda (not GitHub Actions).

> 🔵 Open question from wiki: "Do we need automatic deployment? How about manual redeployment?"
> **Decision**: cf-sandbox-builder provides manual redeployment via the Creator Portal UI.
> Automatic teardown via EventBridge cron (not GH Actions).

---

## Key CiviForm Config Variables (Set Per Sandbox at Launch)

| Variable | Sprint | Description |
|---|---|---|
| `WHITELABEL_CIVIC_ENTITY_SHORT_NAME` | S2 | City short name in CiviForm header |
| `WHITELABEL_CIVIC_ENTITY_FULL_NAME` | S2 | City full name |
| `SHOW_NOT_PRODUCTION_BANNER_ENABLED` | S2–S3 | Shows "Not Production" banner |
| `STAGING_DISABLE_DEMO_MODE_LOGINS` | S1 | `false` = enable FAKE_IDP login |
| `BASE_URL` | S2 | `https://burlington-vt.sandbox.civiform.org` |
| `STAGING_HOSTNAME` | S2 | Subdomain hostname |
| `APP_PREFIX` | S2 | Used for S3 bucket + DynamoDB lock naming |
| `SSL_CERTIFICATE_ARN` | S2 | Wildcard cert ARN (pre-provisioned, shared) |

---

## Auth0 vs FAKE_IDP Decision for Sandboxes

- **FAKE_IDP**: Use for all MVP sandboxes. Built into CiviForm. Allows 1-click role switching (Resident / Admin).
  Set `STAGING_DISABLE_DEMO_MODE_LOGINS=false`.
- **Auth0**: Not needed for sandboxes. Would add per-sandbox Auth0 app complexity with no demo benefit.
- **PIN Gate**: Sits in front of the sandbox URL — validates access before FAKE_IDP login is shown.

---

## Repo References

- `civiform-staging-deploy`: https://github.com/civiform/civiform-staging-deploy
  - Contains config `.sh` files per demo instance (what we're replacing with DB records)
  - `bin/setup-instance`: the script that runs Terraform (what we're automating)
  - `aws_deploy_all_demos.yaml`: GH Action for auto-redeploy (what we're replacing with EventBridge)
- CiviForm main repo: https://github.com/civiform/civiform
