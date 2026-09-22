<script setup lang="ts">
/**
 * Page header — the one place a page says where it is.
 *
 * Three things used to say it: the top bar's breadcrumb, the page's own
 * eyebrow line, and (on some pages) a second in-page trail or a "back" link.
 * They disagreed in language and wrapped into each other at laptop widths.
 * Now the top bar carries the section (the highlighted primary-nav pill) and
 * this header carries what lies *below* the section:
 *
 *  - `trail` — the ancestors of the current page, as links, for pages inside a
 *    hierarchy (Studienteilnehmer › M-007 › V1 Inclusion). The current page is
 *    the H1, so it is never repeated as a crumb.
 *  - `eyebrow` — a plain descriptive line for flat pages that want one.
 *
 * A page passes one or the other. Labels are already translated by the view;
 * this component stays i18n-agnostic apart from the landmark's name.
 */
import { useI18n } from 'vue-i18n'

export interface TrailItem {
  label: string
  to: string
}

interface Props {
  title?: string
  eyebrow?: string
  trail?: TrailItem[]
  /** Heading size — the default matches the working screens. */
  titleClass?: string
}

const props = withDefaults(defineProps<Props>(), {
  title: '',
  eyebrow: '',
  trail: () => [],
  titleClass: 'text-xl font-semibold tracking-tight',
})

const { t } = useI18n()
</script>

<template>
  <header :class="props.title || $slots.title ? 'mb-5' : 'mb-2'" data-testid="page-header">
    <nav
      v-if="props.trail.length"
      :aria-label="t('pageHeader.trail')"
      class="flex flex-wrap items-center gap-1 text-xs text-slate-500 mb-1"
      data-testid="page-trail"
    >
      <template v-for="(item, i) in props.trail" :key="i">
        <RouterLink :to="item.to" class="text-slate-600 hover:text-slate-900 hover:underline">{{ item.label }}</RouterLink>
        <svg v-if="i < props.trail.length - 1" class="w-3 h-3 text-slate-400" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
          <polyline points="9 18 15 12 9 6" />
        </svg>
      </template>
    </nav>
    <div v-else-if="props.eyebrow" class="text-xs text-slate-500 mb-1" data-testid="page-eyebrow">{{ props.eyebrow }}</div>

    <div v-if="props.title || $slots.title" class="flex items-start justify-between gap-4 flex-wrap">
      <div class="min-w-0">
        <h1 :class="[props.titleClass, 'flex items-center gap-3 flex-wrap']">
          <slot name="title">{{ props.title }}</slot>
          <slot name="badge" />
        </h1>
        <slot name="intro" />
      </div>
      <div v-if="$slots.actions" class="flex items-center gap-2 shrink-0">
        <slot name="actions" />
      </div>
    </div>
    <slot />
  </header>
</template>
