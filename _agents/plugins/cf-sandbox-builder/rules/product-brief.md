---
title: cf-sandbox-builder — Product Brief & Demo Requirements
trigger: always_on
---

# Product Brief: Instant Sales Sandbox Provisioner

**Status**: WIP | Target: Fellowship Q3 2026  
**PRD Author**: Tom George (PM Twin) w/ CiviForm Fellowship Team | Jul 30–31, 2026  
**Scoping**: Lesley Katzen w/ Jetski | Aug 3, 2026  
**Repo**: github.com/civiform/civiform-demo-sandbox  
**Strategic alignment**: Partner Reseller Activation / "Pitch-in-a-Box" — Workstream Track C

---

## Executive Summary

Sales partners and Exygy BD leads face significant friction pitching CiviForm: demos require either
static slides or complex local developer infrastructure. Municipal decision-makers need hands-on,
production-parity proof before committing.

**The solution**: A self-service, AI-assisted Generator Portal that lets Exygy BD leads and authorized
Bet #4 Reseller Partners spin up isolated, fully-functional, single-tenant CiviForm sandbox instances
in under 5 minutes of user setup — pre-loaded with real civic safety-net programs, mock applicant
profiles, and predicate rules — shareable with prospects via a 6-digit PIN for a 30-day trial.

---

## Goals & Success Metrics

### Product Goals

| Goal | Detail |
|---|---|
| Fast provisioning | < 5 min user setup, sandbox live in < 30 min total |
| Dual-role demo experience | 1-click role switcher: [Resident] / [CiviForm Admin] / [Program Admin] with persistent demo banner |
| Core platform showcase | Cross-program data pre-fill (4 fields), predicate rules (Show Block 3 IF Income < $50k), ROI benchmarks (75% completion time reduction) |
| Frictionless production hand-off | Self-service [Export Program Schema (JSON)] for city admins to carry trial config into production |

### Success Metrics

| Category | Metric | Target |
|---|---|---|
| Leading / Velocity | Time to provision personalized sandbox | < 5 min user setup |
| Fidelity & Parity | Visual + functional parity with CiviForm production UI | 100% parity |
| Evaluator Engagement | % of trial sessions using both Resident and Admin views | > 80% |
| Lagging (OMTM) | Conversion: sandbox demos → formal RFP/contract discussions | Land 1st deal |

---

## Non-Goals

- No ungated public access — restricted to Exygy BD leads and authorized Bet #4 reseller partners only
- No per-city code forks — all customization via standard CiviForm program schemas and templates
- No live PII or production traffic — synthetic mock data only; no real resident databases ever imported
- No applicant submission export — export engine covers program blocks, question definitions, and predicate schemas only; **applicant data export is prohibited**

---

## User Personas

| Persona | Role & Objectives | Key Pain Points |
|---|---|---|
| **Sales Partner / Exygy BD Lead** | Pitch CiviForm in 20-min meetings, win Bet #4 bids | Can't run terminal commands; needs fast 1-click provisioning |
| **Municipal Central IT Lead** | Evaluate maturity, security, vendor consolidation ROI | Skeptical of slides; needs hands-on proof of low-code + zero lock-in |
| **Program Administrator** | Configure safety-net intake forms, eligibility rules, program blocks | Overwhelmed by legacy software; needs no-code authoring + predicate rules |
| **Resident Applicant** (indirect) | Apply for benefits without repeating household info | Frustrated by repetitive forms across multiple agencies |

---

## Key Demo Features — Must Be Showcased in Baseline Seed

These features **must be pre-loaded** in every sandbox. Do not omit or defer these:

1. **Cross-program data pre-filling** — at least 4 fields pre-filled across programs
2. **Predicate rules logic** — e.g., Show Block 3 IF Income < $50k
3. **Persistent demo banner** — always visible, not dismissible
4. **1-click User Role Switcher** — [Resident] | [CiviForm Admin] | [Program Admin]
5. **Side-by-side ROI benchmarks** — 75% application completion time reduction
6. **Export Program Schema (JSON)** — self-service export for production hand-off

---

## Full Architecture Flow

```
cf-sandbox-builder (Play/Java + Thymeleaf + HTMX)
│
├── SandboxController ──▶ SandboxService (orchestration)
│                          │
│                          ├──▶ Container runtime
│                          │     └──▶ CiviForm image per sandbox + isolated Postgres
│                          │
│                          ├──▶ Seeding Engine ──▶ ProgramMigrationService
│                          │
│                          ├──▶ PDF Scaffolder ──▶ pdf-to-civiform-ocr (Flask, existing)
│                          │
│                          ├──▶ Discovery Engine ──▶ .gov crawler → Gemini → CiviForm JSON
│                          │
│                          ├──▶ PIN Gate (6-digit, sandbox-scoped)
│                          │
│                          └──▶ Teardown Engine (EventBridge + Lambda + DLQ, 30-day)
│
└── PostgreSQL (sandbox metadata: ID, city, status, expiry, PIN, usage)
```

**Sprint 1 focus**: Container runtime (Docker socket) + PIN Gate. Everything else is Sprint 2+.

---

## Technology Stack: Tom's PRD vs. Sal's Scaffold (Sal's wins)

| Layer | Tom's PRD | Sal's Scaffold | Verdict |
|---|---|---|---|
| Backend | Node.js REST | Play Framework 3.0 (Java) | ✅ Mirrors CiviForm main |
| Templating | React SPA | Thymeleaf + HTMX | ✅ No React/Node knowledge gap |
| DI | N/A | Google Guice | ✅ Same as CiviForm main |
| Design system | Unspecified | USWDS 3.x + Tailwind CSS | ✅ 100% visual parity with CiviForm |
| Frontend tooling | Vite | Vite + TypeScript + Sass + PostCSS | ✅ Same |
| Reactive UI | React | HTMX | ✅ Simpler, hypermedia-native |
| Testing | Unspecified | JUnit 4 + AssertJ + Mockito | ✅ Same as CiviForm main |

