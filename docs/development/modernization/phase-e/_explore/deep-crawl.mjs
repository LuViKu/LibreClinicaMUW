// Second-pass: drill one level deeper from the surface walk.
// For each role: visit handcrafted high-value sub-URLs + discover Edit/View/Add
// links from key listing pages and visit those too.
//
// Still strictly read-only: skips Save/Submit/Delete/Sign/Lock/Logout buttons.

import { chromium } from 'playwright';
import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';

const BASE = 'https://libreclinica.reliatec.de/lc-demo01';
const STUDY_ID = 3;         // LCDemo
const SITE_ID = 4;          // München
const SUBJECT_ID = 1;       // M-001
const EVENT_ID = 1;         // first event
const EVENT_DEF_2 = 2;
const EVENT_DEF_3 = 3;

const ROLES = [
  {
    role: 'investigator', user: 'user_demo', pass: 'LibreClinica',
    // Curated deep targets — URLs we want to capture but didn't surface in the top nav
    targets: [
      // Subject-level
      { label: 'ViewStudySubject', url: `/ViewStudySubject?id=${SUBJECT_ID}` },
      { label: 'EnterDataForStudyEvent', url: `/EnterDataForStudyEvent?eventId=${EVENT_ID}` },
      { label: 'UpdateStudyEvent', url: `/UpdateStudyEvent?event_id=${EVENT_ID}` },
      // Schedule event with context (we saw these URLs in the surface walk)
      { label: 'ScheduleEvent_subj1_def2', url: `/CreateNewStudyEvent?studySubjectId=${SUBJECT_ID}&studyEventDefinition=${EVENT_DEF_2}` },
      { label: 'ScheduleEvent_subj1_def3', url: `/CreateNewStudyEvent?studySubjectId=${SUBJECT_ID}&studyEventDefinition=${EVENT_DEF_3}` },
      // Notes assigned to me
      { label: 'Notes_assigned_self', url: `/ViewNotes?module=submit&listNotes_f_discrepancyNoteBean.user=user_demo` },
      // Subject Matrix with Select-An-Event (per-CRF view)
      { label: 'SubjectMatrix_byEvent', url: `/ListStudySubjects?event=${EVENT_DEF_2}` },
    ],
    // Discovery: from these starting URLs, follow internal links matching this pattern
    discover: [
      { from: '/ListStudySubjects', linkPattern: /^(ViewStudySubject|EnterDataForStudyEvent|UpdateStudyEvent)/ },
      { from: `/EnterDataForStudyEvent?eventId=${EVENT_ID}`, linkPattern: /^(InitialDataEntry|ViewSectionDataEntry|UpdateStudyEvent|PrintCRFForm)/ },
    ],
  },
  {
    role: 'monitor', user: 'monitor_demo', pass: 'LibreClinica',
    targets: [
      // Subject detail (read-only)
      { label: 'ViewStudySubject', url: `/ViewStudySubject?id=${SUBJECT_ID}` },
      // SDV View By Subject ID tab
      { label: 'SDV_bySubject', url: `/pages/viewSubjectAggregate?studyId=${STUDY_ID}` },
      // Audit log drill-in (per-subject)
      { label: 'StudyAuditLog_subject1', url: `/StudyAuditLog?id=${SUBJECT_ID}` },
      // Notes assigned to me (monitor)
      { label: 'Notes_assigned_self', url: `/ViewNotes?module=submit&listNotes_f_discrepancyNoteBean.user=monitor_demo` },
      // CRF read-only view
      { label: 'ViewSectionDataEntry', url: `/ViewSectionDataEntry?ecId=1` },
    ],
    discover: [
      { from: `/pages/viewAllSubjectSDVtmp?studyId=${STUDY_ID}`, linkPattern: /^(ViewSectionDataEntry|ViewNotes|ViewStudySubject)/ },
      { from: '/ViewNotes?module=submit', linkPattern: /^(ViewNotes|ViewSectionDataEntry|ViewDiscrepancyNote|UpdateSubjectDiscrepancyNote)/ },
      { from: '/StudyAuditLog', linkPattern: /^StudyAuditLog\?id=/ },
    ],
  },
  {
    role: 'data-manager', user: 'dm_demo', pass: 'LibreClinica',
    targets: [
      // Build Study related
      { label: 'CreateStudy', url: `/CreateStudy` },
      { label: 'UpdateStudy', url: `/UpdateStudy?studyId=${STUDY_ID}` },
      // CRF management
      { label: 'CreateCRF', url: `/CreateCRF` },
      { label: 'ViewCRF_id1', url: `/ViewCRF?crfId=1` },
      // Event Definitions
      { label: 'ListEventDefinition', url: `/ListEventDefinition` },
      { label: 'CreateEventDefinition', url: `/CreateEventDefinition` },
      { label: 'UpdateEventDefinition_id2', url: `/UpdateEventDefinition?id=${EVENT_DEF_2}` },
      // Users
      { label: 'CreateUserAccount', url: `/CreateUserAccount` },
      { label: 'EditStudyUserRole_user1', url: `/EditStudyUserRole?userName=user_demo` },
      // Sites
      { label: 'ListSite', url: `/ListSite` },
      { label: 'ViewSite_id4', url: `/ViewSite?id=${SITE_ID}` },
      { label: 'CreateSubStudy', url: `/CreateSubStudy?parentId=${STUDY_ID}` },
      // Rules
      { label: 'RulesXMLUpload', url: `/RulesXMLUpload` },
      { label: 'TestRule', url: `/TestRule` },
      // Subject Group Classes
      { label: 'CreateSubjectGroupClass', url: `/CreateSubjectGroupClass` },
      // Study Parameter config
      { label: 'EditStudy', url: `/EditStudy?studyId=${STUDY_ID}` },
      // Notes assigned to me (DM)
      { label: 'Notes_assigned_self', url: `/ViewNotes?module=submit&listNotes_f_discrepancyNoteBean.user=dm_demo` },
      // Subject detail
      { label: 'ViewStudySubject', url: `/ViewStudySubject?id=${SUBJECT_ID}` },
      // Audit log drill-in
      { label: 'StudyAuditLog_subject1', url: `/StudyAuditLog?id=${SUBJECT_ID}` },
    ],
    discover: [
      { from: '/ListStudyUser', linkPattern: /^(EditStudyUserRole|ViewUserAccount|UpdateUserAccount|RestoreUserAccount|RemoveUserAccount|ResetPassword|SendUserMail|SetUserRole)/ },
      { from: '/ListCRF?module=manage', linkPattern: /^(ViewCRF|UpdateCRF|RemoveCRF|RestoreCRF|DownloadCRFVersion|ViewCRFVersionMetadata|CreateCRFVersion|EditCRF)/ },
      { from: '/pages/studymodule', linkPattern: /^(UpdateStudy|UpdateEventDefinition|UpdateCRF|CreateEventDefinition|CreateCRF|CreateSite|CreateRule|EditStudyUserRole|ListEventDefinition|ListCRF|ListSite|ListStudyUser|ListSubjectGroupClass)/ },
    ],
  },
];

