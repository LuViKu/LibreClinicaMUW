<script setup lang="ts">
import { computed, ref } from 'vue'
import FieldLabel from '@/components/FieldLabel.vue'
import TextInput from '@/components/TextInput.vue'

/**
 * Phase E.3 primitive — Electronic-signature block.
 *
 * Implements the 21 CFR Part 11 §11.50 + ICH-GCP §8 signature pattern
 * used by Sign Subject, Sign Event, and Study Lock. Owns:
 *
 *  - The attestation paragraph (always rendered, always read aloud
 *    for screen readers — slot it with the role + subject context).
 *  - The user-name field (read-only, hydrated from the auth store
 *    by the parent).
 *  - The password re-challenge field, or — when SSO is the credential
 *    of record — a button-shaped CTA that bounces the user to the
 *    `/sso/reauth` endpoint with `forceAuthn=true` per DR-014 §4.
 *  - A required "I understand" acknowledgement checkbox.
 *
 * Per DR-014 §4 the SSO re-auth flow is feature-flagged off in
 * production until legal/regulatory ratifies proxy-mediated §11.50
 * compliance. The `signatureMode` prop selects between the two
 * variants; until then `local` is the only mode the parent should
 * pass.
 */

type SignatureMode = 'local' | 'sso'

export interface ESignaturePayload {
  /** Always the authenticated user's id — sourced from the auth store. */
  username: string
  /**
   * Present only in `local` mode. Sent over the auth API call;
   * NEVER persisted on the SPA side.
   */
  password?: string
  /** Set true once the user clicks the "Sign" button. */
  acknowledged: boolean
}

interface Props {
  /** Display username, read-only — sourced by parent from the auth store. */
  username: string
  /** Mode of signature. Default is local; SSO re-auth lands once §11.50 is ratified. */
  signatureMode?: SignatureMode
  /** Submit-button label, e.g. "Sign subject M-001". */
  submitLabel: string
  /** Optional disabled override (e.g. blocking preflight rows). */
  disabled?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  signatureMode: 'local',
  disabled: false,
})

const emit = defineEmits<{
  submit: [payload: ESignaturePayload]
}>()

const password = ref('')
const acknowledged = ref(false)

const canSign = computed(() => {
  if (props.disabled || !acknowledged.value) return false
  if (props.signatureMode === 'local') return password.value.length > 0
  return true
})

function sign() {
  if (!canSign.value) return
  emit('submit', {
    username: props.username,
    password: props.signatureMode === 'local' ? password.value : undefined,
    acknowledged: true,
  })
  password.value = ''
}
</script>

<template>
  <section class="bg-white border border-slate-200 rounded-muw p-5">
    <h2 class="text-xs font-semibold uppercase tracking-wider text-slate-500 mb-3">
      <slot name="heading">Attestation</slot>
    </h2>

    <div class="rounded-muw bg-slate-50 border border-slate-200 px-4 py-3 text-xs text-slate-700 leading-relaxed mb-4">
      <slot name="attestation">
        By signing below I attest that, to the best of my knowledge, the data is complete, accurate,
        and reflects the source documents. This electronic signature is recorded in the regulatory
        audit log per ICH-GCP and 21 CFR Part 11 §11.50.
      </slot>
    </div>

    <div class="grid grid-cols-2 gap-x-6 gap-y-4">
      <div>
        <FieldLabel for="esig-username" required>User name</FieldLabel>
        <TextInput
          id="esig-username"
          :model-value="username"
          readonly
        />
      </div>

      <div v-if="signatureMode === 'local'">
        <FieldLabel for="esig-password" required>Password</FieldLabel>
        <TextInput
          id="esig-password"
          v-model="password"
          type="password"
          autocomplete="current-password"
          placeholder="Re-enter to confirm"
        />
        <p class="mt-1 text-[11px] text-slate-500 leading-relaxed">
          Re-entering your password is the electronic-signature event recorded in the audit log.
        </p>
      </div>

      <div v-else class="col-span-1 flex flex-col justify-end">
        <p class="text-[11px] text-slate-500 leading-relaxed mb-2">
          You will be redirected to your institutional identity provider to re-authenticate.
          The return confirms the signature event in the audit log.
        </p>
      </div>
    </div>

    <label class="mt-4 flex items-start gap-2 text-xs text-slate-700">
      <input v-model="acknowledged" type="checkbox" class="mt-0.5 rounded text-muw-blue" />
      <span>
        <slot name="acknowledgement">
          I understand that signing locks the subject's CRFs against site edit until an
          administrator reset.
        </slot>
      </span>
    </label>

    <div class="mt-5 flex items-center justify-end gap-2">
      <slot name="cancel" />
      <button
        type="button"
        class="px-4 py-2 text-xs bg-muw-blue text-white rounded-md hover:bg-muw-blue-700 inline-flex items-center gap-1.5 font-medium disabled:opacity-50 disabled:cursor-not-allowed"
        :disabled="!canSign"
        @click="sign"
      >
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" aria-hidden="true">
          <path d="M20 11.08V12a10 10 0 1 1-5.93-9.14" />
          <polyline points="22 4 12 14.01 9 11.01" />
        </svg>
        {{ submitLabel }}
      </button>
    </div>
  </section>
</template>
