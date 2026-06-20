/**
 * nAMD Slice 5 (2026-06-20) — minimal declaration shim for
 * `@cornerstonejs/dicom-image-loader`. The package ships an UMD
 * bundle without a corresponding `.d.ts`; we only use a handful of
 * loose properties (`external`, `init`) at runtime, so a loose
 * `any`-shaped declaration is enough to make `vue-tsc` happy
 * without pulling in the full upstream type set.
 *
 * When upstream publishes types, drop this file.
 */
declare module '@cornerstonejs/dicom-image-loader' {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const loader: any
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  export const external: any
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  export function init(opts?: any): void
  export default loader
}

declare module 'dicom-parser' {
  // dicom-parser ships actual types upstream but they don't satisfy
  // the loose `external.dicomParser = ...` assignment site we use.
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const parser: any
  export default parser
}
