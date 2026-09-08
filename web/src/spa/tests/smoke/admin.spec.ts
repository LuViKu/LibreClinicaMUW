/**
 * @smoke — Administrator journey.
 *
 * manual_admin is a pure system administrator (no study assignment), so this
 * covers the study-agnostic admin surface: user management and study creation.
 * (Editing an existing study's details is covered by the Data Manager spec,
 * which holds a study role.)
 */
import { test, expect } from '@playwright/test'
import { loginAndGoto } from '../support/auth'

test.describe('@smoke Administrator', () => {
  test('user management lists seeded accounts', async ({ page }) => {
    await loginAndGoto(page, 'admin', '/manage-users')
    // The user table should have hydrated with the seeded demo accounts.
    await expect(page.getByText(/manual_dm|manual_investigator|root/).first()).toBeVisible({ timeout: 15_000 })
  })

  test('study-creation form renders and is fillable', async ({ page }) => {
    await loginAndGoto(page, 'admin', '/studies/new')
    // A create form with at least one text input + a submit control.
    await expect(page.locator('form input, input[type="text"]').first()).toBeVisible({ timeout: 15_000 })
    await expect(page.getByRole('button').first()).toBeVisible()
  })
})
