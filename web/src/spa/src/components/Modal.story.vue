<script setup lang="ts">
import { ref } from 'vue'
import Modal from './Modal.vue'
import StatusPill from './StatusPill.vue'
import FieldLabel from './FieldLabel.vue'
import TextInput from './TextInput.vue'
import HelperText from './HelperText.vue'

const queryOpen = ref(true)
const description = ref('Source document (clinic record dated 06-Oct-2020) lists weight as 55 kg, not 155 kg. Please verify and correct if needed.')
</script>

<template>
  <Story title="Primitives/Modal" :layout="{ type: 'single' }">
    <Variant title="Add Query — modal over context (Monitor)">
      <div class="relative min-h-[420px] p-6 bg-slate-50">
        <!-- Underlying page content the modal sits on top of -->
        <div class="text-xs text-slate-500 mb-2">M-001 · V1 Inclusion · München</div>
        <h2 class="text-lg font-semibold mb-3">Demographics (read-only behind modal)</h2>
        <dl class="bg-white border border-slate-200 rounded-muw p-4 space-y-2 text-xs max-w-md">
          <div class="flex items-baseline justify-between"><dt class="text-slate-500 font-mono">weight_kg</dt><dd class="font-mono">155</dd></div>
          <div class="flex items-baseline justify-between"><dt class="text-slate-500 font-mono">height_cm</dt><dd class="font-mono">168</dd></div>
        </dl>

        <button class="mt-4 px-3 py-1.5 text-xs bg-muw-blue text-white rounded-md hover:bg-muw-blue-700" @click="queryOpen = true">
          Open Add Query modal
        </button>

        <Modal v-model:open="queryOpen" labelled-by="modal-title-query" panel-class="max-w-2xl">
          <template #header>
            <div>
              <h3 id="modal-title-query" class="text-lg font-semibold tracking-tight">Add Note</h3>
              <p class="text-xs text-slate-500 mt-0.5">
                Subject <strong class="text-slate-700">M-001</strong>
                · CRF <strong class="text-slate-700">Demographics</strong>
                · item <span class="font-mono text-slate-700">weight_kg</span>
                · current value <span class="font-mono text-slate-700">155</span>
              </p>
            </div>
          </template>

          <div class="space-y-4">
            <div>
              <FieldLabel for="ntype" required>Note type</FieldLabel>
              <div class="grid grid-cols-4 gap-2 text-xs">
                <label class="flex items-center justify-center gap-1.5 px-3 py-2 rounded-md border border-rose-300 bg-rose-50 text-rose-800 cursor-pointer font-medium">
                  <input type="radio" name="ntype" class="text-rose-600" checked />
                  <StatusPill compact variant="danger">Query</StatusPill>
                </label>
                <label class="flex items-center justify-center gap-1.5 px-3 py-2 rounded-md border border-slate-200 hover:bg-slate-50 cursor-pointer text-slate-700">
                  <input type="radio" name="ntype" class="text-slate-600" />
                  <StatusPill compact variant="warning">Failed val.</StatusPill>
                </label>
                <label class="flex items-center justify-center gap-1.5 px-3 py-2 rounded-md border border-slate-200 hover:bg-slate-50 cursor-pointer text-slate-700">
                  <input type="radio" name="ntype" class="text-slate-600" />
                  <StatusPill compact variant="neutral">Annotation</StatusPill>
                </label>
                <label class="flex items-center justify-center gap-1.5 px-3 py-2 rounded-md border border-slate-200 hover:bg-slate-50 cursor-pointer text-slate-700">
                  <input type="radio" name="ntype" class="text-slate-600" />
                  <StatusPill compact variant="data-manager">Reason</StatusPill>
                </label>
              </div>
            </div>

            <div>
              <FieldLabel for="ndesc" required>Description</FieldLabel>
              <TextInput id="ndesc" v-model="description" />
              <HelperText>Visible to the investigator and recorded in the audit log.</HelperText>
            </div>
          </div>

          <template #footer>
            <div class="text-slate-500 text-xs">
              Audit trail entry will reference: monitor_demo · weight_kg
            </div>
            <div class="flex items-center gap-2">
              <button class="px-3 py-1.5 text-xs border border-slate-200 rounded-md bg-white hover:bg-slate-100 text-slate-700" @click="queryOpen = false">
                Cancel
              </button>
              <button class="px-4 py-1.5 text-xs bg-muw-blue text-white rounded-md hover:bg-muw-blue-700 font-medium">
                Submit query
              </button>
            </div>
          </template>
        </Modal>
      </div>
    </Variant>
  </Story>
</template>
