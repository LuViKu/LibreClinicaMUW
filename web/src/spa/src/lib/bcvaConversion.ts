/**
 * BCVA conversion utilities — decimal ⇄ ETDRS letters with German
 * clinical partial-line shorthand (`1,0p-2`, `0,8+2`).
 *
 * <p>The autorefractometer the MUW clinic uses emits BCVA in the
 * Snellen-equivalent DECIMAL scale (0.0–2.0). The nAMD module
 * (and the design references it ports) plot BCVA in ETDRS
 * LETTERS (0–100). The two scales are related by the Bailey-
 * Lovie LogMAR identity:
 *
 *   LogMAR    = -log10(decimal)
 *   letters   = round(85 + 50 * log10(decimal))   (with 1.0 → 85)
 *
 * <p>Clinical reality adds a SIGNED partial-line marker that the
 * nurse writes by hand. Two directions exist:
 *
 *   `1,0p-2` — patient read the 1.0 line but missed 2 optotypes
 *              → letters = 85 - 2 = 83
 *   `0,8+2`  — patient read the 0.8 line + 2 letters from the
 *              next better line → letters = 80 + 2 = 82
 *
 * <p>The partial is stored alongside decimal as a SIGNED integer in
 * [-4, +4]. ETDRS lines have 5 optotypes so |partial| ≥ 5 would be
 * the next decimal line and should be re-entered there.
 *
 * <p>Two consumers:
 *   - {@code PublicBcvaEntryController}'s portal — the nurse types
 *     `1,0p-2`; {@link parseBcvaInput} extracts `{decimal, partial}`;
 *     the commit payload sends structured values.
 *   - {@code useNamdVisitData} composable — fetches the BCVA
 *     timeline endpoint, threads `{decimal, partial}` through
 *     {@link decimalToLetters} to populate `NamdVisit.bcva`.
 */

/**
 * Bailey-Lovie / Holladay conversion + partial offset.
 *
 * @param decimal Snellen-equivalent decimal acuity (0.0–2.0). 0 → 0.
 * @param partial Signed offset in [-4, +4]. Negative = missed N
 *                optotypes on the read line; positive = read N
 *                extra letters from the next better line.
 * @returns letters in [0, 100].
 */
export function decimalToLetters(decimal: number, partial = 0): number {
  if (decimal <= 0) return 0
  const base = Math.round(85 + 50 * Math.log10(decimal))
  return Math.max(0, Math.min(100, base + partial))
}

/**
 * Round-trip: snap a letters value to the closest decimal-line
 * neighbour on the ETDRS ladder, capture the residual as the
 * signed partial. The "closest" decision uses min-|partial| with
 * ties broken toward the higher decimal (clinical convention —
 * report what the patient demonstrated they could see).
 *
 * <p>Examples (5 letters per ladder rung):
 *   83 letters → `{1.0, -2}`   (base 85 − 2; closer to 1.0 than to 0.8)
 *   82 letters → `{0.8, +2}`   (base 80 + 2; closer to 0.8 than to 1.0)
 *   78 letters → `{0.8, -2}`
 *
 * <p>Used for legacy LETTERS-preset visits whose nAMD-side display
 * wants a canonical decimal + partial for the tooltip.
 */
export function lettersToBcva(letters: number): { decimal: number; partial: number } {
  // ETDRS decimal ladder, descending. Walk pairs of adjacent rungs
  // (rung-above, rung-below) and pick whichever yields the smaller
  // absolute partial. Ties resolve toward the higher decimal.
  const ladder = [
    2.0, 1.6, 1.25, 1.0, 0.8, 0.63, 0.5, 0.4, 0.32, 0.25,
    0.2, 0.16, 0.125, 0.1, 0.08, 0.063, 0.05, 0.04, 0.025, 0.02,
  ]
  for (let i = 0; i < ladder.length - 1; i++) {
    const dAbove = ladder[i]!
    const dBelow = ladder[i + 1]!
    const lAbove = decimalToLetters(dAbove, 0)
    const lBelow = decimalToLetters(dBelow, 0)
    if (letters >= lBelow && letters <= lAbove) {
      const partialAbove = letters - lAbove // ≤ 0
      const partialBelow = letters - lBelow // ≥ 0
      const choice = Math.abs(partialAbove) <= Math.abs(partialBelow)
        ? { decimal: dAbove, partial: partialAbove }
        : { decimal: dBelow, partial: partialBelow }
      return {
        decimal: choice.decimal,
        partial: Math.max(-4, Math.min(4, choice.partial)),
      }
    }
  }
  // Below the ladder's floor — clamp to the lowest rung.
  return { decimal: 0.02, partial: 0 }
}

/**
 * Parse the German clinical shorthand into a structured pair.
 *
 * Accepted forms:
 *   - `1,0` / `1.0` / `0,8` — plain decimal (both comma + dot
 *     decimal separators)
 *   - `1,0p-2` / `1.0p-2` — partial minus N (preserves the `p-`
 *     prefix the operator typed)
 *   - `1,0-2` — partial minus N (bare `-N` shorthand)
 *   - `0,8+2` — partial plus N
 *
 * Returns null when the input is malformed or any field falls
 * outside the supported clinical range
 * ({@code decimal ∈ (0, 2]}, {@code partial ∈ [-4, +4]}).
 */
export function parseBcvaInput(
  raw: string,
): { decimal: number; partial: number } | null {
  const trimmed = raw.trim().toLowerCase()
  if (trimmed === '') return null
  // Capture groups: (1) decimal body, (2) sign token, (3) partial magnitude.
  const m = trimmed.match(/^(\d+(?:[.,]\d+)?)(?:(p-|-|\+)(\d))?$/)
  if (!m) return null
  const decimal = Number.parseFloat(m[1]!.replace(',', '.'))
  let partial = 0
  if (m[2] != null && m[3] != null) {
    const mag = Number.parseInt(m[3]!, 10)
    partial = m[2] === '+' ? mag : -mag
  }
  if (!Number.isFinite(decimal) || decimal <= 0 || decimal > 2) return null
  if (partial < -4 || partial > 4) return null
  return { decimal, partial }
}

/**
 * Render a stored {@code (decimal, partial)} pair in the canonical
 * German clinical form for display + audit:
 *
 *   - `partial === 0` → `"1,0"`
 *   - `partial < 0`   → `"1,0p-2"`  (negative arm uses the `p-`
 *                       prefix the operator wrote)
 *   - `partial > 0`   → `"0,8+2"`
 *
 * Decimals render with German comma as the decimal separator;
 * whole-number decimals get one decimal place (`1,0`, not `1`) so
 * the form reads as a chart line rather than an integer count.
 */
export function formatBcva(decimal: number, partial: number): string {
  // Render with minimal trailing zeroes so the canonical form matches
  // the clinical convention (`0,8` not `0,80`). Whole numbers get one
  // decimal place (`1,0` not `1`) so the reader sees a chart line, not
  // an integer count. JS's `toString` strips trailing zeroes for
  // non-integers; we only need to append `.0` when the decimal is a
  // whole number.
  const numStr = decimal.toString()
  const decStr = (numStr.includes('.') ? numStr : `${numStr}.0`).replace('.', ',')
  if (partial === 0) return decStr
  // `partial < 0` → string already contains the `-`; prepend `p` to
  // match the user's `p-N` notation.
  return partial < 0 ? `${decStr}p${partial}` : `${decStr}+${partial}`
}
