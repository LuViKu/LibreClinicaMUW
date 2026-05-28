// Walk the LibreClinica demo as each role and capture every reachable screen
// in the top nav + Tasks dropdown.
//
// Read-only by design: we never submit forms, click Save/Submit/Delete, or
// follow links containing 'action=remove|delete|reset|lock|unlock'.

import { chromium } from 'playwright';
import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';

const BASE = 'https://libreclinica.reliatec.de/lc-demo01';

const ROLES = [
  { role: 'investigator',  user: 'user_demo',    pass: 'LibreClinica' },
  { role: 'monitor',       user: 'monitor_demo', pass: 'LibreClinica' },
  { role: 'data-manager',  user: 'dm_demo',      pass: 'LibreClinica' },
];

const DANGER_RE = /\b(remove|delete|reset|lock|unlock|restore|sign|logout|j_spring_security_logout)\b/i;

// Things we explicitly *want* to visit if they appear, regardless of nav source.
const EXTRA_TARGETS = [
  // (Filled per-role below if needed.)
];

const sanitize = (s) => s.replace(/[^a-zA-Z0-9._-]+/g, '_').slice(0, 80);

async function capture(page, dir, label) {
  await page.screenshot({ path: path.join(dir, `${label}.png`), fullPage: true });
  const meta = await page.evaluate(() => {
    const trim = (t) => (t || '').replace(/\s+/g, ' ').trim();
    const collect = (sel) => Array.from(document.querySelectorAll(sel))
      .map(a => ({ text: trim(a.textContent), href: a.getAttribute('href') }))
      .filter(x => x.text && x.href);
    const tables = Array.from(document.querySelectorAll('table')).map(t => ({
      n_rows: t.querySelectorAll('tr').length,
      headers: Array.from(t.querySelectorAll('th')).map(h => trim(h.textContent)).filter(Boolean).slice(0, 20),
    })).filter(t => t.headers.length > 0).slice(0, 10);
    return {
      url: location.href,
      title: document.title,
      h1: Array.from(document.querySelectorAll('h1, h2')).map(h => trim(h.textContent)).filter(Boolean).slice(0, 8),
      links: collect('a').slice(0, 200),
      forms: Array.from(document.querySelectorAll('form')).map(f => ({
        action: f.getAttribute('action'),
        method: f.getAttribute('method'),
        n_inputs: f.querySelectorAll('input, select, textarea').length,
        input_names: Array.from(f.querySelectorAll('input, select, textarea'))
          .map(i => i.getAttribute('name')).filter(Boolean).slice(0, 30),
      })),
      buttons: Array.from(document.querySelectorAll('button, input[type=submit], input[type=button]'))
        .map(b => trim(b.value || b.textContent)).filter(Boolean).slice(0, 30),
      tables,
    };
  });
  await writeFile(path.join(dir, `${label}.json`), JSON.stringify(meta, null, 2));
  return meta;
}

async function expandTasks(page) {
  // The Tasks menu is a hover/click dropdown. Try clicking it and capture
  // the now-visible links.
  try {
    await page.click('text=Tasks', { timeout: 3000 });
  } catch {
    try { await page.hover('text=Tasks'); } catch {}
  }
  await page.waitForTimeout(400);
  const items = await page.evaluate(() => {
    const trim = (t) => (t || '').replace(/\s+/g, ' ').trim();
    // Tasks-menu items often live in a list shown after click; cast a wide net.
    return Array.from(document.querySelectorAll('a'))
      .map(a => ({ text: trim(a.textContent), href: a.getAttribute('href') }))
      .filter(x => x.text && x.href && x.href !== '#' && !x.href.startsWith('javascript:'));
  });
  return items;
}

async function login(page, user, pass) {
  await page.goto(`${BASE}/pages/login/login`, { waitUntil: 'domcontentloaded' });
  await page.fill('input[name="j_username"], input#j_username', user);
  await page.fill('input[name="j_password"], input#j_password', pass);
  await Promise.all([
    page.waitForLoadState('domcontentloaded'),
    page.click('input[type=submit], button[type=submit]'),
  ]);
  // We may land on a forced password change for first login. If so, log it.
  const url = page.url();
  if (/ResetPassword|ChangePassword|password/i.test(url) && !/login/i.test(url)) {
    console.warn(`  WARNING: forced password change page: ${url}`);
  }
  return url;
}

async function walkRole(browser, { role, user, pass }) {
  const outDir = `walk-out/${role}`;
  await mkdir(outDir, { recursive: true });
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await ctx.newPage();
  page.setDefaultTimeout(20000);

  console.log(`\n=== ${role} (${user}) ===`);
  await login(page, user, pass);
  await page.goto(`${BASE}/MainMenu`, { waitUntil: 'domcontentloaded' });
  const home = await capture(page, outDir, '00-home');
  console.log(`  home: ${home.url}`);
  console.log(`  top-nav: ${home.links.filter(l => !l.href.startsWith('javascript:') && !l.href.startsWith('#')).slice(0, 12).map(l => l.text).join(' | ')}`);

  // Expand the Tasks dropdown.
  const tasksItems = await expandTasks(page);
  await writeFile(path.join(outDir, '_tasks-menu.json'), JSON.stringify(tasksItems, null, 2));
  // Take an additional screenshot of the page WITH the Tasks menu open.
  await page.screenshot({ path: path.join(outDir, '00b-home-with-tasks-open.png'), fullPage: true });

  // Build target list: top-nav links + Tasks-menu items, deduped by href.
  const topNavHrefs = home.links
    .filter(l => l.href && !l.href.startsWith('javascript:') && !l.href.startsWith('#'))
    .map(l => ({ text: l.text, href: l.href }));
  const allTargets = [...topNavHrefs, ...tasksItems];
  const seen = new Set();
  const targets = [];
  for (const t of allTargets) {
    if (!t.href) continue;
    if (DANGER_RE.test(t.href) || DANGER_RE.test(t.text)) {
      console.log(`  SKIP (danger): ${t.text} -> ${t.href}`);
      continue;
    }
    // Skip leaving the demo host.
    if (t.href.startsWith('http') && !t.href.includes('libreclinica.reliatec.de')) continue;
    if (seen.has(t.href)) continue;
    seen.add(t.href);
    targets.push(t);
  }
  await writeFile(path.join(outDir, '_targets.json'), JSON.stringify(targets, null, 2));
  console.log(`  ${targets.length} targets to visit`);

  // Visit each target. Skip the home (already captured) and login/logout.
  let i = 1;
  for (const t of targets) {
    const label = `${String(i).padStart(2, '0')}-${sanitize(t.text || 'untitled')}`;
    const url = t.href.startsWith('http') ? t.href : `${BASE}/${t.href.replace(/^\//, '')}`;
    try {
      await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 20000 });
      const m = await capture(page, outDir, label);
      console.log(`  [${i}/${targets.length}] ${t.text} -> ${m.url}`);
    } catch (e) {
      console.log(`  [${i}/${targets.length}] FAIL ${t.text}: ${e.message}`);
      await writeFile(path.join(outDir, `${label}.error.txt`), `${url}\n${e.message}\n`);
    }
    i++;
  }

  await ctx.close();
}

(async () => {
  const browser = await chromium.launch({ headless: true });
  for (const r of ROLES) {
    await walkRole(browser, r);
  }
  await browser.close();
  console.log('\nDone.');
})().catch(err => { console.error(err); process.exit(1); });
