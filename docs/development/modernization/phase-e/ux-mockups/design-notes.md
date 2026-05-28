# Phase E — UX mockup design notes

Static HTML + Tailwind sketches for one critical workflow per role, informed by the [Phase E feature catalogue](../README.md). **Browser-previewable directly** — open any `*.html` file from this folder; no build step.

## Status

- **Exploratory** — meant to provoke design conversations, not to be implementation-ready
- **Framework-agnostic** — no React/Vue commitment; the markup can be ported to either
- **No backend hooks** — all data shown is mock data drawn from the LCDemo study captured in the catalogue
- **No CSS files yet** — Tailwind via Play CDN per page, custom rules inline. Once the direction is approved, lift to a real Tailwind build.

## Visual lineage

Style cues drawn from modern data-dense clinical / EDC UIs (REDCap, Castor EDC, Medidata Rave). The legacy LibreClinica UI used colored icon-only status indicators, framed tables, and a fixed-width blue header bar — readable but visually heavy and inaccessible (icon-only state without text). The mockups keep the dense table semantics but modernise:

- **Status as pill badges** (icon + short label) instead of icon-only
- **Sticky table headers** + per-column filter row below header
- **Side rail + breadcrumb context** instead of separate study/site/user widgets scattered across the header
- **Calmer typography** (Inter, 14px base, tighter line-height in tables)
- **More whitespace around primary actions**, still dense inside data tables
- **Accessible color contrast** — all primary text ≥ 4.5:1; status pills include text so colour alone doesn't carry meaning

## Design tokens

### Colours

| Token | Tailwind | Hex | Use |
|---|---|---|---|
| Primary | `blue-600` | `#2563eb` | Brand, primary buttons, links, active state |
| Primary-hover | `blue-700` | `#1d4ed8` | Hover/focus on primary |
| Success | `emerald-600` | `#059669` | Completed, signed, SDV verified |
| Warning | `amber-500` | `#f59e0b` | Data entry started, scheduled |
| Danger | `rose-600` | `#e11d48` | Errors, invalid, missing required |
| Info | `sky-500` | `#0ea5e9` | New / informational state |
| Neutral text | `slate-900` | `#0f172a` | Primary text |
| Muted text | `slate-500` | `#64748b` | Helper text, metadata |
| Border | `slate-200` | `#e2e8f0` | Table dividers, card outlines |
| Surface | `slate-50` | `#f8fafc` | Sidebar, table-row alt |

### Typography

