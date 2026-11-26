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
import type { BindingRole, ImagingModality, ItemAnswer, ItemAnswers } from '@/types/imagingModality'

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

function emptyBindingDraft() {
  return {
    role: 'performed' as BindingRole,
    laterality: 'OU' as 'OU' | 'OD' | 'OS',
    itemOid: '',
    performedValue: '',
  }
}
const bindingDraft = ref(emptyBindingDraft())

/**
 * The answers of the item the draft names, so the form offers them instead of
 * a guess. HealthAEye's "performed" items are coded Y/N, and the old default
 * `1` filled none of them. Null until loaded, and for a free-text item.
 */
const itemAnswers = ref<ItemAnswers | null>(null)
/** The OID `itemAnswers` (or `itemMissing`) belongs to. */
const answersFor = ref('')
const itemMissing = ref(false)

/** The answers to choose from, when the draft is a performed box on a coded item. */
const codedAnswers = computed<ItemAnswer[] | null>(() => {
  const a = itemAnswers.value
  if (bindingDraft.value.role !== 'performed' || !a || !a.coded) return null
  return answersFor.value === bindingDraft.value.itemOid.trim() ? a.answers : null
})

/**
 * The one answer that reads as "yes", if exactly one does. The label decides:
 * a code alone says nothing (`1` may as well mean "right eye"), so a code
 * counts only when the answer has no label of its own.
 */
function yesAnswer(a: ItemAnswers): string {
  const hits = a.answers.filter((x) => {
    const text = x.text.trim()
    const value = x.value.trim()
    if (/^(y|j|yes|ja|durchgeführt|performed)$/i.test(text)) return true
    return text === value && /^(y|j|yes|ja|true)$/i.test(value)
  })
  return hits.length === 1 ? hits[0].value : ''
}

async function loadAnswers(): Promise<void> {
  const oid = bindingDraft.value.itemOid.trim()
  if (!props.studyOid || !oid || bindingDraft.value.role !== 'performed') {
    itemAnswers.value = null
    answersFor.value = ''
    itemMissing.value = false
    return
  }
  if (answersFor.value === oid) return
  try {
    const a = await store.itemAnswers(props.studyOid, oid)
    itemAnswers.value = a
    itemMissing.value = false
    if (a.coded && !a.answers.some((x) => x.value === bindingDraft.value.performedValue)) {
      bindingDraft.value.performedValue = yesAnswer(a)
    }
  } catch {
    itemAnswers.value = null
    itemMissing.value = true
  }
  answersFor.value = oid
}
watch(() => bindingDraft.value.role, () => { void loadAnswers() })

function answerLabel(a: ItemAnswer): string {
  return a.value === a.text ? a.value : `${a.value} – ${a.text}`
}

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

async function saveBinding(m: ImagingModality): Promise<void> {
  if (!props.studyOid || !bindingDraft.value.itemOid.trim()) return
  // The answers may not be loaded yet when Save is the first thing clicked.
  await loadAnswers()
  const choices = codedAnswers.value
  if (choices && !choices.some((a) => a.value === bindingDraft.value.performedValue)) {
    error.value = t('imagingModalities.chooseAnswer')
    return
  }
  void run(async () => {
    const body = { ...bindingDraft.value }
    await store.putBinding(props.studyOid as string, m.id, {
      role: body.role,
      laterality: body.laterality,
      itemOid: body.itemOid.trim(),
      // Only a performed box writes its value; a free-text one may leave it
      // to the server's default.
      performedValue: body.role === 'performed' && body.performedValue ? body.performedValue : undefined,
    })
    bindingDraft.value = emptyBindingDraft()
    itemAnswers.value = null
    answersFor.value = ''
    itemMissing.value = false
  })
}

function removeBinding(m: ImagingModality, bindingId: number): void {
  if (!props.studyOid) return
  void run(() => store.removeBinding(props.studyOid as string, m.id, bindingId))
}

/** Result line of the last catch-up, or null. */
const applyResult = ref<string | null>(null)
const applying = ref(false)

/**
 * Tick "performed" for images filed before their modality had a binding.
 * Writes CRF values (as the system account), so it is confirmed first.
 */
