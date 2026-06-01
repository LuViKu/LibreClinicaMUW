<script setup lang="ts">
import { ref } from 'vue'
import ESignatureBlock from './ESignatureBlock.vue'
import type { ESignaturePayload } from './ESignatureBlock.vue'

const lastPayload = ref<ESignaturePayload | null>(null)
function onSubmit(p: ESignaturePayload) { lastPayload.value = p }
</script>

<template>
  <Story title="Primitives/ESignatureBlock" :layout="{ type: 'single' }">
    <Variant title="Local password challenge — Sign Subject M-001">
      <div class="p-6 max-w-2xl bg-slate-50 space-y-3">
        <ESignatureBlock
          username="user_demo"
          signature-mode="local"
          submit-label="Sign subject M-001"
          @submit="onSubmit"
        >
          <template #attestation>
            By signing below, I, <strong>Dr. user_demo (Investigator, site München)</strong>, attest
            that to the best of my knowledge the data captured for subject <strong>M-001</strong> is
            complete, accurate, and reflects the source documents. This electronic signature is
            recorded in the regulatory audit log per ICH-GCP and 21 CFR Part 11 §11.50.
          </template>
        </ESignatureBlock>
        <pre v-if="lastPayload" class="text-[11px] bg-slate-900 text-slate-50 p-3 rounded">{{ JSON.stringify(lastPayload, null, 2) }}</pre>
      </div>
    </Variant>

    <Variant title="SSO re-auth (flagged off until §11.50 ratified)">
      <div class="p-6 max-w-2xl bg-slate-50">
        <ESignatureBlock
          username="m.mueller"
          signature-mode="sso"
          submit-label="Re-authenticate with MedUni Wien"
          @submit="onSubmit"
        />
      </div>
    </Variant>

    <Variant title="Disabled (blocking preflight row)">
      <div class="p-6 max-w-2xl bg-slate-50">
        <ESignatureBlock
          username="user_demo"
          signature-mode="local"
          submit-label="Sign subject M-001"
          :disabled="true"
        />
      </div>
    </Variant>
  </Story>
</template>
