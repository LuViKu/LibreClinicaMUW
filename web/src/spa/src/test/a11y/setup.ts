import { expect } from 'vitest'
import { toHaveNoViolations } from 'vitest-axe/matchers'

/**
 * Phase E.9 — Vitest a11y harness setup.
 *
 * Wires `vitest-axe`'s matcher onto the global `expect`. Component
 * tests under `src/test/a11y/` and per-primitive tests can then call
 *
 *   import { axe } from 'vitest-axe'
 *   const wrapper = mount(MyComponent)
 *   expect(await axe(wrapper.element)).toHaveNoViolations()
 *
 * jsdom doesn't render colours, so contrast issues are caught by the
 * Playwright suite under `tests/a11y/*.spec.ts` instead. Vitest covers
 * structure-level WCAG 2.2 AA rules: labelling, ARIA roles, form
 * association, heading order, semantic landmarks, link names, table
 * headers.
 */

expect.extend({ toHaveNoViolations })
