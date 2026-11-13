/** Base URL of the running cf-sandbox-builder test server. Override with BASE_URL env var. */
export const BASE_URL = process.env.BASE_URL ?? 'http://localhost:9002'

/** Instant the test server's Clock and the browser clock are both frozen at. */
export const FIXED_CLOCK_INSTANT =
  process.env.FIXED_CLOCK_INSTANT ?? '2026-10-01T12:00:00Z'

/** A known sandbox ID seeded for browser tests. */
export const SEEDED_SANDBOX_ID = 'sb-demo0001'

/** The PIN for the seeded demo sandbox. */
export const SEEDED_SANDBOX_PIN = '482917'

/** Portal admin credentials (MVP: single shared account). */
export const PORTAL_EMAIL = 'admin@civiform.dev'
export const PORTAL_PASSWORD = process.env.DEMO_PORTAL_PASSWORD ?? 'demo'
