import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'node:path';

// v1 hot-load contract: ONE self-contained ESM bundle per view, React
// inlined. Multi-entry Vite builds split shared deps (jsx-runtime, react)
// into a separate chunk that doesn't resolve via blob-URL `import()`.
// Build script invokes Vite once per entry — pick which via VITE_ENTRY.
const entry = process.env.VITE_ENTRY;
if (!entry) {
  throw new Error(
    'VITE_ENTRY env var required. Use the build script (yarn build), do not call vite build directly.'
  );
}

export default defineConfig({
  plugins: [react()],
  // CRITICAL for browser execution: replace `process.env.NODE_ENV` at build
  // time. In Vite *library* mode this is NOT done by default — only in app
  // mode. React's jsx-runtime / react-dom production bundles read
  // `process.env.NODE_ENV` directly; without this define the bundle throws
  // `ReferenceError: process is not defined` when the dashboard executes
  // it via blob-URL `import()`.
  define: {
    'process.env.NODE_ENV': JSON.stringify('production'),
  },
  build: {
    outDir: 'dist',
    emptyOutDir: false,
    target: 'es2020',
    minify: 'esbuild',
    sourcemap: true,
    lib: {
      entry: path.resolve(__dirname, `src/${entry}.tsx`),
      formats: ['es'],
      fileName: () => `${entry}.js`,
    },
  },
});
