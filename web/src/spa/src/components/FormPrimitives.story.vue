<script setup lang="ts">
import { ref } from 'vue'
import FieldLabel from './FieldLabel.vue'
import TextInput from './TextInput.vue'
import SelectInput from './SelectInput.vue'
import HelperText from './HelperText.vue'
import ErrorText from './ErrorText.vue'

const subjectId = ref('M-042')
const secondaryId = ref('')
const gender = ref<string>('')
</script>

<template>
  <Story title="Primitives/Form fields" :layout="{ type: 'single' }">
    <Variant title="Add Subject — Identification section (canonical use)">
      <div class="max-w-2xl bg-white border border-slate-200 rounded-muw p-6 space-y-4">
        <div>
          <FieldLabel for="subject-id" required>Study Subject ID</FieldLabel>
          <TextInput id="subject-id" v-model="subjectId" placeholder="e.g. M-042" />
          <HelperText>The identifier used in source documents. Cannot be edited after save.</HelperText>
        </div>

        <div>
          <FieldLabel for="secondary-id">Secondary ID</FieldLabel>
          <TextInput id="secondary-id" v-model="secondaryId" placeholder="Optional" />
          <ErrorText>Never include name, hospital ID, social security number, or any other identifying data.</ErrorText>
        </div>

        <div>
          <FieldLabel for="gender" required>Gender</FieldLabel>
          <SelectInput id="gender" v-model="gender">
            <option value="">— select —</option>
            <option value="f">Female</option>
            <option value="m">Male</option>
          </SelectInput>
        </div>
      </div>
    </Variant>

    <Variant title="TextInput — disabled / readonly / error states">
      <div class="max-w-md space-y-4 p-6">
        <div>
          <FieldLabel for="ti-default">Default</FieldLabel>
          <TextInput id="ti-default" model-value="default value" />
        </div>
        <div>
          <FieldLabel for="ti-readonly">Read-only</FieldLabel>
          <TextInput id="ti-readonly" model-value="from SSO" readonly />
        </div>
        <div>
          <FieldLabel for="ti-disabled">Disabled</FieldLabel>
          <TextInput id="ti-disabled" model-value="cannot edit" disabled />
        </div>
        <div>
          <FieldLabel for="ti-error" required>Invalid</FieldLabel>
          <TextInput id="ti-error" model-value="too short" error />
          <ErrorText>Must be at least 8 characters.</ErrorText>
        </div>
      </div>
    </Variant>

    <Variant title="TextInput — with prefix icon (search)">
      <div class="max-w-md p-6">
        <TextInput id="search" model-value="" placeholder="Search subjects, sites, CRFs…" type="search" inputmode="search">
          <template #prefix-icon>
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75">
              <circle cx="11" cy="11" r="7" />
              <line x1="20" y1="20" x2="17" y2="17" />
            </svg>
          </template>
        </TextInput>
      </div>
    </Variant>
  </Story>
</template>