const sanitize = (s) => s.replace(/[^a-zA-Z0-9._-]+/g, '_').slice(0, 80);
const DANGER_RE = /\b(remove|delete|reset|lock|unlock|sign|logout|j_spring_security_logout)\b/i;

async function capture(page, dir, label) {
  await page.screenshot({ path: path.join(dir, `${label}.png`), fullPage: true });
  const meta = await page.evaluate(() => {
    const trim = (t) => (t || '').replace(/\s+/g, ' ').trim();
    const collect = (sel) => Array.from(document.querySelectorAll(sel))
      .map(a => ({ text: trim(a.textContent), href: a.getAttribute('href') }))
      .filter(x => x.text && x.href);
    const tables = Array.from(document.querySelectorAll('table')).map(t => ({
      n_rows: t.querySelectorAll('tr').length,
      headers: Array.from(t.querySelectorAll('th')).map(h => trim(h.textContent)).filter(Boolean).slice(0, 25),
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
          .map(i => i.getAttribute('name')).filter(Boolean).slice(0, 40),
      })),
      buttons: Array.from(document.querySelectorAll('button, input[type=submit], input[type=button]'))
        .map(b => trim(b.value || b.textContent)).filter(Boolean).slice(0, 40),
      tables,
      messages: Array.from(document.querySelectorAll('.alert_message, .alert, .errorMessage, .messages, .alerts'))
        .map(e => trim(e.textContent)).filter(Boolean).slice(0, 5),
    };
  });
  await writeFile(path.join(dir, `${label}.json`), JSON.stringify(meta, null, 2));
  return meta;
}

