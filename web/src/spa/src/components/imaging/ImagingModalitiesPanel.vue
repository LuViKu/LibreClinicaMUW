<script setup lang="ts">
/**
 * P3.4 — the study's imaging catalogue, editable.
 *
 * What the study photographs, which device performs each acquisition, and
 * which CRF box each one ticks when a file is filed against a visit. Before
 * this the answer lived in a migration, so onboarding a study meant a code
 * change; this is what makes that untrue.
 *
 * <p>Distinct from the measurement catalogue in the sibling tab: that one is
 * platform-wide and carries values per eye (BCVA, IOP, refraction). This is
 * per study and ticks a checklist.
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

import DenseTable from '@/components/DenseTable.vue'
import { useConfirm } from '@/composables/useConfirm'
import { useImagingModalitiesStore } from '@/stores/imagingModalities'
import type { BindingRole, ImagingModality } from '@/types/imagingModality'

const props = defineProps<{ studyOid: string | null }>()

const { t } = useI18n()
const confirm = useConfirm()
const store = useImagingModalitiesStore()

const ROLES: BindingRole[] = ['performed', 'not_performed_reason', 'initials']
const KINDS = ['e2e', 'dicom', 'image', 'other'] as const

const error = ref<string | null>(null)
/** Which row has its binding editor open. */
const editingBindingsFor = ref<number | null>(null)

const draft = ref({
  code: '',
  labelDe: '',
  labelEn: '',
  device: '',
  kindsAccepted: [] as string[],
  lateralityRequired: true,
  autoMatchAeTitle: '',
  ordinal: 0,
})

const bindingDraft = ref({
  role: 'performed' as BindingRole,
  laterality: 'OU' as 'OU' | 'OD' | 'OS',
  itemOid: '',
  performedValue: '1',
})

const active = computed(() => store.list.filter((m) => m.statusId === 1))
const retired = computed(() => store.list.filter((m) => m.statusId !== 1))

function reload(): void {
  if (props.studyOid) void store.load(props.studyOid)
}
onMounted(reload)
watch(() => props.studyOid, reload)

function resetDraft(): void {
  draft.value = {
    code: '', labelDe: '', labelEn: '', device: '',
    kindsAccepted: [], lateralityRequired: true, autoMatchAeTitle: '', ordinal: 0,
  }
}

async function run(fn: () => Promise<void>): Promise<void> {
  error.value = null
  try {
    await fn()
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  }
}

function add(): void {
  if (!props.studyOid) return
  void run(async () => {
    await store.create(props.studyOid as string, {
      code: draft.value.code,
      labelDe: draft.value.labelDe,
      labelEn: draft.value.labelEn,
      device: draft.value.device || null,
      kindsAccepted: draft.value.kindsAccepted.join(','),
      lateralityRequired: draft.value.lateralityRequired,
      autoMatchAeTitle: draft.value.autoMatchAeTitle || null,
      ordinal: Number(draft.value.ordinal) || 0,
    })
    resetDraft()
  })
}

async function retire(m: ImagingModality): Promise<void> {
  // Worth confirming: a retired modality stops ticking its box, and nobody
  // watching a form would see why.
  if (!(await confirm({ message: t('imagingModalities.retireConfirm', { code: m.code }), danger: true }))) {
    return
  }
  if (!props.studyOid) return
  void run(() => store.retire(props.studyOid as string, m.id))
}

function saveBinding(m: ImagingModality): void {
  if (!props.studyOid || !bindingDraft.value.itemOid.trim()) return
  void run(async () => {
    await store.putBinding(props.studyOid as string, m.id, { ...bindingDraft.value })
    bindingDraft.value = { role: 'performed', laterality: 'OU', itemOid: '', performedValue: '1' }
  })
}

function removeBinding(m: ImagingModality, bindingId: number): void {
  if (!props.studyOid) return
  void run(() => store.removeBinding(props.studyOid as string, m.id, bindingId))
}

function kindsLabel(m: ImagingModality): string {
  // An empty list is meaningful: the modality is on the checklist but no file
  // ever arrives for it (BCVA is the example).
  return m.kindsAccepted ? m.kindsAccepted : t('imagingModalities.checklistOnly')
}
</script>