**Tom's "PM Twin" Node.js prototype is reference only — not production code.**

---

## 4-Month Sprint Plan Overview (8 × 2-week sprints)

Team: 2 SWEs (1 FE, 1 BE) | Capacity: ~60 hrs/sprint per SWE

### Frontend Track — 259 hrs total

| Sprint | Key Features | Est. Hrs |
|---|---|---|
| S1 | Portal shell + "Create Sandbox" form | 27 |
| S2 | Progress bar + async status polling, sandbox list view | 30 |
| S3 | PIN gate UI, sandbox detail panel, city name branding | 35 |
| S4 | Program template picker, Lead/CRM pilot request form | 30 |
| S5 | "Manage Sandboxes" table, expiration warnings, leads admin view | 25 |
| S6 | PDF Scaffolder UI, Discovery Engine trigger UI, WCAG audit | 44 |
| S7 | Discovery Engine seed confirm flow, mobile polish, error states | 26 |
| S8 | E2E browser tests (Playwright), buffer/polish | 30 |

### Backend Track — 327 hrs total

| Sprint | Key Features | Est. Hrs |
|---|---|---|
| S1 | Architecture spike, Provisioning API, RDS schema + Terraform skeleton | 32 |
| S2 | ECS Fargate deploy of CiviForm image per sandbox, metadata store | 40 |
| S3 | Isolated RDS Postgres per sandbox, seeding engine | 40 |
| S4 | Synthetic data seeder, program template library, Lead/CRM API | 44 |
| S5 | 30-day expiration job, usage tracking, PIN gate BE, mock auth, leads admin API | 54 |
| S6 | PDF Scaffolder integration, graduation flow, Discovery Engine crawl+Gemini | 54 |
| S7 | City name injection, SMTP pre-config, Discovery seeder, cost guardrails, security | ~54 |
| S8 | Integration tests, load/smoke tests | 30 |

> ⚠️ S7 BE is the heaviest sprint (~54 hrs). If tight, Discovery seeder slides to S8.

---

## New Scope Items Needing Product Sign-Off

### 1. Gemini Municipal Discovery Engine ⚠️ Needs sign-off
- Crawls public .gov sites → extracts programs via Gemini → seeds into sandbox
- Reuses `pdf-to-civiform-ocr` crawl layer (saves ~17 hrs)
- BE: ~38 hrs | FE: ~20 hrs
- Risk: inconsistent Gemini extraction quality; crawl rate-limiting / robots.txt

### 2. Lead/CRM Ingestion — Tier definitions not finalized
- `POST /api/v1/leads/pilot-request` with pilot tier classification
- Tiers: `discretionary_pilot` | `full_enterprise` | `partner_reseller`
- BE: ~20 hrs | FE: ~15 hrs
- Fallback: text field + manual Exygy triage if tiers not confirmed before S4

---

## Open Questions

### Carried from v1 (still unanswered)
- [ ] Program template authoring — who authors/curates the initial real program JSONs?
- [ ] Auth for Generator Portal — how do Exygy sales reps log in?
- [ ] Single or two portals — sales rep view vs. city admin view?

### New (from Tom's demo)
- [ ] Does the PIN gate expire with the sandbox (30 days), or separately?
- [ ] Is the PIN per-sandbox or per-user-within-sandbox?
- [ ] Who triggers the graduation flow — Exygy manually, or a portal button?

### New (from Discovery Engine + Lead/CRM)
- [ ] Discovery Engine quality bar — how many programs per city should it reliably find?
- [ ] Does Exygy rep review extracted programs before seeding, or fully automatic?
- [ ] Lead tier classification — Exygy manual decision or self-selected by lead?
- [ ] Lead/CRM — integrate with HubSpot/Salesforce or portal-only table for V1?

---

## Key Risks

| Risk | Likelihood | Impact |
|---|---|---|
| Container provisioning > 5 min | Medium | High — pre-warm pool recommended |
| Gemini extraction quality inconsistent across .gov sites | High | Medium — need fallback |
| Crawl rate-limiting / robots.txt blocks | Medium | Medium — user-agent policy + PDF fallback |
| Lead tier definitions not finalized before S4 | Medium | Low — text field fallback |
| S7 BE over capacity | Medium | Low — Discovery seeder slides to S8 |
| Program template authorship blocked | High | Low |
| Fellows roll-off maintenance gap | High | High — serverless + docs top priority |

---

## Needs Sign-Off From Leadership

1. **Gemini Discovery Engine** — autonomous .gov web crawler; major new scope item
2. ~~**AWS-only confirmation**~~ — ✅ **Decided Aug 2026: AWS-only. GCP Terraform modules discarded.**

---

## Reference Links

- [Engineering Handover Package (Drive)](https://drive.google.com/file/d/100fFGm4rdoqiwuD2aebUG__IPuvoGqDC/view)
- [Demo banner spec (Drive)](https://docs.google.com/document/d/1oHexkhzhi_GlF-C1qot6EYhO7x40lpUIwGmfIanXQt0/edit)
- [Main CiviForm repo](https://github.com/civiform/civiform)
- Exygy demo deployment workflow: `experimental/users/lkatzen/skills/civiform_demo_deployment/SKILL.md`
