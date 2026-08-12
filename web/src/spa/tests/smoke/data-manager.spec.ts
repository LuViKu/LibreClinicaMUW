/**
 * @smoke — Data Manager / study-build journey.
 *
 * Covers CRF creation (shell via the library UI + version authoring via the
 * real API) and reaching the study-details editor. The version-authoring
 * test is the direct regression guard for the 2026-08 duplicate-key failure
 * (out-of-sync id sequences 500'd every new-version save).
 */
import { test, expect } from '@playwright/test'
import { login, loginAndGoto, purgeCrfsByPrefix } from '../support/auth'

const CTX = '/LibreClinica'
const TEST_CRF_PREFIXES = ['E2E Authoring', 'E2E UI CRF']

test.describe('@smoke Data Manager', () => {
  // Self-cleanup: disable the CRFs these tests create so a local dev DB doesn't
  // accumulate fixtures across runs. Runs even if a test failed mid-way.
  test.afterAll(async ({ browser }) => {
    const ctx = await browser.newContext()
    try {
      await login(ctx, 'dataManager')
      await purgeCrfsByPrefix(ctx, TEST_CRF_PREFIXES)
    } finally {
      await ctx.close()
    }
  })

  test('authors a CRF version end-to-end (regression: sequence-drift 500)', async ({ page }) => {
    await login(page.context(), 'dataManager')
    const req = page.context().request
    const ts = Date.now()

    const create = await req.post(`${CTX}/pages/api/v1/crfs`, {
      data: { name: `E2E Authoring ${ts}`, description: 'smoke: version authoring persists' },
      headers: { 'content-type': 'application/json' },
    })
    expect(create.status(), 'CRF shell create should return 201').toBe(201)
    const crf = await create.json()
    expect(crf.oid).toBeTruthy()

    const payload = {
      versionName: `v-${ts}`, versionDescription: 'smoke', revisionNotes: '',
      sections: [{ label: 'S1', title: 'Section 1', instructions: '', ordinal: 1, items: [
        { name: `e2e_text_${ts}`, oid: `I_E2E_TEXT_${ts}`, descriptionLabel: 'text', leftItemText: '', rightItemText: '', units: '', dataType: 'ST', defaultValue: '', required: false, responseSet: { type: 'text', label: `e2e text ${ts}` } },
        { name: `e2e_bool_${ts}`, oid: `I_E2E_BOOL_${ts}`, descriptionLabel: 'bool', leftItemText: '', rightItemText: '', units: '', dataType: 'BL', defaultValue: '', required: false, responseSet: { type: 'single-select', label: `e2e bool ${ts}`, options: [{ text: 'Yes', value: '1' }, { text: 'No', value: '0' }] } },
      ] }],
    }
    const author = await req.post(`${CTX}/pages/api/v1/crfs/${encodeURIComponent(crf.oid)}/versions`, {
      data: payload,
      headers: { 'content-type': 'application/json', 'accept-language': 'de-AT' },
    })
    expect(author.ok(), `authoring a version should persist (got ${author.status()})`).toBeTruthy()
    const version = await author.json()
    expect(version.oid, 'persisted version has an OID').toBeTruthy()
    expect(Array.isArray(version.errors) && version.errors.length, 'no authoring errors').toBeFalsy()

    // Confirm the version is listed with its items (round-trips the persistence).
    const list = await req.get(`${CTX}/pages/api/v1/crfs/${encodeURIComponent(crf.oid)}/versions`)
    expect(list.ok()).toBeTruthy()
    const versions = await list.json()
    expect(versions.some((v: { name: string }) => v.name === `v-${ts}`), 'new version appears in the list').toBeTruthy()
  })

  test('creates a CRF shell through the library UI', async ({ page }) => {
    await loginAndGoto(page, 'dataManager', '/crf-library')
    const ts = Date.now()
    await page.getByRole('button', { name: 'Neues CRF' }).click()
    await page.locator('#crf-create-name').fill(`E2E UI CRF ${ts}`)
    await page.getByRole('button', { name: 'CRF anlegen' }).click()
    // The new CRF row should appear in the library list.
    await expect(page.getByText(`E2E UI CRF ${ts}`)).toBeVisible({ timeout: 15_000 })
  })

  test('reaches site (location) management', async ({ page }) => {
    // #2 — site management is a Data-Manager study-build task (was admin-only).
    // (Study-details EDIT is Administrator-only — covered by the admin spec.)
    await loginAndGoto(page, 'dataManager', '/sites')
  })
})
