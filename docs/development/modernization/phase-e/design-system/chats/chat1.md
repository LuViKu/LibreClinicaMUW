# cCRF Platform Design

_Started 2026-05-28 14:19 UTC_

---

## User

Create a design system and fine-tune the design/mock-ups for the new cCRF platform we are developing for the Department of Ophthalmology and Optometry at the medical university of Vienna. Keep the design clean and clinical, while trying to implement some of the design elements of the MUW style guide.

## Assistant

_[tool: read_file]_

_[tool: list_files]_

_[tool: list_files]_

_[tool: list_files]_

_[tool: list_files]_

_[tool: list_files]_

_[tool: invoke_skill]_

_[tool: read_file]_

_[tool: run_script]_

_[tool: view_image]_

_[tool: view_image]_

I have a clear picture now. Let me look at the current state of the mockups to understand what we're working with.

_[tool: update_todos]_

_[tool: copy_files]_

I have a clear picture now. The current mockups use generic Tailwind blue/slate/emerald/violet — they're clean but lack the MUW brand identity. The MUW brand is built around:

- **Dunkelblau #111d4e** (deep navy) as primary
- **Hellblau, Grün (teal), Coral** as a tightly curated secondary palette
- **Rounded "seal" corners** as a signature visual motif
- **Danton** serif for headlines + **Akkurat Pro** grotesk for body

My plan is to **infuse this brand into the existing clean clinical UI** without compromising data density:

1. Build a proper **Design System** page (colors, type, components)
2. Create a shared `muw-tokens.css` with MUW palette + fonts
3. Re-skin all 17 mockups: replace generic blue/violet with MUW Dunkelblau/Coral, swap status pill colors to MUW palette, use **Newsreader** (Danton substitute) for headlines, keep **Inter** for UI
4. Replace the placeholder `#` logo with a proper LibreClinica MUW wordmark using the serif

_[tool: read_file]_

_[tool: grep]_

Now let me look at a few mockups to understand the patterns I need to update:

_[tool: write_file]_

_[tool: write_file]_

Now I have a solid plan. Let me build the foundation: a shared Tailwind config + tokens CSS, then the Design System page.

_[tool: write_file]_

Now the Design System showcase page:

_[tool: show_html]_

_[tool: show_html]_

_[tool: get_webview_logs]_

_[tool: save_screenshot]_

_[tool: view_image]_

_[tool: save_screenshot]_

The hero looks excellent — MUW Dunkelblau with the Newsreader serif and coral accent reads exactly as intended. Let me scroll through the rest:

_[tool: view_image]_

_[tool: view_image]_

_[tool: view_image]_

_[tool: view_image]_

_[tool: view_image]_

I see some eyebrow labels wrapping. Let me check the rest and fix them all in one pass:

_[tool: run_script]_

Now let me fix the eyebrow label wrapping issue in the design system, then batch-update all the mockups:

_[tool: grep]_

_[tool: run_script]_

Now the big batch-update. I'll update all 17 mockups to use the MUW design system:

_[tool: show_html]_

_[tool: save_screenshot]_

All 19 files updated. Let me verify by spot-checking key pages:

_[tool: view_image]_

_[tool: show_html]_

_[tool: save_screenshot]_

The login looks excellent — the new MUW serif wordmark, the deep blue brand, the coral "MUW" accent, the teal password strength meter. Small issue: the large wordmark wraps. Let me fix that and check more screens:

_[tool: view_image]_

_[tool: show_html]_

_[tool: save_screenshot]_

Mostly looking great! Two issues to fix: wordmark wraps to two lines (need `whitespace-nowrap`), and large wordmark is too big. Let me patch:

_[tool: view_image]_

_[tool: save_screenshot]_

_[tool: show_html]_

Let me check more mockups to see how the rebrand looks at scale, then rebuild the index to match the new polish:

_[tool: save_screenshot]_

_[tool: view_image]_

_[tool: run_script]_

Let me fix the wrapping issues across all mockups in one pass, then rebuild the index to match the design system polish:

_[tool: write_file]_

Now let me rebuild index.html to match the design system polish — currently it's the old generic Tailwind layout:

_[tool: show_html]_

_[tool: save_screenshot]_

_[tool: view_image]_

_[tool: view_image]_

_[tool: view_image]_

_[tool: view_image]_

_[tool: show_html]_

_[tool: save_screenshot]_

Now let me verify a few more mockups to make sure the rebrand looks consistent across the board:

_[tool: view_image]_

_[tool: show_html]_

_[tool: save_screenshot]_

_[tool: view_image]_

_[tool: show_html]_

_[tool: save_screenshot]_

Let me check the popup-style mockup (no top bar) and the build study screen:

_[tool: view_image]_

_[tool: show_html]_

_[tool: save_screenshot]_

_[tool: view_image]_

_[tool: grep]_

Let me add `whitespace-nowrap` to a couple of common wrapping pills and breadcrumb items across all mockups in one final cleanup pass:

_[tool: run_script]_

_[tool: update_todos]_

_[tool: show_html]_

_[tool: save_screenshot]_

_[tool: view_image]_

_[tool: show_html]_

_[tool: save_screenshot]_

_[tool: view_image]_

_[tool: delete_file]_

_[tool: done]_

Excellent. Everything looks consistent. Let me clean up the scratch directory and call done:

_[tool: fork_verifier_agent]_

_[tool: update_todos]_

Done. Quick summary of what's in the project:

**Design system foundation** — `muw-tailwind-config.js` + `muw-tokens.css` define the MUW palette as Tailwind utility classes (`bg-muw-blue`, `text-muw-coral-700`, etc.) and pair Newsreader (≈ Danton) with Inter (≈ Akkurat Pro). Drop the three head tags into any file and you have the whole system.

**`design-system.html`** — Standalone showcase: hero, principles, full Dunkelblau shade scale, secondary swatches (Hellblau, Grün, Coral), semantic-mapping table, type specimens, full component sheet (buttons, fields, status pills, role chips, banners, card + dense-table primitives), icon set.

**`index.html`** — Rebuilt as a proper hub: serif hero ("Designing the next LibreClinica for the Department of Ophthalmology **& Optometry**"), scope-at-a-glance card, role legend, mockups grouped by round, MUW-blue closing banner.

**17 mockups rebranded** — every `blue-600` → `muw-blue`, `emerald` → `muw-teal`, `sky` → `muw-sky`, `violet` → `muw-coral`. The placeholder `#` logo became a circle-grid seal (echoes MUW's siegel form). Wordmark is now Newsreader serif "LibreClinica" + small coral "MUW" tag.

**Caveats / next steps:**
- Mockups still target 1440 px desktop; sub-1440 viewports cause some breadcrumb / section-title wraps (cosmetic).
- I deliberately kept page-level `<h1>`s in Inter — serif lives at branding moments (wordmark, hero) so the working screens stay clean and clinical. Easy to flip if you want more brand weight on every page.
- Status taxonomy keeps amber (warning) and rose (danger) outside the MUW palette for accessibility — MUW has no equivalent unambiguous red.

