---
title: cf-sandbox-builder — PR & Testing Standards
trigger: always_on
---

# PR & Testing Standards

> Two types of PRs exist in this project. Standards differ significantly.
> Read the correct section before opening any PR.

---

## PR Type 1: civiform/civiform (Main Open-Source Repo)

**When**: Sprint 4+ changes — demo banner, role switcher, ROI panel, export button.
**Bar**: Public open-source. Reviewed by CiviForm core maintainers. High bar.

### Guiding Principles for CiviForm PRs

1. **Every sandbox feature must be env-var gated.** Non-sandbox CiviForm instances
   must be completely unaffected. Test both `FEATURE_FLAG=true` AND `FEATURE_FLAG=false`.
2. **No behavior change without a test.** If it's visible in the browser, there must be
   a browser test. If it changes server logic, there must be a unit test.
3. **Accessibility is not optional.** Every new UI element must pass keyboard navigation
   and have proper ARIA roles. CiviForm runs WCAG audits in CI.
4. **No hardcoded strings.** All user-facing text must go through the i18n message system.
5. **PR must be self-contained.** Reviewers shouldn't need to understand cf-sandbox-builder
   to review the PR — write the description assuming no sandbox context.

---

### CiviForm PR Checklist (must check every box before opening)

#### Code Quality
- [ ] No new compiler warnings introduced
- [ ] All new public methods have Javadoc
- [ ] All i18n strings added to `messages` file AND English translation
- [ ] No hardcoded English strings in Java or HTML
- [ ] Lombok `@Data @Builder` used on new model classes
- [ ] No static singletons — Guice DI only
- [ ] No blocking calls on the default thread pool (`CompletionStage` for all async)

#### Feature Flag Gating (sandbox-specific features)
- [ ] Feature fully disabled when env var is absent or `false`
- [ ] Explicit test: feature hidden when flag off
- [ ] Explicit test: feature visible when flag on
- [ ] Flag documented in CiviForm's feature flag registry/docs

**Required env var names for sandbox demo features:**
| Feature | Env Var | Default |
|---|---|---|
| Demo banner | `SHOW_NOT_PRODUCTION_BANNER_ENABLED` | `false` |
| FAKE_IDP login | `STAGING_DISABLE_DEMO_MODE_LOGINS` | `true` (disabled) |
| City whitelabel | `WHITELABEL_CIVIC_ENTITY_SHORT_NAME` | (empty) |
| Role switcher | To be defined — gate behind new flag | `false` |
| ROI panel | To be defined — gate behind new flag | `false` |

#### Unit Tests (JUnit 4 + AssertJ + Mockito)
- [ ] Every new service method has a unit test
- [ ] Happy path test
- [ ] Edge case: flag off → feature absent
- [ ] Edge case: flag on → feature present
- [ ] Error/exception path tested where applicable
- [ ] Test coverage does not drop below existing baseline

#### Browser / Integration Tests (Playwright)
CiviForm uses Playwright for E2E browser tests. Every visible UI change needs one.

- [ ] New UI component has a Playwright test
- [ ] Test verifies element is **absent** when flag is off
- [ ] Test verifies element is **present** when flag is on
- [ ] Role-specific visibility tested (Resident vs Admin)
- [ ] Test added to the correct browser test suite file (not a standalone orphan)
- [ ] No `page.waitForTimeout()` — use `page.waitForSelector()` or proper signals

#### Accessibility
- [ ] New interactive elements are keyboard-navigable (tab order correct)
- [ ] New buttons/links have descriptive `aria-label` or visible text
- [ ] New banners use `role="banner"` or `role="alert"` as appropriate
- [ ] Color contrast meets WCAG AA (4.5:1 for normal text)
- [ ] Banner is NOT dismissible (demo banner specifically must be persistent)
- [ ] Screen reader test: element makes sense when read aloud

#### Security
- [ ] No PII logged or exposed through the new feature
- [ ] Export features: applicant data export is **prohibited** — program schema only
- [ ] PIN validation uses constant-time comparison (prevent timing attacks)
- [ ] New endpoints require appropriate auth checks
- [ ] No secrets or API keys in code or tests

