<script setup lang="ts">
/**
 * Phase E.3 primitive — Timeline marker.
 *
 * Date / section markers between groups of events. Sits on the same
 * vertical rail as <TimelineEvent>; the white ring around the icon
 * masks the rail line behind the bullet.
 */
</script>

<template>
  <div class="relative flex items-center mb-3 -ml-8">
    <div class="w-6 h-6 rounded-full bg-white border border-slate-300 inline-flex items-center justify-center z-10">
      <slot name="icon">
        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" class="text-slate-500" aria-hidden="true">
          <rect x="3" y="4" width="18" height="18" rx="2" />
        </svg>
      </slot>
    </div>
    <div class="ml-3 text-xs font-semibold uppercase tracking-wider text-slate-500">
      <slot />
    </div>
  </div>
</template>
