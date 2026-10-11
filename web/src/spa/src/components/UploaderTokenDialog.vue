<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

import Modal from '@/components/Modal.vue'
import FieldLabel from '@/components/FieldLabel.vue'

import { useNotificationsStore } from '@/stores/notifications'
import { uploadPageLink } from '@/lib/uploaderToken'

/**
 * The one moment an uploader token is shown.
 *
 * A token is returned when an uploader is created and when its token is
 * replaced, and never again: the server keeps only a hash. So this dialog
 * is built around not losing it by accident:
 *
 *  - the token sits in a read-only field in a monospaced face, selected on
 *    focus, with a copy button whose result is told by a notice;
 *  - the dialog says in plain words that the token is not shown again and
 *    where to enter it (the settings dialog of the uploader on the
 *    acquisition PC, never the command line);
 *  - beside the token it offers the link for the browser upload page,
 *    `<origin><base>upload#token=<token>`, in a field of its own with its own
 *    copy button. The token sits in the link's fragment, which a browser never
 *    sends to a server, so it is in no access log; the page takes it out of
 *    the address bar when it opens. The link is the token under another
 *    name, shown once like it and to be handled like it;
 *  - it closes only through its own button, which stays disabled until the
 *    person ticks "I have saved the token". Escape, a click on the scrim and
 *    the header's cross would all lose the token without the person
 *    deciding to, so this dialog has no header slot (which is what carries
 *    the cross) and turns the other two off.
 *
 * The token is a prop of the page's own state and nothing else holds it:
 * when the page lets go of it on close, it is gone from the document too.
 */
interface Props {
  open: boolean
  /** The uploader's name, for the title. */
  uploaderName: string
  /** `mwu_` plus 43 base64url characters. Shown once. */
  token: string
  /** A first token for a new uploader, or the replacement of an existing one. */
  mode: 'created' | 'replaced'
  /** The location the token belongs to, named in the note about the link. */
  locationName?: string
}
const props = withDefaults(defineProps<Props>(), { locationName: '' })
const emit = defineEmits<{ 'update:open': [value: boolean] }>()

const { t } = useI18n()
const notifications = useNotificationsStore()

const saved = ref(false)
const tokenInput = ref<HTMLInputElement | null>(null)
const linkInput = ref<HTMLInputElement | null>(null)
const copyButton = ref<HTMLButtonElement | null>(null)

/** Where the browser upload page of this app lives, with the token in its fragment. */
const uploadLink = computed(() => uploadPageLink(window.location.origin, import.meta.env.BASE_URL, props.token))

// Each time the dialog opens it starts unconfirmed, with the cursor on the
// copy button: copying is what almost everyone does first.
watch(
  () => props.open,
  async (isOpen) => {
    if (!isOpen) return
    saved.value = false
    await nextTick()
    copyButton.value?.focus()
  },
  { immediate: true },
)

function selectField(field: HTMLInputElement | null) {
  field?.select()
}

/** Copies the token or the link; where there is no clipboard, selects the field so that Ctrl+C does the job. */
async function copy(what: 'token' | 'link') {
  const value = what === 'token' ? props.token : uploadLink.value
  const field = what === 'token' ? tokenInput.value : linkInput.value
  try {
    await navigator.clipboard.writeText(value)
    notifications.success(t(what === 'token' ? 'uploaders.token.copied' : 'uploaders.token.linkCopied'))
  } catch {
    // No clipboard (an insecure context, a refused permission): say so.
    field?.focus()
    selectField(field)
    notifications.info(t(what === 'token' ? 'uploaders.token.copyFailed' : 'uploaders.token.linkCopyFailed'))
  }
}

function close() {
  if (!saved.value) return
  emit('update:open', false)
}
</script>

