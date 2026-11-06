import { defineConfig } from 'vitest/config';
import { svelte } from '@sveltejs/vite-plugin-svelte';
export default defineConfig({ plugins: [svelte()], base: './', build: { outDir: '../app/build/generated/webAssets/web', emptyOutDir: true }, test: { environment: 'jsdom', setupFiles: ['./src/test-setup.ts'] }, resolve: { conditions: ['browser'] } });
