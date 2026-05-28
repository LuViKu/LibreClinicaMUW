// Render each ux-mockup HTML file in headless Chromium and capture a full-page
// PNG. Validates the mockups load cleanly (Tailwind CDN, fonts, no JS errors)
// and gives us preview thumbnails to inspect.
import { chromium } from 'playwright';
import { mkdir } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const MOCKUP_DIR = path.resolve(__dirname, '../ux-mockups');
const OUT = path.resolve(__dirname, 'mockup-renders');

const files = [
  'index.html',
  // Round 1 - one critical workflow per role
  'investigator-subject-matrix.html',
  'investigator-add-subject.html',
  'investigator-crf-entry.html',
  'monitor-sdv.html',
  'monitor-crf-readonly.html',
  'dm-build-study.html',
  'dm-update-event-definition.html',
  // Round 2 - cross-role essentials
  'notes-discrepancies.html',
  'view-events.html',
  'view-subject.html',
  'schedule-event.html',
  'dm-manage-users.html',
];

(async () => {
  await mkdir(OUT, { recursive: true });
  const browser = await chromium.launch({ headless: true });
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await ctx.newPage();

  const errors = [];
  page.on('pageerror', e => errors.push(`PAGEERROR: ${e.message}`));
  page.on('console', msg => { if (msg.type() === 'error') errors.push(`CONSOLE: ${msg.text()}`); });

  for (const f of files) {
    errors.length = 0;
    const url = 'file://' + path.join(MOCKUP_DIR, f);
    try {
      await page.goto(url, { waitUntil: 'networkidle', timeout: 15000 });
      // Wait for Tailwind CDN to apply styles and Inter to load.
      await page.waitForTimeout(500);
      const outFile = path.join(OUT, f.replace(/\.html$/, '.png'));
      await page.screenshot({ path: outFile, fullPage: true });
      const ok = errors.length === 0 ? 'OK' : `OK (with ${errors.length} warnings)`;
      console.log(`  ${ok}  ${f}`);
      if (errors.length) errors.forEach(e => console.log(`        ${e}`));
    } catch (e) {
      console.log(`  FAIL  ${f}: ${e.message}`);
    }
  }
  await browser.close();
})().catch(e => { console.error(e); process.exit(1); });
