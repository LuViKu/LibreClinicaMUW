import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'
import { fileURLToPath, URL } from 'node:url'

/**
 * Phase E.1 (2026-05-30): Vue 3 + Vite + Tailwind v4.
 *
 * The SPA bundle is consumed by the WAR build — `mvn package` runs
 * `pnpm install` + `pnpm build` via the Frontend Maven Plugin
 * declared in web/pom.xml. Vite output lands at
 * `web/src/main/webapp/app/`, where Tomcat serves it from
 * `/LibreClinica/app/index.html` at runtime.
 *
 * Dev mode (`pnpm dev`) runs the Vite dev server at
 * http://127.0.0.1:5173 with a proxy to the running Spring Boot
 * backend at http://127.0.0.1:8080/LibreClinica — that way the SPA
 * can talk to real `@RestController` endpoints during development
 * without CORS noise.
 */
export default defineConfig({
  plugins: [
    vue(),
    tailwindcss(),
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  base: '/LibreClinica/app/',
  build: {
    outDir: fileURLToPath(new URL('../main/webapp/app', import.meta.url)),
    emptyOutDir: true,
    sourcemap: true,
    target: 'es2022',
    cssCodeSplit: true,
    rollupOptions: {
      output: {
        manualChunks: {
          // Keep Vue / router / Pinia in one chunk; the rest splits per-route.
          vendor: ['vue', 'vue-router', 'pinia', 'vue-i18n'],
        },
      },
    },
  },
  server: {
    host: '127.0.0.1',
    port: 5173,
    strictPort: true,
    proxy: {
      // Forward backend-bound calls to the Spring Boot WAR running in
      // Docker Compose. The SPA never talks to the backend through `/app/`;
      // it calls `/MainMenu`, `/pages/*`, `/actuator/health`, etc.
      // directly. Keep this list in sync with the OpenAPI inventory.
      '^/(MainMenu|pages|actuator|j_spring_security_check|j_spring_security_logout|Logout|Login|ListStudySubjects|ViewStudySubject)': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true,
      },
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/setupTests.ts'],
  },
})
