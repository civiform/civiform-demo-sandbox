/**
 * Global setup — waits for the cf-sandbox-builder server to be healthy
 * before Playwright starts running tests.
 *
 * Mirrors CiviForm's browser-test/src/setup/global-setup.ts pattern.
 * Polls /health until 200 or timeout (60s).
 */

import {request} from '@playwright/test'
import {BASE_URL} from '../support/config'

export default async function globalSetup(): Promise<void> {
  const healthUrl = `${BASE_URL}/health`
  const maxWaitMs = 60_000
  const pollIntervalMs = 1_000
  const start = Date.now()

  console.log(`[global-setup] Waiting for server at ${healthUrl}...`)

  while (Date.now() - start < maxWaitMs) {
    try {
      const ctx = await request.newContext()
      const response = await ctx.get(healthUrl)
      await ctx.dispose()
      if (response.ok()) {
        console.log(`[global-setup] Server ready (${Date.now() - start}ms)`)
        return
      }
    } catch {
      // Server not up yet — keep polling
    }
    await new Promise((resolve) => setTimeout(resolve, pollIntervalMs))
  }

  throw new Error(
    `[global-setup] Server at ${healthUrl} did not respond within ${maxWaitMs}ms.\n` +
      'Run ./bin/run-dev before running browser tests.',
  )
}
