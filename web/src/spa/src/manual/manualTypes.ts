/* Types for the role-organised application manual (see manualData.en.ts /
   manualData.de.ts). Mirrors the Claude Design handoff content model. */

export type ManualAccent = 'blue' | 'coral' | 'sky' | 'teal' | 'tealdk'

/** Chapter role keys. 'common' is the shared getting-started chapter. */
export type ManualRoleKey =
  | 'common'
  | 'administrator'
  | 'data-manager'
  | 'monitor'
  | 'investigator'
  | 'crc'

export interface ManualMeta {
  title: string
  product: string
  subtitle: string
  version: string
  build: string
  /** Legacy field from the design export; the view ignores it and resolves
      screenshots against `${BASE_URL}manual/`. */
  shotBase?: string
}

export interface ManualRoleInfo {
  /** German label (primary). */
  label: string
  /** English label. */
  en: string
  accent: ManualAccent
}

export interface ManualCallout {
  kind: 'info' | 'accent' | 'warn'
  title?: string
  text: string
}

export interface ManualSubsection {
  title: string
  deutsch?: string
  goal?: string
  steps?: string[]
  notes?: string[]
}

export interface ManualSection {
  id: string
  num: string
  title: string
  /** The other-language term shown muted next to the title. */
  deutsch?: string
  route?: string
  /** Role chips shown on the section (design role keys). */
  roles?: ManualRoleKey[]
  goal?: string
  body?: string[]
  bullets?: string[]
  shotPre?: string
  shotPreCaption?: string
  steps?: string[]
  shot?: string
  shotCaption?: string
  /** Render the screenshot in a scroll-capped tall frame. */
  tall?: boolean
  shot2?: string
  shot2Caption?: string
  notes?: string[]
  sub?: { title: string; text: string }
  subsections?: ManualSubsection[]
}

export interface ManualChapter {
  id: string
  role: ManualRoleKey
  kicker: string
  title: string
  deutsch?: string
  oneLiner: string
  intro?: string[]
  callout?: ManualCallout
  sections: ManualSection[]
}

export interface Manual {
  meta: ManualMeta
  roles: Record<ManualRoleKey, ManualRoleInfo>
  chapters: ManualChapter[]
}