async function login(page, user, pass) {
  await page.goto(`${BASE}/pages/login/login`, { waitUntil: 'domcontentloaded' });
  await page.fill('input[name="j_username"], input#j_username', user);
  await page.fill('input[name="j_password"], input#j_password', pass);
  await Promise.all([
    page.waitForLoadState('domcontentloaded'),
    page.click('input[type=submit], button[type=submit]'),
  ]);
}

async function visit(page, outDir, label, url) {
  try {
    await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 20000 });
    const meta = await capture(page, outDir, label);
    // Detect "access denied" or "no permission" by H1/text — log it but keep going.
    const blocked = /access denied|do not have permission|insufficient|unauthorized/i.test(JSON.stringify(meta).slice(0, 5000));
    const tag = blocked ? ' [BLOCKED]' : '';
    console.log(`    ${label}: ${meta.h1?.[0] || meta.title}${tag}`);
    return meta;
  } catch (e) {
    console.log(`    ${label}: FAIL ${e.message}`);
    return null;
  }
}

async function discover(page, fromUrl, linkPattern) {
  await page.goto(`${BASE}${fromUrl}`, { waitUntil: 'domcontentloaded' });
  const links = await page.evaluate(() => Array.from(document.querySelectorAll('a'))
    .map(a => ({ text: (a.textContent || '').replace(/\s+/g, ' ').trim(), href: a.getAttribute('href') }))
    .filter(x => x.text && x.href));
  // Normalize href: strip leading slash + base
  const found = [];
  const seen = new Set();
  for (const l of links) {
    let href = l.href.replace(/^\//, '').replace(/^lc-demo01\//, '');
    if (!linkPattern.test(href)) continue;
    if (DANGER_RE.test(href) || DANGER_RE.test(l.text)) continue;
    if (seen.has(href)) continue;
    seen.add(href);
    found.push({ text: l.text, url: `/${href}` });
  }
  return found.slice(0, 6); // cap discovery to avoid going broad
}

async function deepWalkRole(browser, role) {
  const outDir = `walk-out-deep/${role.role}`;
  await mkdir(outDir, { recursive: true });
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await ctx.newPage();
  page.setDefaultTimeout(20000);

  console.log(`\n=== ${role.role} (${role.user}) — deep crawl ===`);
  await login(page, role.user, role.pass);

  // Curated targets
  console.log(`  -- curated targets (${role.targets.length}) --`);
  let i = 0;
  for (const t of role.targets) {
    i++;
    const label = `${String(i).padStart(2, '0')}-${sanitize(t.label)}`;
    await visit(page, outDir, label, `${BASE}${t.url}`);
  }

  // Discovery
  for (const d of role.discover) {
    console.log(`  -- discover from ${d.from} (pattern ${d.linkPattern}) --`);
    const found = await discover(page, d.from, d.linkPattern);
    for (const f of found) {
      i++;
      const label = `${String(i).padStart(2, '0')}-disc-${sanitize(f.text || f.url)}`;
      await visit(page, outDir, label, `${BASE}${f.url}`);
    }
  }

  await ctx.close();
}

(async () => {
  const browser = await chromium.launch({ headless: true });
  for (const r of ROLES) {
    await deepWalkRole(browser, r);
  }
  await browser.close();
  console.log('\nDone.');
})().catch(err => { console.error(err); process.exit(1); });
