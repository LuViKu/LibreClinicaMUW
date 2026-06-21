<script setup lang="ts">
/**
 * 2026-06-21 user-feedback round 4 — nAMD CTA on EventDetailView.
 *
 * <p>The subject-detail card already routes operators to the
 * nAMD workspace, but the deployed MUW workflow goes:
 * SubjectDetail → EventDetail → CRF entry. Reaching the
 * workspace from EventDetail used to require drilling back to
 * the subject. This CTA mounts inside the
 * {@code event-detail.panels} slot so a single click from any
 * scheduled visit lands the operator in the workspace with the
 * subject pre-selected.
 *
 * <p>Context shape mirrors the SubjectDetail CTA — the host
 * passes the loaded event object, from which we extract the
 * subject label / oid for the workspace deep link.
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { I } from '../icons'

interface EventContext {
  subjectLabel?: string | null
  subjectOid?: string | null
}

interface Props {
  /**
   * The host's event payload. {@code event-detail.panels} entries
   * receive {@code event} as the context arg.
   */
  context?: EventContext | null
}

const props = withDefaults(defineProps<Props>(), { context: null })
const { t } = useI18n()
const router = useRouter()
const auth = useAuthStore()

const studyOid = computed(() => auth.user?.activeStudy?.oid ?? null)
const canOpen = computed(() => !!studyOid.value)
const subjectOid = computed(() => props.context?.subjectOid ?? null)
const subjectLabel = computed(() => props.context?.subjectLabel ?? null)

function open() {
  if (!studyOid.value) return
  void router.push({
    path: `/studies/${studyOid.value}/modules/namd`,
    query: subjectOid.value ? { subjectOid: subjectOid.value } : undefined,
  })
}
</script>

<template>
  <button
    type="button"
    data-testid="namd-event-detail-cta"
    :disabled="!canOpen"
    class="w-full text-left rounded-muw border border-slate-200 bg-white p-4 hover:border-muw-blue hover:shadow-muw-card transition disabled:opacity-50 mb-3"
    @click="open"
  >
    <div class="flex items-center gap-3">
      <span class="w-10 h-10 rounded-md bg-muw-blue-50 text-muw-blue inline-flex items-center justify-center">
        <span v-html="I.eye" />
      </span>
      <div class="flex-1">
        <div class="text-[13px] font-semibold text-slate-900">
          {{ t('studyModules.namd.openFromEvent') }}
        </div>
        <div class="text-[11px] text-slate-500">
          <template v-if="subjectLabel">
            {{ t('studyModules.namd.openFromEventHint', { subject: subjectLabel }) }}
          </template>
          <template v-else>
            {{ t('studyModules.namd.openHint') }}
          </template>
        </div>
      </div>
      <span class="text-muw-blue" v-html="I.arrowRight" />
    </div>
  </button>
</template>
