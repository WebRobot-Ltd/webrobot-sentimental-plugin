// Build each view as its own self-contained ESM bundle by invoking Vite
// once per entry (the v1 hot-load contract requires no shared chunks).
import { spawnSync } from 'node:child_process';
import { rmSync, existsSync, readdirSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(__dirname, '..');
const distDir = path.join(root, 'dist');

const entries = ['Overview', 'Documents', 'Emotions', 'Entities', 'Settings'];

if (existsSync(distDir)) {
  rmSync(distDir, { recursive: true });
}

for (const entry of entries) {
  console.log(`\n→ Building ${entry}.tsx`);
  const result = spawnSync('npx', ['vite', 'build'], {
    cwd: root,
    env: { ...process.env, VITE_ENTRY: entry },
    stdio: 'inherit',
  });
  if (result.status !== 0) {
    console.error(`✗ Build failed for ${entry}`);
    process.exit(result.status ?? 1);
  }
}

console.log('\nProduced bundles in dist/:');
for (const f of readdirSync(distDir).sort()) {
  console.log(`  ${f}`);
}
