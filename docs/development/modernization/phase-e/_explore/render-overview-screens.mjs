// Render the MUW-branded mockups from design-system/project/ as 1440x900
// above-the-fold PNGs for the department-head overview brief. Output lands
// alongside the brief MD so it's tracked in git.
import { chromium } from 'playwright';
import { mkdir } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const SRC = path.resolve(__dirname, '../design-system/project');
const OUT = path.resolve(__dirname, '../overview/images');

const files = [
  { src: 'index.html',                          out: '01-index.png' },
  { src: 'login.html',                          out: '02-login-shibboleth.png' },
  { src: 'investigator-subject-matrix.html',    out: '03-investigator-subject-matrix.png' },
  { src: 'investigator-crf-entry.html',         out: '04-investigator-crf-entry.png' },
  { src: 'monitor-sdv.html',                    out: '05-monitor-sdv.png' },
  { src: 'study-audit-log.html',                out: '06-study-audit-log.png' },
  { src: 'dm-create-crf.html',                  out: '07-dm-create-crf.png' },
];

(async () => {
  await mkdir(OUT, { recursive: true });
  const browser = await chromium.launch({ headless: true });
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await ctx.newPage();

  const errors = [];
  page.on('pageerror', e => errors.push(`PAGEERROR: ${e.message}`));
  page.on('console', msg => { if (msg.type() === 'error') errors.push(`CONSOLE: ${msg.text()}`); });

  for (const { src, out } of files) {
    errors.length = 0;
    const url = 'file://' + path.join(SRC, src);
    try {
      await page.goto(url, { waitUntil: 'networkidle', timeout: 15000 });
      await page.waitForTimeout(700);
      const outFile = path.join(OUT, out);
      await page.screenshot({ path: outFile, fullPage: false });
      const ok = errors.length === 0 ? 'OK' : `OK (with ${errors.length} warnings)`;
      console.log(`  ${ok}  ${src} -> ${out}`);
      if (errors.length) errors.forEach(e => console.log(`        ${e}`));
    } catch (e) {
      console.log(`  FAIL  ${src}: ${e.message}`);
    }
  }
  await browser.close();
})().catch(e => { console.error(e); process.exit(1); });