async function applyToFiled(): Promise<void> {
  if (!props.studyOid) return
  if (!(await confirm({ message: t('imagingModalities.applyPerformed.confirm') }))) return
  applying.value = true
  applyResult.value = null
  error.value = null
  try {
    const res = await store.applyPerformed(props.studyOid)
    const written = res.outcomes.WRITTEN ?? 0
    const kept = (res.outcomes.ALREADY_SET ?? 0) + (res.outcomes.OPERATOR_VALUE_KEPT ?? 0)
      + (res.outcomes.OTHER_SOURCE_KEPT ?? 0)
    const invalid = res.outcomes.INVALID_VALUE ?? 0
    const line = t('imagingModalities.applyPerformed.result', { files: res.files, written, kept })
    // A binding whose value is no answer of its item ticks nothing; saying so
    // is what tells the administrator which bindings to correct.
    applyResult.value = invalid > 0
      ? `${line} ${t('imagingModalities.applyPerformed.invalid', { invalid })}`
      : line
  } catch (e) {
    error.value = e instanceof Error ? e.message : t('imagingModalities.applyPerformed.failed')
  } finally {
    applying.value = false
  }
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
                    <span v-if="b.role === 'performed'" class="text-slate-500">= {{ b.performedValue }}</span>
                    <span
                      v-if="b.valueValid === false"
                      class="text-amber-700"
                      :data-testid="`binding-invalid-${b.id}`"
                    >{{ t('imagingModalities.valueInvalid') }}</span>
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
                      @change="loadAnswers"
                    />
                  </label>
                  <!-- Only a performed box writes its value. On a coded item the
                       value must be one of its answers, so they are offered. -->
                  <label v-if="bindingDraft.role === 'performed'" class="text-[12px]">
                    <span class="block text-slate-500">{{ t('imagingModalities.codedYes') }}</span>
                    <select
                      v-if="codedAnswers"
                      v-model="bindingDraft.performedValue"
                      class="px-2 py-1 rounded ring-1 ring-slate-200"
                      :data-testid="`binding-value-${m.id}`"
                    >
                      <option value="" disabled>{{ t('imagingModalities.chooseAnswerOption') }}</option>
                      <option v-for="a in codedAnswers" :key="a.value" :value="a.value">{{ answerLabel(a) }}</option>
                    </select>
                    <input
                      v-else
                      v-model="bindingDraft.performedValue"
                      type="text"
                      placeholder="1"
                      class="w-20 px-2 py-1 rounded ring-1 ring-slate-200"
                      :data-testid="`binding-value-${m.id}`"
                    />
                  </label>
                  <button
                    type="button"
                    class="px-3 py-1.5 text-[12px] font-medium rounded-lg bg-muw-blue text-white hover:bg-muw-blue-700 disabled:opacity-50"
                    :data-testid="`save-binding-${m.id}`"
                    :disabled="!!codedAnswers && !bindingDraft.performedValue"
                    @click="saveBinding(m)"
                  >{{ t('common.save') }}</button>
                </div>
                <p v-if="itemMissing" class="pb-2 text-[11px] text-amber-700" :data-testid="`binding-item-missing-${m.id}`">
                  {{ t('imagingModalities.itemNotFound') }}
                </p>
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
            <!-- DR-039 — not a file kind: the marker that OCT volumes filed
                 here are analysed, so the visit plan may set tasks on it. -->
            <label class="text-[12px] inline-flex items-center gap-1" :title="t('imagingModalities.octMarkerHint')">
              <input v-model="draft.kindsAccepted" type="checkbox" value="oct" data-testid="imaging-modality-oct-marker" />
              {{ t('imagingModalities.octMarker') }}
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

      <div class="mt-6 flex flex-wrap items-center gap-3">
        <button
          type="button"
          class="px-3 py-1.5 text-[12px] font-medium rounded-lg border border-muw-blue text-muw-blue hover:bg-muw-blue-50 disabled:opacity-50"
          data-testid="apply-performed"
          :disabled="applying"
          @click="applyToFiled"
        >{{ t('imagingModalities.applyPerformed.button') }}</button>
        <p v-if="applyResult" class="text-[13px] text-slate-600" role="status" data-testid="apply-performed-result">
          {{ applyResult }}
        </p>
      </div>
    </template>
  </section>
</template>
