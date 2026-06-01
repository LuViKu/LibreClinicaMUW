import { defineConfig } from 'histoire'
import { HstVue } from '@histoire/plugin-vue'

/**
 * Phase E.3 (2026-05-30): Histoire setup for the component catalogue.
 *
 * `pnpm stories` opens the Histoire dev server at
 * http://localhost:6006 — every primitive lives next to its component
 * file as `<Name>.story.vue`. Every story doubles as a render-time
 * accessibility check via the @histoire/plugin-a11y plugin (added in
 * Phase E.9 alongside the WCAG 2.2 AA gate).
 */
export default defineConfig({
  plugins: [HstVue()],
  setupFile: 'src/style.css',
  storyMatch: ['src/components/**/*.story.vue'],
  theme: {
    title: 'LibreClinica MUW — Phase E primitives',
    favicon: 'public/favicon.svg',
    logo: {
      square: './public/favicon.svg',
      light: './public/favicon.svg',
      dark: './public/favicon.svg',
    },
    colors: {
      primary: {
        50:  '#f3f4f9',
        100: '#e3e6ef',
        200: '#c6cce0',
        300: '#9aa3c4',
        400: '#5f6b9c',
        500: '#384782',
        600: '#243366',
        700: '#1a2658',
        800: '#111d4e',
        900: '#0b1438',
      },
    },
  },
  routerMode: 'hash',
})
