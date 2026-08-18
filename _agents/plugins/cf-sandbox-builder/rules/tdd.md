---
title: cf-sandbox-builder — Technical Design Document (TDD)
trigger: always_on
---

# Technical Design Document: CiviForm Production Sandbox System

**Authors**: Tom George, Lesley Katzen, Salvador Guerrero Ramos | Jul 31, 2026  
**Status**: WIP  
**PRD Reference**: Instant Sandbox Provisioner — PRD Analysis & 4-Month Scope

---

## ⚠️ Known Conflicts Between TDD and Locked Decisions

These must be resolved before implementation — do NOT build to the TDD default without checking here first:

| Topic | TDD Says | Locked Decision | Action Needed |
|---|---|---|---|
| ~~**PIN length**~~ | ~~4-digit Access PIN~~ | ✅ **6-digit — decided Aug 2026** | Use 6-digit PIN everywhere. TDD 4-digit references are superseded. |
| **Docker in MVP** | TDD says `USE_DOCKER=false` (ECS Fargate doesn't support DinD) | Sprint 1 uses **Docker socket on local host** (not Fargate) | No conflict — Sprint 1 is local only. TDD applies to Sprint 2+ cloud deploy. |
| **Provisioning time** | < 30 min (relaxed from warm-pool model) | PRD says < 5 min user setup, < 30 min total | Aligned. HTMX/SSE polling handles the wait. |

---

## 1. Architectural Vision

### Key Decisions from TDD

- **Asynchronous provisioning** — UI sends `POST /api/v1/sandboxes/provision`, gets `202 Accepted` + task ID. Real provisioning happens in background thread pool (`CompletableFuture`). Client polls status or gets email when ready.
- **Real-time log streaming** — Use **Server-Sent Events (SSE)** at `/logs/stream` to stream stdout from provisioning jobs to browser. Bypasses HTTP/ALB 60-second timeout. (Note: Sprint plan uses HTMX polling as simpler alternative for MVP.)
- **Workspace isolation** — Each provisioning job gets unique temp dir under `/tmp/workspaces/` to prevent git/Terraform state collisions during concurrent runs.
- **Version pinning** — Sandboxes deploy the latest stable `civiform/civiform` image at creation time and remain pinned to that version for the full 30-day lifecycle. No auto-upgrades mid-trial.
- **Wildcard SSL** — `*.sandbox.civiform.org` via AWS ACM (TLS 1.3). Pre-provisioned, not per-sandbox.

---

## 2. System Components

### 2.1 Creator Portal (Internal Admin Web App)
- CRUD UI: list all sandboxes + status
- New sandbox inputs: **City Name** + **Domain** + **Sample programs** to load
- Output: unique URL + **6-digit Access PIN** (decided Aug 2026)
- Stack: Play Framework + Thymeleaf + USWDS (same as CiviForm main)

### 2.2 Asynchronous Provisioning Orchestrator
- Job Queue: AWS SQS → Terraform/Cloud Deploy Worker (runs in ECS Fargate task)
- Status Polling API + Email notification ("Your Burlington Sandbox is Live!")
- Scheduled provisioning: `scheduledStartTime` field — sales rep can pre-provision before a meeting

### 2.3 Cloud Single-Tenant Runtime (per sandbox)
- **Compute**: AWS ECS Fargate
- **Database**: Amazon RDS PostgreSQL (isolated schema per sandbox)
- **Auth**: Built-in CiviForm `FAKE_IDP` + PIN Gate
- **Mail**: Mailpit mock container (captures all outgoing emails for evaluator inspection)
- **Routing**: Dynamic wildcard subdomain `burlington-vt.sandbox.civiform.org`

### 2.4 Persistent Dual-Role UI Shell Wrapper
Header always displayed across sandbox:
```
⏳ [Burlington, VT] Production Demo Sandbox — 30 Days Remaining
```
Role Switcher:
```
[ 👤 Resident Applicant View ] | [ 🛠️ CiviForm Admin Portal ]
```
Actions:
```
[ 🔄 Reset to Baseline ] | [ 📥 Export Program Schema (JSON) ] | [ ☎️ Contact CiviForm Sales ]
```

### 2.5 Teardown Engine
- Cron: AWS EventBridge daily at 02:00 UTC
- Actions: Drop DB schema → Terminate ECS task → Overwrite/delete S3 storage → Release DB pool handles
- DLQ: 3 retries, then Slack alert to `#civiform-sre-alerts`

---

## 3. Provisioning Engine — Technical Details

### 3.1 ECS Fargate Constraints (Sprint 2+)

> ⚠️ ECS Fargate does NOT support Docker-in-Docker (DinD). `USE_DOCKER=false` when running in Fargate.

All required CLI tools must be baked into the image:
- `terraform` (pinned 1.5.7)
- `aws` CLI v2
- `python3`, `pip`
- `git`, `bash`, `curl`, `jq`, `openssl`

### 3.2 Async Timeout Handling

1. Controller returns `202 Accepted` immediately
2. Provisioning runs in `CompletableFuture` background thread
3. Logs streamed via SSE at `/logs/stream`
4. Email sent when sandbox is ready

### 3.3 IAM / Credentials

- No hardcoded `AWS_ACCESS_KEY_ID` ever
- Use ECS Task Role (`CiviformControlPlaneTaskRole`)
- For multi-account: use STS `assume-role` to `OrganizationAccountAccessRole`

### 3.4 Database Connection Limits

- Use PgBouncer on RDS
- Max **15 connections per sandbox schema** to prevent pool exhaustion on shared RDS instance

---

## 4. API Contracts

### POST /api/v1/sandboxes/provision

Request:
```json
{
  "municipalityName": "Burlington, VT",
  "contactEmail": "bd-lead@exygy.com",
  "selectedDomains": ["housing", "utilities", "workforce", "childcare"],
  "baselineTemplate": "standard_midsize_city_v1",
  "scheduledStartTime": "2026-08-01T13:30:00Z",
  "requireAccessPin": true
}
```

Response `202 Accepted`:
```json
{
  "taskId": "task_burl_987654",
  "status": "SCHEDULED",
  "scheduledStartTime": "2026-08-01T13:30:00Z",
  "accessPin": "849201",
  "sandboxUrl": "https://burlington-vt.sandbox.civiform.org",
  "statusUrl": "https://sandbox-portal.civiform.org/api/v1/tasks/task_burl_987654"
}
```

### POST /api/v1/leads/pilot-request

Request:
```json
{
  "name": "Jane Doe",
  "email": "jdoe@burlingtonvt.gov",
  "municipality": "Burlington, VT",
  "packageTier": "discretionary_pilot"
}
```

Response `201 Created`:
```json
{
  "message": "Pilot proposal request received. Exygy BD lead notified.",
  "leadId": "lead_1785518637621",
  "submittedAt": "2026-07-31T17:23:57Z"
}
```

Automated actions on new lead:
- Email to `civiform-pilots@exygy.com`
- Slack card to `#civiform-sales-leads`

### POST /api/v1/discovery/search-programs

Discovery Engine — Step 1 (crawl .gov → Gemini extraction):
```json
{ "municipalityName": "Burlington, VT" }
```
Returns list of discovered programs with eligibility criteria.

### POST /api/v1/discovery/synthesize-schema

Discovery Engine — Step 2 (selected programs → CiviForm JSON + DB seed):
```json
{
  "municipalityName": "Burlington, VT",
  "selectedProgramIds": ["burl_water_discount", "burl_senior_tax_relief"]
}
```
Gemini generates form block definitions, field types, and predicate rules → seeds via `ProgramMigrationService`.

---

## 5. Security Architecture

### PIN Gate (Marcus / CISO Persona)

- Opening `burlington-vt.sandbox.civiform.org` → prompts for Access PIN
- Optional: restrict to `@burlingtonvt.gov` or `@exygy.com` email domain
- Successful PIN → encrypted HTTP-only session cookie (browser session duration)

### Persistent Warning Banner

```
⚠️ Demo Environment: Do NOT enter real resident PII. This sandbox is strictly for feature evaluation.
```

Displayed on ALL pages — Resident and Admin views. Not dismissible.

### PII Safeguards

- Strict PII exclusion filter in export engine
- Applicant data export is **prohibited** (program schema only)
- 30-day automatic cryptographic shredding of all storage

---

## 6. Cost Model

| Metric | Value |
|---|---|
| Cost per active sandbox | ~$40–$120/month |
| Target concurrent sandboxes | 5–10 |
| Monthly budget cap | ~$500–$1,200 |
| Primary funding | Exygy Bet #4 Sales Enablement budget |
| Secondary funding | Google.org fellowship grants (AWS credits if available) |

---

## 7. Testing Strategy

- **Unit**: JSON Schema Exporter — verify PII exclusion and data model parity
- **Integration**: REST API endpoints — correct status codes + payloads
- **PIN Gate security**: Verify FAKE_IDP role switching blocked before PIN entry
- **Teardown verification**: Simulate lifecycle teardown — confirm schemas dropped, no orphaned tables
- **CUJ E2E**: Schedule → PIN gate → dual-role switching → program edit → predicate config → schema export → teardown
