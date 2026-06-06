<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

/**
 * Phase E.6 — CRF authoring **live preview** view.
 *
 * <p>Renders the same widgets {@code CrfEntryView} uses but bound to
 * {@code useCrfPreviewStore} instead of {@code useCrfEntryStore}. No
 * persistence — Save / Mark complete are no-op stubs that flip
 * in-memory state only.
 *
 * <p>Used by the {@code CrfAuthoringWizard} via an inline overlay so
 * the operator can author + try out a CRF in the same window. The
 * view is also embeddable on its own (the store carries the schema +
 * the open flag) for non-wizard preview entry points later on.
 *
 * <p>Wired to render every CRF — the ophth preset, a vanilla
 * Demographics draft, anything the wizard hands it. The component
 * doesn't carry preset-specific assumptions.
 */

import StatusPill from '@/components/StatusPill.vue'
import FieldLabel from '@/components/FieldLabel.vue'
import TextInput from '@/components/TextInput.vue'
import SelectInput from '@/components/SelectInput.vue'
import HelperText from '@/components/HelperText.vue'
import ErrorText from '@/components/ErrorText.vue'
import CheckboxArrayInput from '@/components/CheckboxArrayInput.vue'

import { useCrfPreviewStore } from '@/stores/crfPreview'
import type { CrfEntryStatus, CrfItem } from '@/types/crf'

interface Props {
  /**
   * When {@code true} the view renders as a full-bleed overlay with
   * its own backdrop + close affordance. Defaults to {@code false}
   * (inline render) so the view can also be mounted as a standalone
   * preview page in a future routing pass.
   */
  asOverlay?: boolean
}
const props = withDefaults(defineProps<Props>(), { asOverlay: false })

const emit = defineEmits<{
  close: []
}>()

const { t } = useI18n()
const store = useCrfPreviewStore()

function statusVariant(s: CrfEntryStatus): 'success' | 'info' | 'warning' | 'neutral' {
  switch (s) {
    case 'complete':
    case 'locked':
      return 'success'
    case 'in-progress':
      return 'info'
    case 'not-started':
      return 'warning'
    default:
      return 'neutral'
  }
}

function statusLabel(s: CrfEntryStatus): string {
  return t(`crfEntry.status.${s}`)
}

function showError(item: CrfItem): string | null {
  return store.itemErrors[item.oid] ?? null
}

function inputBindings(item: CrfItem) {
  const raw = store.values[item.oid]
  return {
    id: `preview-item-${item.oid}`,
    modelValue: (raw == null ? '' : String(raw)) as string,
    error: showError(item) != null,
    'onUpdate:modelValue': (v: string) => store.setValue(item.oid, v),
  }
}

const heading = computed(() =>
  t('crfPreview.heading', { crfName: store.crfName || store.schema?.name || '' }),
)

function onClose(): void {
  store.close()
  emit('close')
}

function onFillSample(): void {
  store.fillSampleData()
}

function onReset(): void {
  store.reset()
}

function onSaveStub(): void {
  // No-op stub: flips local in-memory status only (per Phase E.6
  // preview spec — Save and Mark complete render their flow without
  // persisting anything).
  store.save()
}

function onMarkCompleteStub(): void {
  store.markComplete()
}

const rootClass = computed(() =>
  props.asOverlay
    ? 'fixed inset-0 z-[60] bg-slate-900/40 overflow-y-auto'
    : 'relative',
)
</script>