<template>
  <section data-testid="imaging-modalities-panel">
    <p class="text-[13px] text-slate-500 mb-4">{{ t('imagingModalities.intro') }}</p>

    <p v-if="!studyOid" class="text-[13px] text-slate-500 italic">
      {{ t('imagingModalities.noStudy') }}
    </p>

    <template v-else>
      <p v-if="error" class="mb-3 text-[13px] text-rose-700" data-testid="imaging-error">{{ error }}</p>
      <p v-if="store.error" class="mb-3 text-[13px] text-rose-700">{{ store.error }}</p>
      <p v-if="store.isLoading" class="text-[13px] text-slate-500">{{ t('common.loading') }}</p>

      <DenseTable v-else-if="active.length || retired.length">
        <template #header>
          <tr class="border-b border-slate-200">
            <th scope="col" class="px-3 py-2 font-medium">{{ t('imagingModalities.code') }}</th>
            <th scope="col" class="px-3 py-2 font-medium">{{ t('imagingModalities.label') }}</th>
            <th scope="col" class="px-3 py-2 font-medium">{{ t('imagingModalities.device') }}</th>
            <th scope="col" class="px-3 py-2 font-medium">{{ t('imagingModalities.kinds') }}</th>
            <th scope="col" class="px-3 py-2 font-medium">{{ t('imagingModalities.bindings') }}</th>
            <th scope="col" class="px-3 py-2 font-medium">
              <span class="sr-only">{{ t('common.actions') }}</span>
            </th>
          </tr>
        </template>
          <template v-for="m in active" :key="m.id">
            <tr data-testid="imaging-modality-row">
              <td class="px-3 py-2 font-medium text-slate-700">{{ m.code }}</td>
              <td class="px-3 py-2">{{ m.labelDe }}</td>
              <td class="px-3 py-2">
                {{ m.device || '—' }}
                <span
                  v-if="m.autoMatchAeTitle"
                  class="ml-1 text-[11px] text-muw-teal-700"
                  :title="t('imagingModalities.autoMatchHint')"
                >AE {{ m.autoMatchAeTitle }}</span>
              </td>
              <td class="px-3 py-2 text-[12px]">{{ kindsLabel(m) }}</td>
              <td class="px-3 py-2 text-[12px]">
                <span v-if="m.bindings.length === 0" class="text-slate-500 italic">
                  {{ t('imagingModalities.noBindings') }}
                </span>
                <ul v-else class="space-y-0.5">
                  <li v-for="b in m.bindings" :key="b.id" class="flex items-center gap-1.5">
                    <span class="text-slate-500">{{ t(`imagingModalities.role.${b.role}`) }}</span>
                    <span v-if="b.laterality !== 'OU'" class="text-slate-500">{{ b.laterality }}</span>
                    <code class="text-[11px]">{{ b.itemOid }}</code>
                    <button
                      type="button"
                      class="text-[11px] text-rose-700 hover:underline"
                      :data-testid="`remove-binding-${b.id}`"
                      @click="removeBinding(m, b.id)"
                    >{{ t('common.remove') }}</button>
                  </li>
                </ul>
              </td>
              <td class="px-3 py-2 text-right whitespace-nowrap">
                <button
                  type="button"
                  class="text-[12px] text-muw-blue hover:underline mr-2"
                  :data-testid="`edit-bindings-${m.id}`"
                  @click="editingBindingsFor = editingBindingsFor === m.id ? null : m.id"
                >{{ t('imagingModalities.addBinding') }}</button>
                <button
                  type="button"
                  class="text-[12px] text-slate-600 hover:underline"
                  :data-testid="`retire-${m.id}`"
                  @click="retire(m)"
                >{{ t('imagingModalities.retire') }}</button>
              </td>
            </tr>
            <tr v-if="editingBindingsFor === m.id" :key="`edit-${m.id}`">
              <td colspan="6" class="px-3 bg-slate-50">
                <div class="flex flex-wrap items-end gap-2 py-2">
                  <label class="text-[12px]">
                    <span class="block text-slate-500">{{ t('imagingModalities.roleLabel') }}</span>
                    <select v-model="bindingDraft.role" class="px-2 py-1 rounded ring-1 ring-slate-200">
                      <option v-for="r in ROLES" :key="r" :value="r">
                        {{ t(`imagingModalities.role.${r}`) }}
                      </option>
                    </select>
                  </label>
                  <label class="text-[12px]">
                    <span class="block text-slate-500">{{ t('imagingModalities.eye') }}</span>
                    <select v-model="bindingDraft.laterality" class="px-2 py-1 rounded ring-1 ring-slate-200">
                      <option value="OU">OU</option>
                      <option value="OD">OD</option>
                      <option value="OS">OS</option>
                    </select>
                  </label>
                  <label class="text-[12px] flex-1 min-w-[12rem]">
                    <span class="block text-slate-500">{{ t('imagingModalities.itemOid') }}</span>
                    <input
                      v-model="bindingDraft.itemOid"
                      type="text"
                      class="w-full px-2 py-1 rounded ring-1 ring-slate-200"
                      :placeholder="t('imagingModalities.itemOidPlaceholder')"
                      :data-testid="`binding-oid-${m.id}`"
                    />
                  </label>
                  <label class="text-[12px]">
                    <span class="block text-slate-500">{{ t('imagingModalities.codedYes') }}</span>
                    <input
                      v-model="bindingDraft.performedValue"
                      type="text"
                      class="w-20 px-2 py-1 rounded ring-1 ring-slate-200"
                    />
                  </label>
                  <button
                    type="button"
                    class="px-3 py-1.5 text-[12px] font-medium rounded-lg bg-muw-blue text-white hover:bg-muw-blue-700"
                    :data-testid="`save-binding-${m.id}`"
                    @click="saveBinding(m)"
                  >{{ t('common.save') }}</button>
                </div>
              </td>
            </tr>
          </template>

          <tr v-for="m in retired" :key="`r-${m.id}`" class="opacity-60">
            <td class="px-3 py-2 font-medium">{{ m.code }}</td>
            <td class="px-3 py-2">{{ m.labelDe }}</td>
            <td colspan="4" class="px-3 py-2 text-[12px] italic">{{ t('imagingModalities.retired') }}</td>
          </tr>
      </DenseTable>

      <p v-else class="text-[13px] text-slate-500 italic" data-testid="imaging-empty">
        {{ t('imagingModalities.empty') }}
      </p>

      <!-- Add. Inline rather than a dialog: adding several acquisitions in a
           row is the normal case when a study is being set up. -->
      <form class="mt-5 p-4 rounded-xl ring-1 ring-slate-200 bg-white" @submit.prevent="add">
        <h3 class="text-[13px] font-semibold text-slate-700 mb-3">{{ t('imagingModalities.addTitle') }}</h3>
        <div class="flex flex-wrap gap-3">
          <label class="text-[12px]">
            <span class="block text-slate-500">{{ t('imagingModalities.code') }}</span>
            <input v-model="draft.code" type="text" required
                   class="px-2 py-1 rounded ring-1 ring-slate-200 w-40" data-testid="new-code" />
          </label>
          <label class="text-[12px]">
            <span class="block text-slate-500">{{ t('imagingModalities.labelDe') }}</span>
            <input v-model="draft.labelDe" type="text" required
                   class="px-2 py-1 rounded ring-1 ring-slate-200 w-48" data-testid="new-label-de" />
          </label>
          <label class="text-[12px]">
            <span class="block text-slate-500">{{ t('imagingModalities.labelEn') }}</span>
            <input v-model="draft.labelEn" type="text" required
                   class="px-2 py-1 rounded ring-1 ring-slate-200 w-48" data-testid="new-label-en" />
          </label>
          <label class="text-[12px]">
            <span class="block text-slate-500">{{ t('imagingModalities.device') }}</span>
            <input v-model="draft.device" type="text"
                   class="px-2 py-1 rounded ring-1 ring-slate-200 w-36" data-testid="new-device" />
          </label>
          <label class="text-[12px]">
            <span class="block text-slate-500">{{ t('imagingModalities.aeTitle') }}</span>
            <input v-model="draft.autoMatchAeTitle" type="text"
                   class="px-2 py-1 rounded ring-1 ring-slate-200 w-36"
                   :title="t('imagingModalities.autoMatchHint')" />
          </label>
          <label class="text-[12px]">
            <span class="block text-slate-500">{{ t('imagingModalities.ordinal') }}</span>
            <input v-model="draft.ordinal" type="number" min="0"
                   class="px-2 py-1 rounded ring-1 ring-slate-200 w-20" />
          </label>
        </div>
        <fieldset class="mt-3">
          <legend class="text-[12px] text-slate-500">{{ t('imagingModalities.kinds') }}</legend>
          <div class="flex flex-wrap gap-3 mt-1">
            <label v-for="k in KINDS" :key="k" class="text-[12px] inline-flex items-center gap-1">
              <input v-model="draft.kindsAccepted" type="checkbox" :value="k" />
              {{ t(`ingestInbox.kind.${k}`) }}
            </label>
            <label class="text-[12px] inline-flex items-center gap-1 ml-2">
              <input v-model="draft.lateralityRequired" type="checkbox" />
              {{ t('imagingModalities.lateralityRequired') }}
            </label>
          </div>
          <p class="mt-1 text-[11px] text-slate-500">{{ t('imagingModalities.kindsHint') }}</p>
        </fieldset>
        <button
          type="submit"
          class="mt-3 px-3 py-1.5 text-[12px] font-medium rounded-lg bg-muw-blue text-white hover:bg-muw-blue-700"
          data-testid="add-modality"
        >{{ t('imagingModalities.add') }}</button>
      </form>
    </template>
  </section>
</template>
