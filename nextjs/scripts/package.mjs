// Bundles dist/ + manifest.json into <pluginId>.zip with the layout the
// dashboard expects (manifest at root, dist/<View>.js inside).
// The zip filename matches the basename of `manifest.ui.zipPath`.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import archiver from 'archiver';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(__dirname, '..');
const distDir = path.join(root, 'dist');
const manifestPath = path.join(root, 'manifest.json');
const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
const zipBaseName = manifest?.ui?.zipPath
  ? path.basename(manifest.ui.zipPath)
  : `${manifest.pluginId}.zip`;
const zipPath = path.join(root, zipBaseName);

if (!fs.existsSync(distDir)) {
  console.error('dist/ not found — run `yarn build` first.');
  process.exit(1);
}
if (!fs.existsSync(manifestPath)) {
  console.error('manifest.json not found at module root.');
  process.exit(1);
}

const out = fs.createWriteStream(zipPath);
const archive = archiver('zip', { zlib: { level: 9 } });

out.on('close', () => {
  console.log(`✅ ${zipPath} (${archive.pointer()} bytes)`);
});
archive.on('warning', (err) => {
  if (err.code === 'ENOENT') console.warn(err);
  else throw err;
});
archive.on('error', (err) => {
  throw err;
});

archive.pipe(out);
archive.file(manifestPath, { name: 'manifest.json' });
archive.directory(distDir, 'dist');
await archive.finalize();
