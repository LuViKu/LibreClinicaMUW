<script setup lang="ts">
/* Application manual — role-organised, German-primary with an EN toggle.
   Content model: src/manual (ported from the Claude Design handoff).
   Chapters are gated to the signed-in user's role(s); 'common' is always shown. */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '@/stores/auth'
import type { UserRole } from '@/types/auth'
import { getManual, manualLangFor, type ManualLang } from '@/manual'
import type { ManualChapter, ManualRoleKey } from '@/manual/manualTypes'

const auth = useAuthStore()
const { locale } = useI18n()

/* ── language (local to the manual; defaults to the app locale) ── */
const lang = ref<ManualLang>(manualLangFor(String(locale.value)))
const manual = computed(() => getManual(lang.value))

const L = computed(() =>
  lang.value === 'de'
    ? { kicker: 'Anwendungshandbuch', byRole: 'nach Rolle gegliedert', goal: 'Ziel', notes: 'Hinweise', print: 'Drucken / PDF', contents: 'Inhalt' }
    : { kicker: 'Application manual', byRole: 'organised by role', goal: 'Goal', notes: 'Notes', print: 'Print / PDF', contents: 'Contents' },
)

/* ── role gating ── */
const ROLE_KEY: Record<UserRole, ManualRoleKey> = {
  Investigator: 'investigator',
  Monitor: 'monitor',
  'Data Manager': 'data-manager',
  Administrator: 'administrator',
  CRC: 'crc',
}

const userRoles = computed<UserRole[]>(() => {
  const a = auth.user?.activeStudy
  if (a?.roles && a.roles.length) return [...a.roles]
  if (a?.role) return [a.role]
  if (auth.user?.role) return [auth.user.role]
  return []
})

/** Manual chapters the user may read: their role(s) + CRC→Investigator inheritance, plus 'common'. */
const grantedKeys = computed<Set<ManualRoleKey>>(() => {
  const s = new Set<ManualRoleKey>(['common'])
  for (const r of userRoles.value) {
    s.add(ROLE_KEY[r])
    if (r === 'CRC') s.add('investigator') // CRC inherits the Investigator surface
  }
  return s
})

const visibleChapters = computed<ManualChapter[]>(() =>
  manual.value.chapters.filter((c) => grantedKeys.value.has(c.role)),
)

const accentOf = (role: ManualRoleKey) => manual.value.roles[role]?.accent ?? 'blue'
const roleLabel = (role: ManualRoleKey) => {
  const r = manual.value.roles[role]
  return lang.value === 'de' ? (r?.label ?? role) : (r?.en ?? role)
}

/* ── screenshots resolve under the app base path (public/manual/<role>/<id>.png) ── */
const shotUrl = (p: string) => `${import.meta.env.BASE_URL}manual/${p}`

