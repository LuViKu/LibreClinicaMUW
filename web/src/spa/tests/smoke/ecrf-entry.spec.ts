/**
 * @smoke — eCRF data entry: fill + save (#23).
 *
 * The deep fill+save path the component suite covers in isolation
 * (CrfEntryView vitest) — here exercised end-to-end against the real
 * backend: an investigator opens a CRF from a visit, edits a field, saves
 * the draft, and gets the success toast (#11/#15). Uses the seeded Default
 * Study cohort. Resilient by design — it walks to the first CRF that
 * exposes an editable field rather than hard-coding a seed OID.
 */
import { test, expect, type Page } from '@playwright/test'
import { login } from '../support/auth'

/** Open a subject → visit → the first CRF that has an editable input. */
async function openFillableCrf(page: Page): Promise<boolean> {
  await page.goto('/subjects', { waitUntil: 'domcontentloaded' })
  const subject = page.getByRole('link', { name: /M-\d+/ }).first()
  await expect(subject).toBeVisible({ timeout: 15_000 })
  await subject.click()
  await expect(page).toHaveURL(/\/subjects\/M-\d+/)

  const visit = page.locator('a[href*="/events/"]').first()
  await expect(visit).toBeVisible({ timeout: 15_000 })
  await visit.click()
  await expect(page).toHaveURL(/\/events\/[\w-]+/)

  // Walk the event's CRF-entry links until one yields an editable field.
  const crfLinks = page.locator('a[href*="/event-crfs/"]:not([href*="/readonly"]):not([href*="/print"])')
  const count = await crfLinks.count()
  for (let i = 0; i < count; i++) {
    const href = await crfLinks.nth(i).getAttribute('href')
    if (!href) continue
    await page.goto(href, { waitUntil: 'domcontentloaded' })
    // An editable entry form has at least one enabled text/number input
    // that isn't inside a disabled fieldset (read-only / completed CRFs
    // disable the whole fieldset).
    const editable = page.locator('form input:not([disabled]):not([readonly])').first()
    if (await editable.count() > 0 && await editable.isEnabled().catch(() => false)) {
      return true
    }
  }
  return false
}

test.describe('@smoke eCRF entry', () => {
  test('investigator fills a field and saves the draft (success toast)', async ({ page }) => {
    await login(page.context(), 'investigator')
    const found = await openFillableCrf(page)
    test.skip(!found, 'no CRF with an editable field in the seeded cohort')

    const input = page.locator('form input:not([disabled]):not([readonly])').first()
    const type = await input.getAttribute('type')
    await input.fill(type === 'number' ? '12' : 'E2E')

    // Save draft — label is locale-dependent (de-AT default / en fallback).
    await page.getByRole('button', { name: /Entwurf speichern|Save draft/i }).click()

    // #11/#15 — the success toast confirms the save.
    await expect(page.getByTestId('global-toast-success')).toBeVisible({ timeout: 15_000 })
  })
})
