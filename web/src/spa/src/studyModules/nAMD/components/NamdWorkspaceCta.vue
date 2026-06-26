<script setup lang="ts">
/**
 * nAMD workspace — SubjectDetailView injection card.
 *
 * Single CTA mounted inside the {@code subject-detail.workspace} slot
 * (declared in the module manifest, consumed by the framework agent's
 * surgical edit to SubjectDetailView.vue). One click navigates the
 * operator to the workspace, preserving the subject's OID in the route
 * query so the data composable can refresh against the right subject.
 *
 * Renders nothing when the host doesn't pass a subject — host gates the
 * injection slot at the v-for level, but defensive guard keeps the
 * Storybook story usable without props.
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { I } from '../icons'

interface Props {
  /** Subject OID — surfaced by the host's slot context. */
  subjectOid?: string | null
  /** Subject label — rendered inline so the operator confirms identity. */
  subjectLabel?: string | null
}

const props = withDefaults(defineProps<Props>(), { subjectOid: null, subjectLabel: null })
const { t } = useI18n()
const router = useRouter()
const auth = useAuthStore()

const studyOid = computed(() => auth.user?.activeStudy?.oid ?? null)
const canOpen = computed(() => !!studyOid.value)

function open() {
  if (!studyOid.value) return
  // Thread the subject label via route query (was showing the internal
  // numeric id, e.g. "Patient 106"). The composable prefers subjectLabel.
  const query: Record<string, string> = {}
  if (props.subjectOid) query.subjectOid = props.subjectOid
  if (props.subjectLabel) query.subjectLabel = props.subjectLabel
  void router.push({
    path: `/studies/${studyOid.value}/modules/namd`,
    query: Object.keys(query).length ? query : undefined,
  })
}
</script>

<template>
  <button
    type="button"
    data-testid="namd-workspace-cta"
    :disabled="!canOpen"
    class="w-full text-left rounded-muw border border-slate-200 bg-white p-4 hover:border-muw-blue hover:shadow-muw-card transition disabled:opacity-50"
    @click="open"
  >
    <div class="flex items-center gap-3">
      <span class="w-10 h-10 rounded-md bg-muw-blue-50 text-muw-blue inline-flex items-center justify-center">
        <span v-html="I.eye" />
      </span>
      <div class="flex-1">
        <div class="text-[13px] font-semibold text-slate-900">
          {{ t('studyModules.namd.open') }}
        </div>
        <div class="text-[11px] text-slate-500">
          {{ t('studyModules.namd.openHint') }}
        </div>
      </div>
      <span class="text-muw-blue" v-html="I.arrowRight" />
    </div>
  </button>
</template>