#### PR Description Requirements
Every CiviForm PR description must include:
1. **What**: What does this change do?
2. **Why**: Why is it needed? (Link to issue/design doc)
3. **How**: Brief technical explanation
4. **Testing**: What was tested and how
5. **Screenshots**: Before/after for any UI change
6. **Flag**: Which env var gates this feature and what the default is
7. **Rollback**: How to disable/revert if something goes wrong

---

### Specific Test Cases by Sprint 4 Feature

#### Demo Banner (`SHOW_NOT_PRODUCTION_BANNER_ENABLED`)

```
Unit tests:
- Banner rendered in BaseHtmlLayout when flag=true
- Banner absent in BaseHtmlLayout when flag=false
- City name injected from WHITELABEL_CIVIC_ENTITY_SHORT_NAME env var
- Days remaining calculated correctly from expiresAt env var
- Banner renders on Resident AND Admin pages

Browser tests:
- Banner visible on applicant home page (flag=true)
- Banner visible on admin dashboard (flag=true)
- Banner NOT dismissible (no close button, no JS dismiss)
- Banner NOT present on any page (flag=false)
- City name "Burlington, VT" appears in banner text
- "30 Days Remaining" appears with correct countdown

Accessibility:
- Banner has role="alert" or role="banner"
- Banner text is read by screen readers
- Banner does not shift layout or obscure content
```

#### Role Switcher

```
Unit tests:
- Switcher renders when STAGING_DISABLE_DEMO_MODE_LOGINS=false
- Switcher absent when STAGING_DISABLE_DEMO_MODE_LOGINS=true
- Clicking [Resident] sets correct FAKE_IDP persona
- Clicking [CiviForm Admin] sets correct FAKE_IDP persona
- Session state preserved on persona switch

Browser tests:
- [Resident] / [CiviForm Admin] / [Program Admin] buttons visible
- Click [CiviForm Admin] → page reloads as admin
- Click [Resident] → page reloads as applicant
- Active role is visually highlighted
- Switcher visible on all pages (not just home)
- Switcher absent when not in demo mode

Security tests:
- Cannot switch roles without valid PIN session cookie
- Persona switching does not expose admin data to resident session
```

#### Export Program Schema (JSON)

```
Unit tests:
- Export serializes program blocks correctly
- Export EXCLUDES all applicant/submission data (PII prohibition)
- Export EXCLUDES: applicant names, emails, addresses, SSN, income values
- Export INCLUDES: program names, block definitions, question types, predicate rules
- Exported JSON is valid CiviForm ProgramDefinition format
- Export works with 0 programs (empty ZIP)
- Export works with 5+ programs

Browser tests:
- "Export Program Schema (JSON)" button present in admin view
- Clicking triggers ZIP download
- ZIP contains one JSON per program
- JSON is parseable and matches expected schema

Security tests:
- Endpoint requires admin session (not accessible to resident)
- Response headers: Content-Disposition attachment (no inline display)
- Exported JSON verified to contain no PII fields
```

---

## PR Type 2: cf-sandbox-builder (Private Repo)

**When**: All Sprint 1–8 work in this repo.
**Bar**: Internal tool — lower ceremony, but still rigorous.

### cf-sandbox-builder PR Checklist

#### Code Quality
- [ ] No new compiler warnings
- [ ] Guice DI used — no static singletons
- [ ] All async ops use `CompletionStage` (no blocking calls in controllers)
- [ ] New model fields added to both `SandboxInstance.java` AND `sandbox_instances` table
- [ ] New routes added to `server/conf/routes`
- [ ] New i18n strings in `server/conf/messages`

#### Unit Tests (JUnit 4 + AssertJ + Mockito)
- [ ] Every new `SandboxService` method has a unit test in `InMemorySandboxService` test
- [ ] Every new controller action has a unit test
- [ ] PIN generation: test output is always 6 digits, random distribution
- [ ] PIN validation: correct PIN passes, wrong PIN fails, case doesn't matter
- [ ] Port allocation: test is thread-safe (concurrent calls get different ports)
- [ ] DB schema: test CRUD against embedded test Postgres (Testcontainers)

