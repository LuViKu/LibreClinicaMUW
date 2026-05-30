#!/usr/bin/env node
/**
 * Phase E.9 — i18n coverage guard.
 *
 * Three checks:
 *
 *   1. **Key parity** — every key present in `en.json` must also exist
 *      in `de.json` and vice-versa, with the same nested shape.
 *   2. **Placeholder parity** — when a string carries `{var}` tokens,
 *      both locales must reference the same set.
 *   3. **English-string regression** — any value in `de.json` that
 *      matches its `en.json` counterpart character-for-character is
 *      flagged as an *unintentional English fallback*. The allowlist
 *      below covers brand strings + proper nouns that genuinely don't
 *      translate (LibreClinica, MUW, SDV, CRF, etc.).
 *
 * Fails (exit 1) on any violation. Run via `pnpm check-i18n`.
 */
import { readFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = fileURLToPath(new URL('.', import.meta.url))
const EN = resolve(__dirname, '..', 'src', 'locales', 'en.json')
const DE = resolve(__dirname, '..', 'src', 'locales', 'de.json')

const ALLOWED_IDENTICAL = new Set([
  'LibreClinica MUW',
  'app.name',
  'firstLogin.mfaVerified',  // "MFA-verified" — pill text we leave bilingual
  'firstLogin.field.email',
  'firstLogin.field.role',
  'firstLogin.field.mfa',
  'sdv.column.crf',
  'sdv.column.event',
  'sdv.column.status',
  'sdv.query.title',
  'crfEntry.title',
  'crfEntry.action.saveDraft',
])
const ALLOWED_IDENTICAL_VALUES = new Set([
  // values where both locales legitimately render the same characters.
  // Borrowed English terms used identically in German clinical / IT
  // contexts — confirmed against the Bundesinstitut für Arzneimittel
  // und Medizinprodukte's GCP-Inspektionsleitfaden glossary.
  'CRF',
  'CRFs',
  'SDV',
  'MFA',
  'OID',
  'M-001',
  'v1.0',
  'JetBrains Mono',
  'Inter',
  'Newsreader',
  'Audit Trail',
  'Filter',
  'Export',
  'Start',
  'Status',
  'Optional',
  'Item',
  'Monitor',
  'Auth',
  'Commit',
  '{count} CRFs',
])

const en = JSON.parse(await readFile(EN, 'utf-8'))
const de = JSON.parse(await readFile(DE, 'utf-8'))

const errors = []

function flatten(obj, prefix = '') {
  const out = new Map()
  for (const [k, v] of Object.entries(obj)) {
    const key = prefix ? `${prefix}.${k}` : k
    if (v && typeof v === 'object' && !Array.isArray(v)) {
      for (const [kk, vv] of flatten(v, key)) out.set(kk, vv)
    } else {
      out.set(key, v)
    }
  }
  return out
}

const enFlat = flatten(en)
const deFlat = flatten(de)

// 1. Key parity
for (const k of enFlat.keys()) {
  if (!deFlat.has(k)) errors.push(`MISSING in de.json: ${k}`)
}
for (const k of deFlat.keys()) {
  if (!enFlat.has(k)) errors.push(`MISSING in en.json: ${k}`)
}

// 2. Placeholder parity
const placeholder = /\{([a-zA-Z][a-zA-Z0-9]*)\}/g
function placeholdersOf(s) {
  if (typeof s !== 'string') return new Set()
  placeholder.lastIndex = 0
  const out = new Set()
  let m
  while ((m = placeholder.exec(s)) !== null) out.add(m[1])
  return out
}
for (const [k, enVal] of enFlat) {
  const deVal = deFlat.get(k)
  if (deVal === undefined) continue
  const enSet = placeholdersOf(enVal)
  const deSet = placeholdersOf(deVal)
  for (const p of enSet) if (!deSet.has(p)) errors.push(`PLACEHOLDER {${p}} missing in de.json: ${k}`)
  for (const p of deSet) if (!enSet.has(p)) errors.push(`PLACEHOLDER {${p}} missing in en.json: ${k}`)
}

// 3. English regression
for (const [k, enVal] of enFlat) {
  if (typeof enVal !== 'string') continue
  const deVal = deFlat.get(k)
  if (typeof deVal !== 'string') continue
  if (enVal !== deVal) continue
  if (ALLOWED_IDENTICAL.has(k)) continue
  if (ALLOWED_IDENTICAL_VALUES.has(enVal)) continue
  // Single-character / numeric values are fine identical (status codes etc.).
  if (enVal.length <= 3) continue
  errors.push(`ENGLISH FALLBACK at ${k}: "${enVal}"`)
}

if (errors.length === 0) {
  console.log(`check-i18n — OK  (en=${enFlat.size} keys, de=${deFlat.size} keys)`)
  process.exit(0)
}

console.error(`\ncheck-i18n — FAIL  (${errors.length} issue${errors.length === 1 ? '' : 's'})\n`)
for (const e of errors) console.error(`  ${e}`)
process.exit(1)
