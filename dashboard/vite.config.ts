import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// The dev server proxies /api to the backend so the browser's Basic-auth prompt is same-origin.
export default defineConfig({
  plugins: [react()],
  server: { port: 5173, proxy: { '/api': process.env.BACKEND_URL ?? 'http://localhost:8080' } },
});
