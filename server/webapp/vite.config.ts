import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

// Build lands in the Ktor server's static resources; the Gradle :server build
// runs `npm run ci-build` automatically (see server/build.gradle.kts).
export default defineConfig({
  plugins: [react(), tailwindcss()],
  build: {
    outDir: process.env.WEBAPP_OUT ?? '../src/main/resources/web',
    emptyOutDir: true,
  },
  server: {
    // `npm run dev` proxies API calls to a locally running server.
    proxy: { '/api': 'http://127.0.0.1:8080' },
  },
});