<template>
  <Modal
    :open="props.open"
    labelled-by="uploader-token-title"
    panel-class="max-w-xl"
    :close-on-scrim-click="false"
    :close-on-escape="false"
    @update:open="(v) => { if (!v) close() }"
  >
    <h2 id="uploader-token-title" class="text-lg font-semibold tracking-tight" data-testid="uploader-token-title">
      {{
        props.mode === 'replaced'
          ? t('uploaders.token.replacedTitle', { name: props.uploaderName })
          : t('uploaders.token.createdTitle', { name: props.uploaderName })
      }}
    </h2>

    <div
      class="mt-3 flex items-start gap-2 rounded-md border border-amber-300 bg-amber-50 px-3 py-2 text-xs text-amber-900"
      data-testid="uploader-token-warning"
    >
      <svg
        class="mt-0.5 h-4 w-4 shrink-0"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        stroke-width="1.75"
        aria-hidden="true"
      >
        <path d="M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z" />
        <line x1="12" x2="12" y1="9" y2="13" />
        <line x1="12" x2="12.01" y1="17" y2="17" />
      </svg>
      <span class="leading-relaxed">{{ t('uploaders.token.warning') }}</span>
    </div>

    <p class="mt-3 text-xs leading-relaxed text-slate-600" data-testid="uploader-token-where">
      {{ t('uploaders.token.whereTo') }}
    </p>

    <div class="mt-4">
      <FieldLabel for="uploader-token-value">{{ t('uploaders.token.label') }}</FieldLabel>
      <div class="flex items-stretch gap-2">
        <input
          id="uploader-token-value"
          ref="tokenInput"
          type="text"
          readonly
          spellcheck="false"
          autocomplete="off"
          :value="props.token"
          class="min-w-0 flex-1 rounded-md border border-slate-300 bg-slate-50 px-3 py-2 font-mono text-[13px] text-slate-900 muw-focus"
          data-testid="uploader-token-value"
          @focus="selectField(tokenInput)"
          @click="selectField(tokenInput)"
        />
        <button
          ref="copyButton"
          type="button"
          class="shrink-0 rounded-md border border-slate-300 bg-white px-3 py-2 text-xs font-medium text-slate-700 hover:bg-slate-100 muw-focus"
          :aria-label="t('uploaders.token.copyAria')"
          data-testid="uploader-token-copy"
          @click="copy('token')"
        >
          {{ t('uploaders.token.copy') }}
        </button>
      </div>
    </div>

    <!-- The same token as a link for the browser upload page: in the fragment, so it reaches no access log. -->
    <div class="mt-4">
      <FieldLabel for="uploader-link-value">{{ t('uploaders.token.linkLabel') }}</FieldLabel>
      <div class="flex items-stretch gap-2">
        <input
          id="uploader-link-value"
          ref="linkInput"
          type="text"
          readonly
          spellcheck="false"
          autocomplete="off"
          :value="uploadLink"
          class="min-w-0 flex-1 rounded-md border border-slate-300 bg-slate-50 px-3 py-2 font-mono text-[12px] text-slate-900 muw-focus"
          data-testid="uploader-link-value"
          @focus="selectField(linkInput)"
          @click="selectField(linkInput)"
        />
        <button
          type="button"
          class="shrink-0 rounded-md border border-slate-300 bg-white px-3 py-2 text-xs font-medium text-slate-700 hover:bg-slate-100 muw-focus"
          :aria-label="t('uploaders.token.linkCopyAria')"
          data-testid="uploader-link-copy"
          @click="copy('link')"
        >
          {{ t('uploaders.token.linkCopy') }}
        </button>
      </div>
      <p class="mt-1.5 text-[11px] leading-relaxed text-slate-500" data-testid="uploader-link-note">
        {{
          props.locationName
            ? t('uploaders.token.linkNote', { location: props.locationName })
            : t('uploaders.token.linkNoteHere')
        }}
      </p>
    </div>

    <label class="mt-4 flex items-start gap-2 text-sm text-slate-800">
      <input
        v-model="saved"
        type="checkbox"
        class="mt-0.5 h-4 w-4 rounded border-slate-300 muw-focus"
        data-testid="uploader-token-saved"
      />
      <span>{{ t('uploaders.token.saved') }}</span>
    </label>

    <template #footer>
      <p
        v-if="!saved"
        id="uploader-token-close-hint"
        class="text-xs text-slate-500"
        data-testid="uploader-token-close-hint"
      >
        {{ t('uploaders.token.closeHint') }}
      </p>
      <div v-else />
      <button
        type="button"
        class="px-4 py-1.5 text-xs bg-muw-blue text-white rounded-md hover:bg-muw-blue-700 font-medium disabled:opacity-50 muw-focus"
        :disabled="!saved"
        :aria-describedby="saved ? undefined : 'uploader-token-close-hint'"
        data-testid="uploader-token-close"
        @click="close"
      >
        {{ t('uploaders.token.close') }}
      </button>
    </template>
  </Modal>
</template>