- **Font:** [Inter](https://fonts.google.com/specimen/Inter), weights 400 / 500 / 600 / 700
- **Base size:** 14 px (Tailwind `text-sm`) — clinical data density demands smaller than the typical 16 px web default
- **Table rows:** 13 px (`text-[13px]`), 36 px row height
- **Headings:** 18 / 20 / 24 px (`text-lg` / `text-xl` / `text-2xl`)

### Spacing

Tailwind default 4-pt scale. Cards `p-4`, dense table cells `px-3 py-2`, primary buttons `px-4 py-2`.

### Component vocabulary

- **Status pill** — colored background, icon + label, `rounded-full px-2.5 py-0.5 text-xs font-medium`
- **Filter row** — input under each filterable column header; Apply / Clear at the end
- **Action button group** — icon-only buttons for inline actions (View / Edit / Sign), with title attribute for tooltips
- **Sticky table header** — `sticky top-0 bg-slate-50 z-10`
- **Side rail** — `w-56 border-r border-slate-200 bg-slate-50 px-3 py-4 text-sm`, role-conditional sections
- **Top breadcrumb bar** — Study › Site › Subject style, with action menu on the right (Change Study/Site, profile)

## Screens included

### Round 1 — one critical workflow per role

| Role | Screen | File | Replaces legacy |
|---|---|---|---|
| All | Landing index | [index.html](index.html) | n/a |
| Investigator | Subject Matrix | [investigator-subject-matrix.html](investigator-subject-matrix.html) | `/ListStudySubjects` |
| Investigator | Add Subject | [investigator-add-subject.html](investigator-add-subject.html) | `/AddNewSubject` |
| Investigator | CRF data entry | [investigator-crf-entry.html](investigator-crf-entry.html) | `/InitialDataEntry` |
| Monitor | SDV table | [monitor-sdv.html](monitor-sdv.html) | `/pages/viewAllSubjectSDVtmp` |
| Monitor | Read-only CRF view | [monitor-crf-readonly.html](monitor-crf-readonly.html) | `/ViewSectionDataEntry` |
| Data Manager | Build Study | [dm-build-study.html](dm-build-study.html) | `/pages/studymodule` |
| Data Manager | Update Event Definition | [dm-update-event-definition.html](dm-update-event-definition.html) | `/UpdateEventDefinition?id=…` |

### Round 2 — cross-role essentials

| Role | Screen | File | Replaces legacy |
|---|---|---|---|
| Inv · Mon · DM | Notes & Discrepancies | [notes-discrepancies.html](notes-discrepancies.html) | `/ViewNotes?module=submit` |
| Inv · Mon · DM | View Events | [view-events.html](view-events.html) | `/ViewStudyEvents` |
| Investigator | View Subject (M-001) | [view-subject.html](view-subject.html) | `/ViewStudySubject?id=…` |
| Investigator | Schedule Event | [schedule-event.html](schedule-event.html) | `/CreateNewStudyEvent` |
| Data Manager | Manage Users | [dm-manage-users.html](dm-manage-users.html) | `/ListStudyUser` |

The Notes & Discrepancies mockup shows the **Monitor** view, with an in-place role switcher chip in the top bar and a "What changes per role" expandable in the side panel — so the per-role power differences (Investigator can't Close; Monitor is the only role that can; DM has full visibility plus delete-cascades-close) are visible in one place rather than spread across three near-identical mockups.

### Round 3 — sign-off, modal pattern, audit log, login, CRF designer, import wizard

| Role | Screen | File | Replaces legacy |
|---|---|---|---|
| Investigator | Sign Subject (M-001) | [investigator-sign-subject.html](investigator-sign-subject.html) | `/SignStudySubject?id=…` |
| Monitor | Add Query modal | [monitor-add-query.html](monitor-add-query.html) | `/CreateDiscrepancyNote` (current JS popup) |
| Inv · Mon · DM | Study Audit Log | [study-audit-log.html](study-audit-log.html) | `/ViewAuditLog` |
| (no auth) | Login + forced password change | [login.html](login.html) | `/pages/login/login` (HTTP 500 in current build — see [known-issues.md](../known-issues.md)) |
| Data Manager | Create / Edit CRF | [dm-create-crf.html](dm-create-crf.html) | `/CreateCRF` + `/EditCRF` (currently Excel upload only) |
| Data Manager | Import CRF Data wizard | [dm-import-crf-data.html](dm-import-crf-data.html) | `/ImportCRFData` (multi-step) |

**Patterns introduced in Round 3:**

- **Confirmation-with-preflight** ([investigator-sign-subject.html](investigator-sign-subject.html)) — Pre-flight checklist (pass/warn/info rows) → casebook snapshot → e-signature attestation block → primary action. Use for any "this is a one-way regulatory action" screen (study lock, sign-off, archive).
- **Modal over context** ([monitor-add-query.html](monitor-add-query.html)) — Underlying read-only CRF stays dimly visible (`bg-slate-900/40` scrim, `z-40`); modal panel sits centered at `pt-16`. Tight modal header (title + breadcrumb chips + close). Segmented note-type control instead of a dropdown. Audit-trail preview line in the footer left rail.
- **Timeline vs table view** ([study-audit-log.html](study-audit-log.html)) — Activity stream with date markers and per-event-type icons (sign-off = emerald, reason-for-change = violet diff card, SDV = sky, admin = amber). Toggle to a flat table view for export workflows. The diff card (before/after side-by-side) is the reusable primitive for any "what changed" recap.
- **Auth card + multi-step onboarding** ([login.html](login.html)) — Centred-card pattern, branded MUW lockup, inline error region, LDAP secondary action. Right pane shows the first-login forced-change variant: stepper (Password → 2FA → Terms), live password-strength meter, account-recovery sub-card. (Production: one pane renders at a time. Both shown here for review.)
- **Item-grid designer with live preview** ([dm-create-crf.html](dm-create-crf.html)) — Left rail = sections; main = inline-editable item grid (drag-handle, OID + response-type cell editors, item-group separators for repeating vs non-repeating); right rail = response-set editor and per-item properties. Bottom of main shows a live Investigator-view preview that updates as you edit. Designed so DM never leaves the page to author an item.
- **Wizard with stepper + preview-before-commit** ([dm-import-crf-data.html](dm-import-crf-data.html)) — Compact horizontal stepper (Upload → Map → **Preview & resolve** → Commit), validation summary cards (Ready / Overwrite / Error / Warning), filter chips, per-row diff cells with action selector, mandatory reason-for-change note before commit. Reusable for any "diff between staged and committed state" screen.

## What's intentionally NOT in these mockups

To keep iteration cheap, these are out of scope for the first pass:

- **Real interactivity** beyond hover/focus styles — no working filters, no actual form validation, no popovers
- **Empty / error / loading states** for tables and forms
- **Mobile / responsive breakpoints** — desktop-first at 1440 px; clinical-trial data entry is desktop-bound today
- **Internationalisation** — English copy only (the demo also uses a German CRF; localisation can come once layout settles)
- **Accessibility audit** — base contrast is OK, but ARIA roles, keyboard traps, screen reader nav, etc. need a dedicated pass
- **Animation / transitions**
- **Print styles** (CRF PDF, audit log PDF) — significant in clinical context, deferred

## Next steps once direction is approved

1. Extract shared components (header bar, side rail, status pill, table primitives, form primitives) into a small component library
2. Pick the framework (React vs Vue — see [MIGRATION.md](../../../../../MIGRATION.md))
3. Build a real Tailwind config with the design tokens above, lock the colour palette
4. Cover the remaining screens from the catalogue (the deferred items: Notes & Discrepancies, Schedule Event, View Events, ListCRF, Manage Sites, Manage Users, Test Rule, etc.)
5. Wire to a stub API layer so designers and devs can iterate on real navigation
6. First accessibility audit + first usability test with actual MUW Ophthalmology investigators