<template>
  <div v-if="store.isOpen && store.schema" :class="rootClass" role="dialog" aria-modal="true">
    <div
      :class="props.asOverlay
        ? 'mx-auto my-6 max-w-3xl bg-white border border-slate-200 rounded-muw shadow-xl'
        : 'bg-white border border-slate-200 rounded-muw'"
      data-testid="crf-preview-root"
    >
      <header
        class="flex items-start justify-between gap-4 px-6 py-4 border-b border-slate-200"
      >
        <div class="min-w-0">
          <div class="text-[10px] uppercase tracking-wider text-slate-400 font-semibold">
            {{ t('crfPreview.modeTell') }}
          </div>
          <h1 class="text-base font-semibold tracking-tight truncate">
            {{ heading }}
          </h1>
          <div class="mt-1 flex items-center gap-2 flex-wrap">
            <StatusPill :variant="statusVariant(store.status)">
              {{ statusLabel(store.status) }}
            </StatusPill>
          </div>
        </div>
        <div class="flex items-center gap-2 shrink-0">
          <button
            type="button"
            class="px-3 py-1.5 text-xs border border-slate-300 rounded-md bg-white hover:bg-slate-50 text-slate-700"
            data-testid="crf-preview-fill-sample"
            @click="onFillSample"
          >
            {{ t('crfPreview.action.fillSampleData') }}
          </button>
          <button
            type="button"
            class="px-3 py-1.5 text-xs border border-slate-300 rounded-md bg-white hover:bg-slate-50 text-slate-700"
            data-testid="crf-preview-reset"
            @click="onReset"
          >
            {{ t('crfPreview.action.reset') }}
          </button>
          <button
            type="button"
            class="px-3 py-1.5 text-xs border border-slate-300 rounded-md bg-white hover:bg-slate-50 text-slate-700"
            data-testid="crf-preview-close"
            @click="onClose"
          >
            {{ t('crfPreview.action.close') }}
          </button>
        </div>
      </header>

      <!-- Preview-mode banner — always visible so the operator can't mistake
           the preview for real data entry. -->
      <div
        class="mx-6 mt-4 rounded-md border border-amber-200 bg-amber-50 px-3 py-2 text-xs text-amber-900"
        role="status"
        data-testid="crf-preview-banner"
      >
        {{ t('crfPreview.banner') }}
      </div>

      <form
        class="px-6 py-5 space-y-6"
        novalidate
        @submit.prevent="onMarkCompleteStub"
      >
        <section
          v-for="section in store.schema.sections"
          :id="`preview-${section.oid}`"
          :key="section.oid"
          class="bg-white border border-slate-200 rounded-muw p-5"
        >
          <h2 class="text-xs font-semibold uppercase tracking-wider text-slate-500 mb-1">
            {{ section.title }}
          </h2>
          <p v-if="section.instructions" class="text-[11px] text-slate-500 mb-4 leading-relaxed">
            {{ section.instructions }}
          </p>

          <div class="space-y-4">
            <!-- Phase E.6 polish-runtime — skip items currently hidden
                 by show-when. The preview store evaluates the same rule
                 the runtime store uses (shared evaluator in
                 components/showWhen.ts). -->
            <div v-for="item in section.items.filter((it) => !store.isItemHidden(it.oid))" :key="item.oid">
              <FieldLabel :for="`preview-item-${item.oid}`" :required="item.required">
                {{ item.label }}
              </FieldLabel>

              <template v-if="item.dataType === 'select-one' && item.options">
                <SelectInput v-bind="inputBindings(item)">
                  <option :value="undefined">— {{ t('common.search') }} —</option>
                  <option v-for="opt in item.options" :key="opt.code" :value="opt.code">
                    {{ opt.label }}
                  </option>
                </SelectInput>
              </template>

              <template v-else-if="item.dataType === 'select-multi' && item.options">
                <CheckboxArrayInput
                  :id-prefix="`preview-item-${item.oid}`"
                  :model-value="(store.values[item.oid] as string[] | null | undefined) ?? []"
                  :options="item.options"
                  :error="showError(item) != null"
                  @update:model-value="(v: string[]) => store.setValue(item.oid, v)"
                />
              </template>

              <template v-else-if="item.dataType === 'boolean'">
                <label class="inline-flex items-center gap-2 text-xs text-slate-700">
                  <input
                    :id="`preview-item-${item.oid}`"
                    type="checkbox"
                    :checked="Boolean(store.values[item.oid])"
                    class="rounded border-slate-300 text-muw-blue focus:ring-muw-blue"
                    @change="store.setValue(item.oid, ($event.target as HTMLInputElement).checked)"
                  />
                  <span>{{ t('crfPreview.boolean.true') }}</span>
                </label>
              </template>

              <template v-else-if="item.dataType === 'file'">
                <div class="text-[11px] italic text-slate-500 border border-dashed border-slate-300 rounded-md px-3 py-3">
                  {{ t('crfPreview.file.stub') }}
                </div>
              </template>

              <template v-else-if="item.dataType === 'integer' || item.dataType === 'real'">
                <input
                  :id="`preview-item-${item.oid}`"
                  :value="store.values[item.oid] ?? ''"
                  :aria-invalid="showError(item) != null || undefined"
                  type="number"
                  :min="item.min"
                  :max="item.max"
                  :step="item.dataType === 'integer' ? 1 : 0.1"
                  class="w-full px-3 py-2 border rounded-md focus:outline-none transition-colors muw-focus"
                  :class="showError(item)
                    ? 'border-rose-400 bg-rose-50/40 focus:border-rose-500 focus:ring-2 focus:ring-rose-100'
                    : 'border-slate-300 focus:border-muw-blue focus:ring-2 focus:ring-muw-blue-100'"
                  @input="store.setValue(item.oid, ($event.target as HTMLInputElement).value === '' ? null : Number(($event.target as HTMLInputElement).value))"
                />
              </template>

              <template v-else-if="item.dataType === 'date' || item.dataType === 'partial-date'">
                <TextInput
                  v-bind="inputBindings(item)"
                  type="text"
                  :placeholder="item.dataType === 'date' ? 'YYYY-MM-DD' : 'YYYY-MM'"
                  inputmode="numeric"
                />
              </template>

              <template v-else>
                <TextInput v-bind="inputBindings(item)" type="text" />
              </template>

              <HelperText v-if="item.helper">{{ item.helper }}</HelperText>
              <ErrorText v-if="showError(item)">{{ showError(item) }}</ErrorText>
            </div>
          </div>
        </section>

        <!-- Stub action row — Save + Mark complete render so the operator
             sees the flow chrome, but they only mutate in-memory state. -->
        <div
          class="flex items-center justify-end gap-2 border-t border-slate-200 pt-4"
        >
          <button
            type="button"
            class="px-3 py-2 text-xs border border-slate-200 rounded-md bg-white hover:bg-slate-50 text-slate-700"
            data-testid="crf-preview-save-stub"
            @click="onSaveStub"
          >
            {{ t('crfPreview.action.saveStub') }}
          </button>
          <button
            type="submit"
            class="px-4 py-2 text-xs bg-muw-blue text-white rounded-md hover:bg-muw-blue-700 font-medium disabled:opacity-50"
            data-testid="crf-preview-mark-complete-stub"
            :disabled="!store.isComplete"
          >
            {{ t('crfPreview.action.markCompleteStub') }}
          </button>
        </div>
      </form>
    </div>
  </div>
</template>
