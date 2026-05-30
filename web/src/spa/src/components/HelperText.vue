<script setup lang="ts">
/**
 * Phase E.3 primitive — Helper text.
 *
 * The slim slate-500 line of context beneath a form control. Pair with
 * <ErrorText> when both context and an error need to render — Vue's
 * default behaviour is to show both, which is intended (the helper text
 * explains the field, the error tells the user what to fix).
 */
</script>

<template>
  <p class="mt-1 text-[11px] text-slate-500 leading-relaxed">
    <slot />
  </p>
</template>
