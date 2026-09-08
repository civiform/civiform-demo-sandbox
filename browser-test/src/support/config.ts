/** Base URL of the running cf-sandbox-builder server. Override with BASE_URL env var. */
export const BASE_URL = process.env.BASE_URL ?? 'http://localhost:9001'

/** A known sandbox ID seeded by InMemorySandboxService for browser tests. */
export const SEEDED_SANDBOX_ID = 'sb-demo0001'

/** The PIN for the seeded demo sandbox. */
export const SEEDED_SANDBOX_PIN = '482917'
