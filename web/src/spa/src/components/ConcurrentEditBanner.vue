<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { LockProbe } from '@/types/crf'

/**
 * Phase E.6 crf-entry-advanced — concurrent-edit warning banner.
 *
 * <p>Rendered above the form when another session has heart-beated
 * within the TTL window. Soft warning only — the underlying save
 * endpoint still accepts writes (last-write-wins). The banner gives
 * the user a chance to coordinate before stomping on the other
 * session's edits.
 */

interface Props {
  probe: LockProbe | null
}

const props = defineProps<Props>()
const { t } = useI18n()

const visible = computed(() => {
  return props.probe != null
      && !props.probe.sameUser
      && props.probe.lastEditorName != null
      && props.probe.lastEditorName.trim().length > 0
})

const lastSeenText = computed(() => {
  const ts = props.probe?.lastSeenAt
  if (!ts) return ''
  try {
    const d = new Date(ts)
    return d.toLocaleTimeString()
  } catch {
    return ts
  }
})
</script>

<template>
  <div
    v-if="visible && probe"
    class="rounded-md border border-amber-300 bg-amber-50 px-3 py-2 text-xs text-amber-900 mb-4 flex items-start gap-2"
    role="alert"
    aria-live="polite"
  >
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" aria-hidden="true" class="mt-0.5 flex-shrink-0">
      <path d="M12 9v4M12 17h.01" />
      <path d="M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z" />
    </svg>
    <div class="flex-1">
      <div class="font-semibold">{{ t('crfEntry.concurrentEdit.bannerTitle') }}</div>
      <div class="text-amber-800">
        <strong>{{ probe.lastEditorName }}</strong>
        <span v-if="lastSeenText"> · {{ t('crfEntry.concurrentEdit.lastSeen', { at: lastSeenText }) }}</span>
      </div>
    </div>
  </div>
</template>
