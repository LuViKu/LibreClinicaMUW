<script setup lang="ts">
/**
 * Phase E.3 primitive — Inline error text.
 *
 * Bound to a field via `aria-describedby` from the parent — pair with
 * the `error` flag on TextInput / SelectInput so the input itself sets
 * `aria-invalid`. Default slot is the error message string; the warning
 * icon is rendered automatically.
 */
</script>

<template>
  <p
    class="mt-1 text-[11px] text-rose-700 flex items-start gap-1"
    role="alert"
  >
    <svg
      width="14"
      height="14"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      stroke-width="1.75"
      class="mt-0.5 shrink-0"
      aria-hidden="true"
    >
      <path d="M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z" />
      <line x1="12" x2="12" y1="9" y2="13" />
      <line x1="12" x2="12.01" y1="17" y2="17" />
    </svg>
    <span><slot /></span>
  </p>
</template>