#### Specific Required Tests by Sprint

**Sprint 1 — Must have before merging:**
```
DockerSandboxService:
- createSandbox() → status is PROVISIONING (not RUNNING)
- createSandbox() → PIN is exactly 6 digits
- createSandbox() → Postgres schema created before container launch
- createSandbox() → container env vars include DATABASE_URL, APPLICATION_SECRET
- createSandbox() → concurrent calls get different ports (thread-safety test)
- getSandboxStatus() → returns PROVISIONING while container starting
- getSandboxStatus() → returns RUNNING after /health + 15s buffer
- getSandboxStatus() → returns FAILED if container exits non-zero
- validatePin() → returns sandbox on correct PIN
- validatePin() → returns empty on wrong PIN
- validatePin() → case insensitive (or document it's case sensitive — pick one)
- deleteSandbox() → drops Postgres schema
- deleteSandbox() → stops container

SandboxController:
- POST /sandboxes → 303 redirect to /sandboxes/:id (not home)
- POST /sandboxes/:id/access → redirect to sandbox URL on correct PIN
- POST /sandboxes/:id/access → 400 on wrong PIN
- GET /sandboxes/:id/status → returns HTML fragment (not full page)

PIN Gate:
- Correct PIN → HTTP-only session cookie set
- Wrong PIN → no cookie, error message shown
- Expired sandbox → PIN rejected
```

**Sprint 3 — Seeding Engine:**
```
SeedingEngine:
- seedSandbox() → calls ProgramMigrationService with correct program JSON
- seedSandbox() → waits for RUNNING status before seeding
- seedSandbox() → fails gracefully if CiviForm API returns error
- Cross-program prefill: 4 shared fields appear in all programs
- Predicate rule: income < $50k block shows when income < $50k
- Predicate rule: income >= $50k block hidden when income >= $50k
```

**Sprint 5 — Teardown:**
```
TeardownEngine:
- Drops Postgres schema for expired sandbox
- Stops/terminates ECS task
- DLQ receives failed teardown jobs
- Slack alert fires after 3 consecutive failures
- Expired sandbox returns 404 on PIN gate (not valid PIN)
```

#### Integration Tests
- [ ] Full end-to-end: create → RUNNING → PIN access → teardown
- [ ] Concurrent: 2 sandboxes created simultaneously → different ports, different schemas
- [ ] Docker socket accessible from inside builder container (manual test Day 1)

#### Security Tests
- [ ] PIN endpoint rate-limited (not tested until Sprint 5 hardening)
- [ ] No sandbox URL accessible without valid PIN session
- [ ] Expired sandbox not accessible even with valid PIN
- [ ] Container env vars not logged or exposed via any endpoint
- [ ] `APPLICATION_SECRET` is cryptographically random (32+ chars)

---

## Branching & PR Workflow

### For civiform/civiform PRs
1. Branch from `main`: `git checkout -b demo-sandbox/feature-name`
2. All commits must reference the issue number
3. PR title format: `[Demo Sandbox] Feature name (env-var gated)`
4. Request review from: Exygy Tech Lead + at least 1 CiviForm core maintainer
5. All CI checks must pass (do not merge with failing checks)
6. Squash merge preferred to keep history clean

### For cf-sandbox-builder PRs
1. Branch from `main`: `git checkout -b sprint1/feature-name`
2. PR title format: `[S1-BE3] DockerSandboxService: launch CiviForm containers`
3. Request review from: 1 SWE peer
4. Run `./bin/run-dev` and manually verify the feature before requesting review
5. Merge commit (not squash) to preserve sprint history

---

## CI Must-Pass Gates (never merge with red CI)

### civiform/civiform
- `./bin/run_tests` (Play unit tests)
- `./bin/run_browser_tests` (Playwright)
- Checkstyle
- Dependency vulnerability scan
- Accessibility audit (axe-core in browser tests)

### cf-sandbox-builder
- `sbt test` (all JUnit tests pass)
- `sbt compile` (zero warnings)
- Docker socket test (manual): `docker exec <builder> docker run hello-world`
