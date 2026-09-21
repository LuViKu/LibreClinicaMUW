/**
 * P2-7 — `?mock=1` must not work in a production build.
 *
 * The nAMD workspace can render fabricated visits so it is developable without
 * a real subject. That is a development affordance: a URL parameter that fills
 * a clinical screen with invented fluid volumes is one pasted link away from a
 * clinician reading numbers that describe nobody, with nothing on the page
 * saying so.
 *
 * The gate is `import.meta.env.DEV`, which Vite replaces with a literal at
 * build time, so the branch is not merely unreachable in production — it is
 * absent from the bundle.
 */
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

// Read from the project root: the test runs in a jsdom environment whose
// import.meta.url is not a file URL.
const source = readFileSync(
  resolve(process.cwd(), 'src/studyModules/nAMD/views/NamdWorkspaceView.vue'),
  'utf-8',
)

describe('nAMD workspace mock mode', () => {
  it('reads the mock flag only behind a build-time DEV guard', () => {
    const line = source.split('\n').find((l) => l.includes('route.query.mock'))
    expect(line, 'the mock query parameter should still be read somewhere').toBeTruthy()
    expect(line).toContain('import.meta.env.DEV')
  })

  it('has no other route to mock data', () => {
    const reads = source.split('\n').filter((l) => l.includes('query.mock'))
    expect(reads).toHaveLength(1)
  })
})
