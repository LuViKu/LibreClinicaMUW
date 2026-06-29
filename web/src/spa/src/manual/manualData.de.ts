/* LibreClinica MUW — Anwendungshandbuch · deutschsprachiges (primäres) Inhaltsmodell.
   Übersetzt aus dem englischen Quellmodell (manualData.en.ts), nach Rolle gegliedert.
   Inline-Auszeichnung in Strings: **fett**, *kursiv*, `code`. Screenshot-Pfade
   sind `rolle/id.png` und werden in der Ansicht gegen `${BASE_URL}manual/` aufgelöst. */
import type { Manual } from './manualTypes'
import { gettingStartedDe } from './de/gettingStarted'
import { administratorDe } from './de/administrator'
import { dataManagerDe } from './de/dataManager'
import { monitorDe } from './de/monitor'
import { investigatorDe } from './de/investigator'
import { crcDe } from './de/crc'

export const manualDe: Manual = {
  meta: {
    title: 'Anwendungshandbuch',
    product: 'LibreClinica MUW',
    subtitle:
      'Endbenutzerhandbuch der klinischen Studiendaten-Plattform der Augenklinik — nach Rolle gegliedert.',
    version: 'v1.5.0-beta.2-muw',
    build: 'Build 25-06-2026 · 7518bd3de',
  },
  roles: {
    common: { label: 'Alle Rollen', en: 'All roles', accent: 'blue' },
    administrator: { label: 'Administrator/-in', en: 'Administrator', accent: 'blue' },
    'data-manager': { label: 'Studienleitung', en: 'Data Manager', accent: 'coral' },
    monitor: { label: 'Monitor', en: 'Monitor', accent: 'sky' },
    investigator: { label: 'Prüfarzt/-ärztin', en: 'Investigator', accent: 'teal' },
    crc: { label: 'Koordinator/-in', en: 'CRC', accent: 'tealdk' },
  },
  chapters: [
    gettingStartedDe,
    administratorDe,
    dataManagerDe,
    monitorDe,
    investigatorDe,
    crcDe,
  ],
}
