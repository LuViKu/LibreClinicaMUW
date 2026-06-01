/*
 * MUW LibreClinica — Tailwind CDN config
 * Loaded immediately after https://cdn.tailwindcss.com so all subsequent
 * markup can use brand utility classes (bg-muw-blue, text-muw-coral-700, etc.).
 *
 * Palette derived from MedUni Wien Corporate Design Styleguide (March 2022).
 *   Dunkelblau  #111d4e  — primary brand / Dunkelblau (Logo, Typografie, Basiselemente)
 *   Hellblau    #5fb4e5  — secondary
 *   Grün        #2f8e91  — secondary (web variant)
 *   Coral       #f0a794  — secondary / 40% as background tint
 */

tailwind.config = {
  theme: {
    extend: {
      colors: {
        /* Primary: Dunkelblau — buttons, links, brand */
        'muw-blue': {
          DEFAULT: '#111d4e',
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
          950: '#050817',
        },
        /* Secondary: Hellblau — info, scheduled, monitor role */
        'muw-sky': {
          DEFAULT: '#5fb4e5',
          50:  '#e7f3fb',
          100: '#cfe8f6',
          200: '#b5dcf1',
          300: '#97cfec',
          400: '#7cc2e8',
          500: '#5fb4e5',
          600: '#3a92c8',
          700: '#1d6c98',
          800: '#155270',
          900: '#0e3a52',
        },
        /* Secondary: Grün — success, complete, signed, investigator role */
        'muw-teal': {
          DEFAULT: '#2f8e91',
          50:  '#e4f2ef',
          100: '#c8e5df',
          200: '#a8d7cd',
          300: '#84c9bc',
          400: '#54aca5',
          500: '#2f8e91',
          600: '#267376',
          700: '#1d595c',
          800: '#163f42',
          900: '#0e2a2c',
        },
        /* Secondary: Coral — data manager role, soft accents, info banners */
        'muw-coral': {
          DEFAULT: '#f0a794',
          50:  '#fdf0eb',
          100: '#fce1d8',
          200: '#fad1c4',
          300: '#f8c0b0',
          400: '#f4b3a2',
          500: '#f0a794',
          600: '#d96849',
          700: '#b04a30',
          800: '#7a3220',
          900: '#4e1f12',
        },
      },
      fontFamily: {
        /* Newsreader (Google) ≈ MUW Danton — modern antiqua for headings */
        serif: ['Newsreader', 'Georgia', 'Times New Roman', 'serif'],
        /* Inter ≈ MUW Akkurat Pro — clean grotesk for body / UI */
        sans:  ['Inter', 'system-ui', '-apple-system', 'Segoe UI', 'sans-serif'],
        /* Mono — for OIDs, SQL-like keys */
        mono:  ['JetBrains Mono', 'ui-monospace', 'SFMono-Regular', 'monospace'],
      },
      borderRadius: {
        /* Echoes MUW logo's "siegel" rounded form */
        'muw': '0.625rem',
      },
      boxShadow: {
        'muw-card': '0 1px 2px rgba(17, 29, 78, 0.04), 0 4px 12px rgba(17, 29, 78, 0.04)',
        'muw-elev': '0 4px 12px rgba(17, 29, 78, 0.06), 0 16px 40px rgba(17, 29, 78, 0.08)',
      },
    },
  },
};
