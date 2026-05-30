#!/usr/bin/env node
/**
 * Phase E.2 — Design-token guard.
 *
 * Fails (exit 1) when any source file under src/ uses a Tailwind colour
 * utility outside the locked MUW palette. The check is regex-based on
 * Tailwind v4 class strings, which is sufficient for the closed set of
 * utilities we generate; if Tailwind v4 ever ships arbitrary `bg-[#…]`
 * tokens in production code, extend this script.
 *
 * Allowed palette:
 *   - The 4 MUW families (muw-blue, muw-sky, muw-teal, muw-coral)
 *     across the 50–900 scale.
 *   - The neutral scale (white / black / slate-50…950) for borders,
 *     surfaces, typography.
 *   - The two intentionally-out-of-palette semantic colours per
 *     DR-008 / the design-system memory (amber, rose) — MUW has no
 *     unambiguous red, and the team accepted these as the warning/danger
 *     visual tells.
 *
 * Anything else (blue-*, sky-*, teal-*, emerald-*, indigo-*, violet-*,
 * fuchsia-*, lime-*, etc. — the default Tailwind ramps) is flagged.
 */
import { readdir, readFile } from 'node:fs/promises'
import { join, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = fileURLToPath(new URL('.', import.meta.url))
const ROOT = resolve(__dirname, '..', 'src')

// Tailwind v4 default colour-ramp prefixes we deliberately do NOT use.
const FORBIDDEN_FAMILIES = new Set([
  'red', 'orange', 'yellow', 'lime', 'green', 'emerald', 'teal',
  'cyan', 'sky', 'blue', 'indigo', 'violet', 'purple', 'fuchsia',
  'pink',
  // (no `gray` — Tailwind v4 uses `slate` as the neutral default we keep)
])

const ALLOWED_FAMILIES = new Set([
  'muw-blue', 'muw-sky', 'muw-teal', 'muw-coral',
  // Neutral surface scale we accept (slate-* is the dominant typography
  // + border colour in the MUW design tokens).
  'slate',
  // Intentional out-of-palette semantic colours (per [[phase-e-design-system]]).
  'amber', 'rose',
  // Plain `white` / `black` are not family-scoped — handled separately.
])

const COLOUR_UTILITY = /(?:bg|text|border|ring|outline|fill|stroke|from|to|via|divide|placeholder|caret|accent|shadow|decoration)-([a-z][a-z0-9-]*?)(?:-(\d{2,3}))?(?=[\s"'\]/:]|$)/g

const SKIP_DIRS = new Set(['node_modules', 'dist', '__tests__', '.histoire-output'])

async function walk(dir) {
  const entries = await readdir(dir, { withFileTypes: true })
  const out = []
  for (const entry of entries) {
    if (SKIP_DIRS.has(entry.name)) continue
    const full = join(dir, entry.name)
    if (entry.isDirectory()) {
      out.push(...(await walk(full)))
    } else if (/\.(vue|ts|tsx|html)$/.test(entry.name)) {
      out.push(full)
    }
  }
  return out
}

const violations = []

const files = await walk(ROOT)
for (const file of files) {
  const content = await readFile(file, 'utf-8')
  COLOUR_UTILITY.lastIndex = 0
  let match
  while ((match = COLOUR_UTILITY.exec(content)) !== null) {
    const [hit, family] = match
    if (!family) continue

    // Skip non-colour `border` / `bg` / `ring` utilities like `border-2`
    // or `bg-cover` — those don't have a colour family.
    if (/^\d+$/.test(family)) continue
    if (['transparent', 'current', 'inherit', 'none', 'black', 'white'].includes(family)) continue

    // Forbidden = explicit Tailwind default ramp.
    if (FORBIDDEN_FAMILIES.has(family) && !ALLOWED_FAMILIES.has(family)) {
      const lineNumber = content.slice(0, match.index).split('\n').length
      violations.push({
        file: relative(resolve(ROOT, '..'), file),
        line: lineNumber,
        utility: hit,
        family,
        hint: hintFor(family),
      })
    }
  }
}

function hintFor(family) {
  if (family === 'blue' || family === 'indigo') return 'use muw-blue-* (Dunkelblau)'
  if (family === 'sky' || family === 'cyan') return 'use muw-sky-* (Hellblau)'
  if (family === 'teal' || family === 'emerald' || family === 'green') return 'use muw-teal-* (Grün)'
  if (family === 'pink' || family === 'fuchsia' || family === 'purple' || family === 'violet') return 'use muw-coral-* (Coral)'
  if (family === 'red') return 'use rose-* (semantic danger) per DR-008 — MUW has no unambiguous red'
  if (family === 'orange' || family === 'yellow') return 'use amber-* (semantic warning) per DR-008'
  return 'use a token from the MUW palette'
}

if (violations.length === 0) {
  console.log(`check-tokens — OK  (${files.length} files scanned)`)
  process.exit(0)
}

console.error(`\ncheck-tokens — FAIL  (${violations.length} violation${violations.length === 1 ? '' : 's'})\n`)
for (const v of violations) {
  console.error(`  ${v.file}:${v.line}  ${v.utility}`)
  console.error(`    ↳ ${v.hint}`)
}
console.error(`\nReplace each utility with one from the MUW palette before committing.`)
console.error(`See web/src/spa/src/style.css for the locked @theme token set.`)
process.exit(1)
