<script setup lang="ts">
import { ref } from 'vue'
import Wizard from './Wizard.vue'
import type { WizardStep } from './Wizard.vue'

const step = ref(2)

const importSteps: WizardStep[] = [
  { id: 'upload',   title: 'Upload',             clickable: true  },
  { id: 'map',      title: 'Map',                clickable: true  },
  { id: 'preview',  title: 'Preview & resolve',  clickable: true  },
  { id: 'commit',   title: 'Commit',             clickable: false },
]

const firstLoginSteps: WizardStep[] = [
  { id: 'profile', title: 'Confirm profile' },
  { id: 'terms',   title: 'Accept terms'    },
]
</script>

<template>
  <Story title="Primitives/Wizard" :layout="{ type: 'single' }">
    <Variant title="Import CRF Data — 4-step (Preview & resolve active)">
      <div class="p-6 bg-slate-50">
        <Wizard v-model:step="step" :steps="importSteps">
          <template #default="{ current }">
            <div class="rounded-muw border border-slate-200 bg-white p-6">
              <h3 class="text-lg font-semibold tracking-tight mb-2">
                {{ current?.title }}
              </h3>
              <p class="text-xs text-slate-500 mb-4">
                Slot-driven body — the parent renders whatever step content it
                needs. The wizard just owns the stepper.
              </p>

              <div class="flex items-center justify-between">
                <button
                  class="px-3 py-1.5 text-xs border border-slate-200 rounded-md bg-white hover:bg-slate-100 text-slate-700"
                  :disabled="step === 0"
                  @click="step = Math.max(0, step - 1)"
                >
                  Back
                </button>
                <button
                  class="px-4 py-1.5 text-xs bg-muw-blue text-white rounded-md hover:bg-muw-blue-700 font-medium"
                  :disabled="step === importSteps.length - 1"
                  @click="step = Math.min(importSteps.length - 1, step + 1)"
                >
                  Continue
                </button>
              </div>
            </div>
          </template>
        </Wizard>
      </div>
    </Variant>

    <Variant title="First-login (2-step, Confirm profile active)">
      <div class="p-6 max-w-sm bg-slate-50">
        <Wizard :step="0" :steps="firstLoginSteps">
          <template #default="{ current }">
            <p class="text-sm text-slate-700">{{ current?.title }} body…</p>
          </template>
        </Wizard>
      </div>
    </Variant>
  </Story>
</template>
