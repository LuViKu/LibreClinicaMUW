/**
 * Pluggable study-module SPI — TypeScript contract.
 *
 * Background: previously the nAMD-specific workspace was hand-coded
 * into shared views (SubjectDetailView, EventDetailView, RetinalMetricsView,
 * CrfEntryView). The next batch of institutional studies (GA, RVO,
 * observational AMD) would have to copy-paste and re-merge every slice
 * each time. This file declares the single SPA-only contract every
 * study module implements; the registry + host views dispatch on
 * {@link StudyModuleManifest.protocolType}, matched case-insensitively
 * against the existing {@code study.protocol_type} column.
 *
 * SPA-only: backend controllers stay protocol-agnostic (DR-0?? captured
 * in the modernization plan). Module behaviour runs in the browser; the
 * backend only needs to surface {@code protocolType} on the active
 * study, which it already does on the {@code Study} DTO and now also
 * on {@code MeDto.ActiveStudyDto}.
 */
import type { RouteRecordRaw } from 'vue-router'
import type { Component } from 'vue'

/** Identifies a module — matches {@code study.protocol_type}, normalised via toUpperCase(). */
export type StudyModuleId = string

/**
 * Stable injection-slot ids. Host views opt into a slot by calling
 * {@code useStudyModuleStore().injectionsFor(slotId)} and rendering
 * each returned entry's {@code component}. The slot id is opaque to
 * the host — it does NOT know which module is filling it.
 */
export type InjectionSlotId =
  | 'subject-detail.tabs'
  | 'subject-detail.workspace'
  | 'event-detail.panels'
  | 'event-detail.actions'
  | 'crf-entry.banner'
  | 'nav.modules'

export interface InjectionEntry {
  /** Stable id within the slot, used for keying + de-dup. */
  key: string
  /** i18n key for the label (resolved at render time). */
  labelKey: string
  /** Vue SFC to mount. */
  component: Component
  /** Optional predicate — receives slot context (e.g. the event) and decides whether to mount. */
  predicate?: (ctx: unknown) => boolean
}

export interface StudyModuleManifest {
  /** Matches {@code study.protocol_type} — normalised via toUpperCase(). */
  protocolType: StudyModuleId
  /** i18n key for the human-readable module label. */
  labelKey: string
  /** Routes the module adds — prefixed by the registry boot with /studies/:studyOid/modules/<id>. */
  routes: RouteRecordRaw[]
  /** Optional view-injection entries by slot id. */
  injections?: Partial<Record<InjectionSlotId, InjectionEntry[]>>
  /** Lazy i18n loader — merged into vue-i18n on activation. */
  loadI18n?: () => Promise<{ de: Record<string, unknown>; en: Record<string, unknown> }>
  /** Optional client-side visit scheduler hook (T&E etc). */
  visitScheduler?: (ctx: VisitSchedulerContext) => VisitSchedulerHint | null
}

export interface VisitSchedulerContext {
  currentVisitOid: string
  lastFluidResult?: { irf: number; srf: number; ped: number }
  defaultIntervalDays: number
}

export interface VisitSchedulerHint {
  intervalDays: number
  rationale: string
}
