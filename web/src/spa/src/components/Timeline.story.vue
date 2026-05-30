<script setup lang="ts">
import Timeline from './Timeline.vue'
import TimelineMarker from './TimelineMarker.vue'
import TimelineEvent from './TimelineEvent.vue'
import DiffCard from './DiffCard.vue'
import StatusPill from './StatusPill.vue'
</script>

<template>
  <Story title="Primitives/Timeline" :layout="{ type: 'single' }">
    <Variant title="Study Audit Log — mixed event types">
      <div class="p-6 bg-slate-50">
        <Timeline>
          <TimelineMarker>Today · 28-May-2026</TimelineMarker>

          <TimelineEvent variant="signed">
            <template #icon>
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75">
                <path d="M20 11.08V12a10 10 0 1 1-5.93-9.14" />
                <polyline points="22 4 12 14.01 9 11.01" />
              </svg>
            </template>
            <div class="flex items-center justify-between text-xs">
              <div class="flex items-center gap-2">
                <span class="font-medium text-slate-900">Subject sign-off</span>
                <StatusPill compact variant="investigator">M-001</StatusPill>
                <span class="text-slate-500">by</span>
                <span class="font-mono text-slate-700">user_demo</span>
              </div>
              <span class="text-slate-500 font-mono">15:42 CEST</span>
            </div>
            <p class="text-xs text-slate-600 mt-1.5">All 11 CRFs locked. IP 10.0.4.21 · München.</p>
          </TimelineEvent>

          <TimelineEvent variant="reason-for-change">
            <template #icon>
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75">
                <path d="M12 20h9" />
                <path d="M16.5 3.5a2.121 2.121 0 0 1 3 3L7 19l-4 1 1-4Z" />
              </svg>
            </template>
            <div class="flex items-center justify-between text-xs">
              <div class="flex items-center gap-2">
                <span class="font-medium text-slate-900">Data edit · Reason for change</span>
                <span class="font-mono text-slate-600">M-001 · AE · ae_severity</span>
                <span class="text-slate-500">by</span>
                <span class="font-mono text-slate-700">user_demo</span>
              </div>
              <span class="text-slate-500 font-mono">14:31 CEST</span>
            </div>
            <div class="mt-2">
              <DiffCard>
                <template #before>Severe</template>
                <template #after>Moderate</template>
              </DiffCard>
            </div>
            <p class="text-xs text-slate-600 italic mt-2">"Re-graded after consult with PI."</p>
          </TimelineEvent>

          <TimelineEvent variant="sdv">
            <template #icon>
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75">
                <polyline points="20 6 9 17 4 12" />
              </svg>
            </template>
            <div class="flex items-center justify-between text-xs">
              <div class="flex items-center gap-2">
                <span class="font-medium text-slate-900">SDV verified</span>
                <span class="font-mono text-slate-600">M-002 · Demographics</span>
                <span class="text-slate-500">by</span>
                <span class="font-mono text-slate-700">monitor_demo</span>
              </div>
              <span class="text-slate-500 font-mono">11:08 CEST</span>
            </div>
          </TimelineEvent>

          <TimelineMarker>Yesterday · 27-May-2026</TimelineMarker>

          <TimelineEvent variant="query">
            <template #icon>
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75">
                <path d="M4 15a4 4 0 0 0 4 4h12V5a2 2 0 0 0-2-2H8a4 4 0 0 0-4 4z" />
              </svg>
            </template>
            <div class="flex items-center justify-between text-xs">
              <div class="flex items-center gap-2">
                <span class="font-medium text-slate-900">Query opened</span>
                <span class="font-mono text-slate-600">M-001 · weight_kg</span>
              </div>
              <span class="text-slate-500 font-mono">14:02 CEST</span>
            </div>
          </TimelineEvent>
        </Timeline>
      </div>
    </Variant>
  </Story>
</template>
