<script setup lang="ts">
import { computed } from 'vue'

/**
 * Phase E.3 primitive — Multi-step wizard with horizontal stepper.
 *
 * Used by Import-CRF-Data (Upload → Map → Preview & resolve → Commit),
 * the first-login profile-completion flow (per DR-014's login mockup
 * rework), and the e-signature confirmation flow.
 *
 * Owns the visual stepper + the body slot; navigation is driven by the
 * parent (the parent decides "can the user advance to step N+1" — that
 * lives in domain logic, not in the primitive). The parent calls
 * `v-model:step` to move between steps.
 *
 * The progressbar role on the stepper exposes the current step + total
 * for assistive tech.
 */

export interface WizardStep {
  /** Stable id used as the key. */
  id: string
  /** Human label shown under the step bullet. */
  title: string
  /**
   * Whether this step is allowed to be jumped to via clicking its
   * bullet. Default true. Steps the user hasn't reached yet typically
   * have this set to false.
   */
  clickable?: boolean
}

interface Props {
  /** Current step (zero-indexed). v-model:step. */
  step: number
  steps: WizardStep[]
}

const props = defineProps<Props>()

const emit = defineEmits<{
  'update:step': [value: number]
}>()

const onClickStep = (idx: number) => {
  const target = props.steps[idx]
  if (target?.clickable === false) return
  if (idx === props.step) return
  emit('update:step', idx)
}

const currentStep = computed(() => props.steps[props.step])
</script>

<template>
  <div class="space-y-6">
    <ol
      class="flex items-center gap-3 text-xs"
      role="progressbar"
      :aria-valuenow="step + 1"
      :aria-valuemin="1"
      :aria-valuemax="steps.length"
      :aria-valuetext="currentStep ? `Step ${step + 1} of ${steps.length}: ${currentStep.title}` : undefined"
    >
      <template v-for="(s, idx) in steps" :key="s.id">
        <li
          class="flex items-center gap-2"
          :class="[
            idx < step ? 'text-muw-teal-700 font-medium' : '',
            idx === step ? 'text-muw-blue font-semibold' : '',
            idx > step ? 'text-slate-500' : '',
          ]"
        >
          <button
            type="button"
            class="w-7 h-7 rounded-full inline-flex items-center justify-center font-semibold transition-colors"
            :class="[
              idx < step ? 'bg-muw-teal-100 text-muw-teal-700' : '',
              idx === step ? 'bg-muw-blue text-white' : '',
              idx > step ? 'border border-slate-300 bg-white text-slate-500' : '',
              s.clickable === false && idx !== step ? 'cursor-not-allowed opacity-60' : 'cursor-pointer hover:ring-2 hover:ring-muw-blue-100',
            ]"
            :disabled="s.clickable === false && idx !== step"
            :aria-current="idx === step ? 'step' : undefined"
            :aria-label="`Step ${idx + 1}: ${s.title}`"
            @click="onClickStep(idx)"
          >
            <template v-if="idx < step">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" aria-hidden="true">
                <polyline points="20 6 9 17 4 12" />
              </svg>
            </template>
            <template v-else>{{ idx + 1 }}</template>
          </button>
          <span>{{ s.title }}</span>
        </li>
        <span
          v-if="idx < steps.length - 1"
          class="flex-1 h-px"
          :class="idx < step ? 'bg-muw-teal-300' : idx === step ? 'bg-muw-blue-300' : 'bg-slate-200'"
          aria-hidden="true"
        />
      </template>
    </ol>

    <div>
      <slot :current-step="step" :current="currentStep" />
    </div>
  </div>
</template>
