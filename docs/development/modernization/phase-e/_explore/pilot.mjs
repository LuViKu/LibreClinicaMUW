// Pilot: log in as user_demo, capture home + top-nav structure.
// Run from repo root: npx playwright test --config=docs/development/modernization/phase-e/_explore/pw.config.mjs
// Or as a standalone script: node docs/development/modernization/phase-e/_explore/pilot.mjs

import { chromium } from 'playwright';
import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';

const BASE = 'https://libreclinica.reliatec.de/lc-demo01';
const OUT = 'pilot-out';

const users = [
  { role: 'investigator', user: 'user_demo',    pass: 'LibreClinica' },
];

async function captureNav(page, role, label) {
  const dir = path.join(OUT, role);
  await mkdir(dir, { recursive: true });

  await page.screenshot({ path: path.join(dir, `${label}.png`), fullPage: true });

  // Snapshot URL + title + visible top-level links (the legacy menu)
  const meta = await page.evaluate(() => {
    const collect = (sel) => Array.from(document.querySelectorAll(sel))
      .map(a => ({ text: (a.textContent || '').trim().replace(/\s+/g, ' '), href: a.getAttribute('href') }))
      .filter(x => x.text);
    return {
      url: location.href,
      title: document.title,
      // Top menu is typically rendered as <a> in a header div; we cast a wide net.
      links_top:    collect('#sidebar a, .sidebar a, #header a, .header a, .top a').slice(0, 60),
      // Tasks menu items, sometimes rendered as buttons or links in a dropdown.
      links_tasks:  collect('a[href*="Task"], a[href*="task"]').slice(0, 40),
      // Forms that exist on the page (gives a hint about what actions are possible).
      forms: Array.from(document.querySelectorAll('form')).map(f => ({
        action: f.getAttribute('action'),
        method: f.getAttribute('method'),
        n_inputs: f.querySelectorAll('input, select, textarea').length,
      })),
      // Buttons and submit inputs.
      buttons: Array.from(document.querySelectorAll('button, input[type=submit], input[type=button]'))
        .map(b => (b.value || b.textContent || '').trim()).filter(Boolean).slice(0, 40),
    };
  });
  await writeFile(path.join(dir, `${label}.json`), JSON.stringify(meta, null, 2));
  return meta;
}

(async () => {
  const browser = await chromium.launch({ headless: true });
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await ctx.newPage();
  page.setDefaultTimeout(30000);

  for (const u of users) {
    console.log(`\n=== ${u.role} (${u.user}) ===`);
    await page.goto(`${BASE}/pages/login/login`, { waitUntil: 'domcontentloaded' });
    await captureNav(page, u.role, '00-login');

    await page.fill('input[name="j_username"], input#j_username, input[name="username"]', u.user);
    await page.fill('input[name="j_password"], input#j_password, input[name="password"]', u.pass);
    await Promise.all([
      page.waitForLoadState('domcontentloaded'),
      page.click('input[type=submit], button[type=submit]'),
    ]);

    // Possible first-time password change page or 2FA — log what we land on either way.
    await captureNav(page, u.role, '01-post-login');

    // Try to reach home explicitly.
    await page.goto(`${BASE}/MainMenu`, { waitUntil: 'domcontentloaded' }).catch(() => {});
    await captureNav(page, u.role, '02-home');
  }

  await browser.close();
})().catch(err => { console.error(err); process.exit(1); });
