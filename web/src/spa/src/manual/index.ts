/* Locale-selecting loader for the application manual.
   German is the primary body; English is the secondary/fallback. */
import type { Manual } from './manualTypes'
import { manualDe } from './manualData.de'
import { manualEn } from './manualData.en'

export type ManualLang = 'de' | 'en'

export function getManual(lang: ManualLang): Manual {
  return lang === 'en' ? manualEn : manualDe
}

/** Normalise a vue-i18n locale (e.g. 'de-AT', 'en') to the manual language. */
export function manualLangFor(locale: string): ManualLang {
  return locale.toLowerCase().startsWith('de') ? 'de' : 'en'
}

export type { Manual, ManualRoleKey } from './manualTypes'
export { manualDe, manualEn }
