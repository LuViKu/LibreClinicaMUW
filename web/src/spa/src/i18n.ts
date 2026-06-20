import { createI18n } from 'vue-i18n'
import deMessages from './locales/de.json'
import enMessages from './locales/en.json'

/**
 * The vue-i18n instance, extracted from {@code main.ts} so other
 * modules (the study-module store's lazy bundle merger, in
 * particular) can call {@code i18n.global.mergeLocaleMessage()}
 * without import cycles.
 *
 * Behaviour: identical to the inline version that previously lived
 * in main.ts. Legacy mode disabled (composition API); fallback to
 * English when a key is missing in {@code de-AT}; missing-key +
 * fallback warnings gated on {@code !import.meta.env.PROD} so dev
 * gets the signal while prod stays quiet.
 */
export const i18n = createI18n({
  legacy: false,
  locale: 'de-AT',
  fallbackLocale: 'en',
  missingWarn: !import.meta.env.PROD,
  fallbackWarn: !import.meta.env.PROD,
  messages: {
    'de-AT': deMessages,
    en: enMessages,
  },
})
