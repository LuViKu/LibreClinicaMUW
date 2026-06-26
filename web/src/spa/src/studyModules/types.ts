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
import type { EventDetailDto } from '@/types/event'
import type { SubjectDetail } from '@/types/subject'

/** Identifies a module — matches {@code study.protocol_type}, normalised via toUpperCase(). */
export type StudyModuleId = string

/**
 * Per-slot context map. Each host view that consumes a slot passes the
 * context value to {@code entry.predicate(ctx)} so modules can decide
 * whether to mount without hard-coding per-host conditionals.
 *
 * <p>Slots that have no useful context (banner / nav — always rendered
 * if active module provides an entry) use {@code null}. Slots whose
 * host data may be loading or absent use {@code T | null} so the
 * predicate can short-circuit on the null pre-load state.
 *
 * <p>Adding a slot: pick a stable id, add the entry here with its
 * context type, then have the host view consume via
 * {@code injectionsFor(slotId)}. The slot id is part of the public
 * contract — call sites in modules type-check against the entry shape.
 */
export interface SlotContextMap {
  /** Sub-tab on SubjectDetailView. Receives the loaded subject or null. */
  'subject-detail.tabs': SubjectDetail | null
  /** Top-of-view CTA on SubjectDetailView. Receives the loaded subject or null. */
  'subject-detail.workspace': SubjectDetail | null
  /** Bottom-of-view panel on EventDetailView. Receives the loaded event-detail DTO or null. */
  'event-detail.panels': EventDetailDto | null
  /** Action button on EventDetailView header. Receives the loaded event-detail DTO or null. */
  'event-detail.actions': EventDetailDto | null
  /** Top-of-form banner on CrfEntryView. No context (banner mounts unconditionally). */
  'crf-entry.banner': null
  /** Entry in TopBar's primary nav. No context (entry renders whenever the active study matches). */
  'nav.modules': null
}

/**
 * Stable injection-slot ids — extension points views opt into. Derived
 * from {@link SlotContextMap} so adding a slot in one place automatically
 * surfaces in both the map AND the union.
 */
export type InjectionSlotId = keyof SlotContextMap

/**
 * One injection entry parameterised by the slot it targets. The
 * {@code predicate} parameter is statically typed against
 * {@link SlotContextMap}{@code [S]} so modules get type-checking on the
 * context they receive without having to read the host source.
 *
 * <p>Defaulting {@code S} to {@code InjectionSlotId} keeps array
 * destinations like {@code InjectionEntry[]} accepting entries from any
 * slot — useful for the store's mass {@code injectionsFor} return type.
 */
export interface InjectionEntry<S extends InjectionSlotId = InjectionSlotId> {
  /** Stable id within the slot, used for keying + de-dup. */
  key: string
  /** i18n key for the label (resolved at render time). */
  labelKey: string
  component: Component
  /** Optional predicate — receives slot context and decides whether to mount. */
  predicate?: (ctx: SlotContextMap[S]) => boolean
}

export interface StudyModuleManifest {
  /** Matches {@code study.protocol_type} — normalised via toUpperCase(). */
  protocolType: StudyModuleId
  /** i18n key for the human-readable module label. */
  labelKey: string
  /** Routes the module adds — prefixed by the registry boot with /studies/:studyOid/modules/<id>. */
  routes: RouteRecordRaw[]
  /**
   * View-injection entries by slot id. Each slot's array is typed
   * against {@link SlotContextMap}{@code [slotId]} so module-side
   * predicate declarations are type-checked.
   */
  injections?: {
    [S in InjectionSlotId]?: InjectionEntry<S>[]
  }
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
