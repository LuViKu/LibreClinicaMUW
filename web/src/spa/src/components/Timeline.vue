<script setup lang="ts">
/**
 * Phase E.3 primitive — Timeline.
 *
 * The Study Audit Log uses a vertical timeline with date markers + per-
 * event-type icons. Same shape works for any "what happened when"
 * surface (subject history, study lifecycle, scheduled-event list).
 *
 * Slot-driven so the consumer owns the event-type icon vocabulary
 * (signed = teal check, reason-for-change = coral pencil, SDV = sky
 * check, admin = amber cog). Default slot is the timeline body.
 *
 * Use `<TimelineMarker>` and `<TimelineEvent>` (small sibling
 * components) inside the default slot for date markers and event
 * cards. They share the same column rail so the visual alignment
 * stays consistent.
 */
</script>

<template>
  <div class="relative pl-8">
    <!-- Vertical rail behind the markers/events. -->
    <div class="absolute left-3 top-2 bottom-0 w-px bg-slate-200" aria-hidden="true"></div>
    <slot />
  </div>
</template>