/* ── tiny inline markdown: escape, then **bold** *italic* `code` ── */
function inlineHtml(s: string): string {
  let h = String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
  h = h.replace(/`([^`]+)`/g, '<code>$1</code>')
  h = h.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
  h = h.replace(/\*([^*]+)\*/g, '<em>$1</em>')
  return h
}

/* ── flat list of anchorable ids (chapters + their sections), for TOC + scrollspy ── */
const tocChapters = computed(() =>
  visibleChapters.value.map((c) => ({
    id: c.id,
    role: c.role,
    title: c.title,
    sections: c.sections.map((s) => ({ id: s.id, num: s.num, title: s.title })),
  })),
)

const activeId = ref<string>('')
let io: IntersectionObserver | null = null

function observeSections() {
  io?.disconnect()
  io = new IntersectionObserver(
    (entries) => {
      const vis = entries
        .filter((e) => e.isIntersecting)
        .sort((a, b) => a.boundingClientRect.top - b.boundingClientRect.top)
      if (vis.length) activeId.value = vis[0].target.id
    },
    { rootMargin: '-12% 0px -80% 0px', threshold: 0 },
  )
  const ids = tocChapters.value.flatMap((c) => [c.id, ...c.sections.map((s) => s.id)])
  for (const id of ids) {
    const el = document.getElementById(id)
    if (el) io.observe(el)
  }
}

onMounted(() => {
  // wait a tick for the DOM to render the (re-)filtered chapters
  requestAnimationFrame(observeSections)
})
watch([visibleChapters, lang], () => requestAnimationFrame(observeSections))
onBeforeUnmount(() => io?.disconnect())

function go(id: string) {
  document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}
const printManual = () => window.print()
</script>

<template>
  <div class="manual-shell" :data-lang="lang">
    <div class="mx-auto max-w-[1180px] px-4 sm:px-6 lg:px-8 py-6 lg:flex lg:gap-8">
      <!-- ░░ TABLE OF CONTENTS ░░ -->
      <aside class="manual-toc no-print hidden lg:block w-[260px] shrink-0">
        <div class="sticky top-4 max-h-[calc(100vh-2rem)] overflow-y-auto pr-2">
          <div class="text-[11px] font-semibold uppercase tracking-[0.16em] text-slate-400 mb-3 px-2">
            {{ L.contents }}
          </div>
          <nav class="space-y-1">
            <div v-for="ch in tocChapters" :key="ch.id" :class="`ac-${accentOf(ch.role)}`">
              <a
                href="#"
                class="toc-link flex items-center gap-2 px-2 py-[7px] rounded-md text-[13px] font-semibold text-slate-700 hover:text-slate-900"
                :class="{ active: activeId === ch.id }"
                @click.prevent="go(ch.id)"
              >
                <span class="w-1.5 h-1.5 rounded-full shrink-0" :style="{ background: 'var(--ac)' }"></span>
                <span class="truncate">{{ ch.title }}</span>
              </a>
              <div class="pl-3 mt-0.5 mb-1 space-y-px">
                <a
                  v-for="s in ch.sections"
                  :key="s.id"
                  href="#"
                  class="toc-link block pl-4 pr-2 py-[5px] rounded-md text-[12.5px] text-slate-500 hover:text-slate-800 leading-snug"
                  :class="{ active: activeId === s.id }"
                  @click.prevent="go(s.id)"
                >
                  <span class="mono text-[10px] text-slate-400 mr-1.5">{{ s.num }}</span>{{ s.title }}
                </a>
              </div>
            </div>
          </nav>
        </div>
      </aside>

      <!-- ░░ CONTENT ░░ -->
      <main class="min-w-0 flex-1 max-w-[860px]">
        <!-- cover -->
        <header class="pb-8 border-b border-slate-200">
          <div class="flex items-center justify-between gap-4 flex-wrap">
            <div class="flex items-center gap-2.5">
              <span class="inline-block w-7 h-px bg-muw-coral"></span>
              <span class="text-[12px] font-semibold uppercase tracking-[0.2em] text-muw-coral">{{ L.kicker }}</span>
            </div>
            <!-- language toggle -->
            <div class="no-print inline-flex items-center rounded-lg border border-slate-200 overflow-hidden text-[12px] font-medium">
              <button
                class="px-2.5 py-1 transition"
                :class="lang === 'de' ? 'bg-muw-blue text-white' : 'text-slate-600 hover:bg-slate-50'"
                @click="lang = 'de'"
              >Deutsch</button>
              <button
                class="px-2.5 py-1 transition border-l border-slate-200"
                :class="lang === 'en' ? 'bg-muw-blue text-white' : 'text-slate-600 hover:bg-slate-50'"
                @click="lang = 'en'"
              >English</button>
            </div>
          </div>
          <h1 class="mt-5 font-serif text-[clamp(2rem,4.5vw,3rem)] font-semibold tracking-tight text-muw-blue leading-[1.05]">
            {{ manual.meta.product }}
          </h1>
          <p class="mt-3 text-[1.02rem] text-slate-600 max-w-[52ch] leading-relaxed">{{ manual.meta.subtitle }}</p>

          <!-- role cards (only the user's visible chapters) -->
          <div class="mt-7 grid sm:grid-cols-2 gap-3">
            <a
              v-for="ch in tocChapters"
              :key="ch.id"
              href="#"
              :class="`ac-${accentOf(ch.role)}`"
              class="role-card group flex items-start gap-3 rounded-xl border border-slate-200 bg-white p-3.5 transition hover:shadow-[0_4px_14px_rgba(17,29,78,.06)]"
              @click.prevent="go(ch.id)"
            >
              <span class="mt-1 w-2.5 h-2.5 rounded-full shrink-0" :style="{ background: 'var(--ac)' }"></span>
              <span class="min-w-0">
                <span class="block text-[13.5px] font-semibold text-slate-900">{{ ch.title }}</span>
                <span class="block text-[11.5px] text-slate-500 leading-snug mt-0.5" v-html="inlineHtml(visibleChapters.find(c => c.id === ch.id)?.oneLiner || '')"></span>
              </span>
            </a>
          </div>

          <div class="mt-7 flex flex-wrap items-center gap-x-6 gap-y-2 text-[12px] text-slate-500">
            <span class="inline-flex items-center gap-2"><span class="w-1.5 h-1.5 rounded-full bg-muw-teal"></span>GCP / 21 CFR Part 11</span>
            <span class="mono">{{ manual.meta.version }}</span>
            <button class="no-print ml-auto inline-flex items-center gap-1.5 text-[12px] font-medium text-slate-500 hover:text-muw-blue transition" @click="printManual">
              <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M6 9V2h12v7M6 18H4a2 2 0 0 1-2-2v-5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v5a2 2 0 0 1-2 2h-2M6 14h12v8H6z"/></svg>
              {{ L.print }}
            </button>
          </div>
        </header>

        <!-- chapters -->
        <div
          v-for="ch in visibleChapters"
          :id="ch.id"
          :key="ch.id"
          :class="`ac-${accentOf(ch.role)}`"
          class="manual-chapter scroll-mt-4"
        >
          <header class="pt-12 pb-2">
            <div class="flex items-center gap-2.5 mb-3">
              <span class="w-2.5 h-2.5 rounded-full" :style="{ background: 'var(--ac)' }"></span>
              <span class="text-[12px] font-semibold uppercase tracking-[0.2em]" :style="{ color: 'var(--ac)' }">{{ ch.kicker }}</span>
            </div>
            <h2 class="font-serif text-[clamp(1.7rem,3.5vw,2.4rem)] font-semibold tracking-tight text-slate-900 leading-[1.08]">{{ ch.title }}</h2>
            <div v-if="ch.deutsch && ch.deutsch !== ch.title" class="mt-1 text-[1rem] text-slate-400 font-serif italic">{{ ch.deutsch }}</div>
            <p class="mt-3 text-[1rem] text-slate-600 leading-relaxed max-w-[58ch]" v-html="inlineHtml(ch.oneLiner)"></p>
            <p v-for="(p, i) in ch.intro || []" :key="i" class="mt-3 text-[14px] text-slate-700 leading-relaxed max-w-[64ch]" v-html="inlineHtml(p)"></p>

            <div v-if="ch.callout" class="mt-5 rounded-xl border p-4 flex gap-3" :class="`callout callout-${ch.callout.kind}`">
              <svg class="shrink-0 mt-0.5" width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                <template v-if="ch.callout.kind === 'warn'"><path d="M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z"/><path d="M12 9v4M12 17h.01"/></template>
                <template v-else-if="ch.callout.kind === 'accent'"><path d="M12 2 3 7v6c0 5 3.8 8.3 9 9 5.2-.7 9-4 9-9V7z"/></template>
                <template v-else><circle cx="12" cy="12" r="10"/><path d="M12 16v-4M12 8h.01"/></template>
              </svg>
              <div class="min-w-0">
                <div v-if="ch.callout.title" class="text-[13px] font-semibold text-slate-800 mb-1" v-html="inlineHtml(ch.callout.title)"></div>
                <div class="text-[13px] text-slate-600 leading-relaxed" v-html="inlineHtml(ch.callout.text)"></div>
              </div>
            </div>
          </header>

          <!-- sections -->
          <section
            v-for="sec in ch.sections"
            :id="sec.id"
            :key="sec.id"
            class="manual-section scroll-mt-4 py-8 border-t border-slate-100"
          >
            <div class="flex items-start gap-4">
              <div class="wf-num shrink-0 text-[1.6rem] leading-none font-semibold mt-0.5" :style="{ color: 'var(--ac)', opacity: 0.32 }">{{ sec.num }}</div>
              <div class="min-w-0 flex-1">
                <div class="flex flex-wrap items-center gap-x-3 gap-y-1.5">
                  <h3 class="text-[1.28rem] font-semibold text-slate-900 tracking-tight">{{ sec.title }}</h3>
                  <span v-if="sec.deutsch && sec.deutsch !== sec.title" class="text-[14px] text-slate-400">{{ sec.deutsch }}</span>
                  <span v-if="sec.route" class="route-badge mono">{{ sec.route }}</span>
                </div>
                <div v-if="sec.roles && sec.roles.length" class="flex flex-wrap items-center gap-1.5 mt-2">
                  <span v-for="r in sec.roles" :key="r" :class="`ac-${accentOf(r)}`" class="role-chip">{{ roleLabel(r) }}</span>
                </div>
              </div>
            </div>

            <div class="lg:pl-[2.75rem]">
              <div v-if="sec.goal" class="my-4 flex gap-2.5 items-start">
                <span class="goal-tag shrink-0 mt-[3px]">{{ L.goal }}</span>
                <span class="text-[14.5px] text-slate-800 font-medium leading-snug" v-html="inlineHtml(sec.goal)"></span>
              </div>

              <p v-for="(p, i) in sec.body || []" :key="`b${i}`" class="text-[14px] text-slate-700 leading-relaxed my-3" v-html="inlineHtml(p)"></p>

              <ul v-if="sec.bullets && sec.bullets.length" class="space-y-2 my-4">
                <li v-for="(b, i) in sec.bullets" :key="`bl${i}`" class="flex gap-2.5 text-[14px] text-slate-700 leading-relaxed">
                  <span class="mt-[9px] w-1.5 h-1.5 rounded-full shrink-0" :style="{ background: 'var(--ac)' }"></span>
                  <span v-html="inlineHtml(b)"></span>
                </li>
              </ul>

              <figure v-if="sec.shotPre" class="my-6">
                <div class="shot-frame"><div class="shot-body"><img loading="lazy" :src="shotUrl(sec.shotPre)" alt="" /></div></div>
                <figcaption v-if="sec.shotPreCaption" class="mt-2 text-[12px] text-slate-500 italic" v-html="inlineHtml(sec.shotPreCaption)"></figcaption>
              </figure>

              <ol v-if="sec.steps && sec.steps.length" class="step-list space-y-2.5 my-4">
                <li v-for="(s, i) in sec.steps" :key="`s${i}`" class="text-[14px] text-slate-700 leading-relaxed pt-0.5" v-html="inlineHtml(s)"></li>
              </ol>

              <figure v-if="sec.shot" class="my-6">
                <div class="shot-frame" :class="{ 'shot-tall': sec.tall }"><div class="shot-body"><img loading="lazy" :src="shotUrl(sec.shot)" alt="" /></div></div>
                <figcaption v-if="sec.shotCaption" class="mt-2 text-[12px] text-slate-500 italic" v-html="inlineHtml(sec.shotCaption)"></figcaption>
              </figure>

              <figure v-if="sec.shot2" class="my-6">
                <div class="shot-frame"><div class="shot-body"><img loading="lazy" :src="shotUrl(sec.shot2)" alt="" /></div></div>
                <figcaption v-if="sec.shot2Caption" class="mt-2 text-[12px] text-slate-500 italic" v-html="inlineHtml(sec.shot2Caption)"></figcaption>
              </figure>

              <div v-if="sec.notes && sec.notes.length" class="my-5 rounded-xl bg-slate-50 border border-slate-200/80 p-4">
                <div class="text-[11px] font-semibold uppercase tracking-[0.13em] text-slate-400 mb-2">{{ L.notes }}</div>
                <ul class="space-y-2">
                  <li v-for="(n, i) in sec.notes" :key="`n${i}`" class="flex gap-2.5 text-[13px] text-slate-600 leading-relaxed">
                    <span class="mt-[7px] w-1 h-1 rounded-full bg-slate-400 shrink-0"></span>
                    <span v-html="inlineHtml(n)"></span>
                  </li>
                </ul>
              </div>

              <div v-if="sec.sub" class="mt-5 pl-4 border-l-2" :style="{ borderColor: 'var(--ac-bd)' }">
                <h4 class="text-[14px] font-semibold text-slate-800" v-html="inlineHtml(sec.sub.title)"></h4>
                <p class="text-[13.5px] text-slate-600 leading-relaxed mt-1.5" v-html="inlineHtml(sec.sub.text)"></p>
              </div>

              <div v-for="(ss, i) in sec.subsections || []" :key="`ss${i}`" class="mt-6 pl-4 border-l-2" :style="{ borderColor: 'var(--ac-bd)' }">
                <h4 class="text-[15px] font-semibold text-slate-900">
                  <span v-html="inlineHtml(ss.title)"></span>
                  <span v-if="ss.deutsch" class="text-slate-400 font-normal"> · {{ ss.deutsch }}</span>
                </h4>
                <div v-if="ss.goal" class="my-3 flex gap-2.5 items-start">
                  <span class="goal-tag shrink-0 mt-[3px]">{{ L.goal }}</span>
                  <span class="text-[13.5px] text-slate-700 font-medium leading-snug" v-html="inlineHtml(ss.goal)"></span>
                </div>
                <ol v-if="ss.steps && ss.steps.length" class="step-list space-y-2.5 my-3">
                  <li v-for="(s, j) in ss.steps" :key="`sss${j}`" class="text-[14px] text-slate-700 leading-relaxed pt-0.5" v-html="inlineHtml(s)"></li>
                </ol>
                <div v-if="ss.notes && ss.notes.length" class="my-4 rounded-xl bg-slate-50 border border-slate-200/80 p-4">
                  <div class="text-[11px] font-semibold uppercase tracking-[0.13em] text-slate-400 mb-2">{{ L.notes }}</div>
                  <ul class="space-y-2">
                    <li v-for="(n, j) in ss.notes" :key="`ssn${j}`" class="flex gap-2.5 text-[13px] text-slate-600 leading-relaxed">
                      <span class="mt-[7px] w-1 h-1 rounded-full bg-slate-400 shrink-0"></span>
                      <span v-html="inlineHtml(n)"></span>
                    </li>
                  </ul>
                </div>
              </div>
            </div>
          </section>
        </div>

        <footer class="py-12 mt-4 border-t border-slate-200 text-[12px] text-slate-400">
          <span class="mono">{{ manual.meta.version }} · {{ manual.meta.build }}</span>
        </footer>
      </main>
    </div>
  </div>
</template>

<style>
/* role-accent vars — set --ac / --ac-bg / --ac-bd per chapter via .ac-* */
.manual-shell .ac-blue   { --ac:#111d4e; --ac-bg:#f3f4f9; --ac-bd:#c6cce0; }
.manual-shell .ac-coral  { --ac:#d96849; --ac-bg:#fdf0eb; --ac-bd:#fad1c4; }
.manual-shell .ac-sky    { --ac:#1d6c98; --ac-bg:#e7f3fb; --ac-bd:#b5dcf1; }
.manual-shell .ac-teal   { --ac:#1d595c; --ac-bg:#e4f2ef; --ac-bd:#a8d7cd; }
.manual-shell .ac-tealdk { --ac:#163f42; --ac-bg:#e4f2ef; --ac-bd:#84c9bc; }

.manual-shell .mono { font-family:'JetBrains Mono', ui-monospace, monospace; }

.manual-shell .toc-link { position:relative; transition:color .12s, background .12s; }
.manual-shell .toc-link.active { color:var(--ac); background:var(--ac-bg); font-weight:600; }
.manual-shell .toc-link.active::before { content:''; position:absolute; left:0; top:4px; bottom:4px; width:2px; border-radius:2px; background:var(--ac); }

.manual-shell .role-card:hover { border-color:var(--ac-bd); }
.manual-shell .wf-num { font-family:'Newsreader', serif; }

.manual-shell .goal-tag {
  font-size:.62rem; font-weight:700; text-transform:uppercase; letter-spacing:.05em;
  padding:.12rem .4rem; border-radius:5px; color:var(--ac); background:var(--ac-bg); border:1px solid var(--ac-bd);
}
.manual-shell .route-badge {
  font-size:.72rem; color:var(--ac); background:var(--ac-bg); border:1px solid var(--ac-bd);
  padding:.12rem .5rem; border-radius:6px; white-space:nowrap;
}
.manual-shell .role-chip {
  font-size:.66rem; font-weight:600; letter-spacing:.02em; padding:.1rem .45rem; border-radius:5px; white-space:nowrap;
  color:var(--ac); background:var(--ac-bg); border:1px solid var(--ac-bd);
}

/* numbered step list with accent counters */
.manual-shell .step-list { counter-reset:step; }
.manual-shell .step-list > li { counter-increment:step; position:relative; padding-left:2.5rem; }
.manual-shell .step-list > li::before {
  content:counter(step); position:absolute; left:0; top:-1px;
  width:1.65rem; height:1.65rem; border-radius:9px; display:flex; align-items:center; justify-content:center;
  font-size:.78rem; font-weight:600; color:var(--ac); background:var(--ac-bg); border:1px solid var(--ac-bd);
}

/* inline `code` produced by v-html */
.manual-shell code {
  font-family:'JetBrains Mono', ui-monospace, monospace; font-size:.82em;
  background:#eef0f6; color:#2b3666; padding:.08em .38em; border-radius:5px; border:1px solid #e2e6f0; white-space:nowrap;
}

/* screenshot frame */
.manual-shell .shot-frame { border:1px solid #dfe3ec; border-radius:12px; overflow:hidden; box-shadow:0 1px 2px rgba(17,29,78,.04),0 10px 30px rgba(17,29,78,.05); background:#fff; }
.manual-shell .shot-body img { display:block; width:100%; height:auto; }
.manual-shell .shot-tall .shot-body { max-height:560px; overflow-y:auto; }

/* callouts */
.manual-shell .callout-info   { border-color:#c6cce0; background:#f3f4f9; color:#243366; }
.manual-shell .callout-accent { border-color:#fad1c4; background:#fdf0eb; color:#d96849; }
.manual-shell .callout-warn   { border-color:#fde2b8; background:#fef6e7; color:#b45309; }

@media print {
  .no-print { display:none !important; }
  .manual-shell .manual-chapter { break-before:page; }
  .manual-shell .manual-section { break-inside:avoid; }
  .manual-shell .shot-frame { break-inside:avoid; box-shadow:none; }
  .manual-shell .shot-tall .shot-body { max-height:none; overflow:visible; }
}
</style>
