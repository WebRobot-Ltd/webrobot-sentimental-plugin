# Sentimental Plugin — Next.js UI

Hot-loaded dashboard plugin for the WebRobot Sentimental Plugin
(`pluginType: nextjs`, `pluginId: sentimental-plugin-ui`). Renders inside
the WebRobot ELT dashboard at
`/dashboard/extensions/sentimental-plugin-ui/<viewId>`.

This is the **frontend module** of the sentimental-plugin monorepo and
sits next to:

```
../etl/    Scala 2.13 — ETL stages (sentiment_analyze, sentiment_save, ...)
../api/    Java 11    — REST endpoints (queried by this UI)
../cli/    Java 11    — `webrobot sentiment ...` CLI subcommands
./        ← you are here: Next.js UI hot-loaded as ESM bundles
```

## Build

```bash
yarn install
yarn build       # → dist/Overview.js, dist/Documents.js, dist/Emotions.js, dist/Entities.js, dist/Settings.js
yarn package     # → sentimental-plugin-ui.zip (manifest.json + dist/)
```

The build invokes Vite **once per view** so each `dist/<View>.js` is a
self-contained ESM module with React inlined. Multi-entry Vite would
split shared deps (jsx-runtime, react) into a chunk that doesn't resolve
through blob-URL `import()`, which is why we don't use it.

Bundle size, gzipped: ~20 KB per view (React inline costs ~18 KB).

## Deploy

After `yarn build && yarn package`:

1. **Upload the ZIP to MinIO** at the path declared in
   `manifest.json → ui.zipPath`:
   ```
   s3a://sparklogs-data/plugins/sentimental-plugin-ui/sentimental-plugin-ui.zip
   ```

   With the WebRobot CLI:
   ```bash
   webrobot plugins upload \
     --file sentimental-plugin-ui.zip \
     --plugin-id sentimental-plugin-ui \
     --plugin-type nextjs
   ```

   Or directly with `mc` for ops:
   ```bash
   mc cp sentimental-plugin-ui.zip \
     myminio/sparklogs-data/plugins/sentimental-plugin-ui/sentimental-plugin-ui.zip
   ```

2. **Register the plugin installation** so the dashboard discovers it:
   POST to the Jersey admin endpoint
   `/webrobot/api/admin/plugin-installations` with `plugin_id`,
   `plugin_type='nextjs'`, `enabled=true`, and the `ui_zip_path` from
   step 1 (the CLI does this in one shot).

3. **Enable for the target organizations** via the dashboard
   `/dashboard/extensions/sentimental-plugin-ui` → super-admin toggle.

## Plugin context (`PluginViewProps`)

Every view receives this stable contract — defined in
[`src/types.ts`](src/types.ts) and mirrored in the dashboard at
`webrobot-elt-clouddashboard/frontend/plugins/ui/types.ts`:

```ts
interface PluginViewProps {
  pluginId: string;       // "sentimental-plugin-ui"
  viewId: string;         // "overview" | "documents" | "emotions" | "entities" | "settings"
  componentName: string;  // "dist/Overview.js" — the bundle the host requested
  installations: PluginInstallation[];
  token: string | null;   // JWT — use in Authorization headers
  user: {
    role: 'super_admin' | 'admin' | 'developer' | 'viewer' | 'authenticated' | null;
    organizationId: string | null;
    isSemiManaged: boolean;
  };
  apiBaseUrl: string;     // "" = same-origin; otherwise prefix for fetch
  buildType: string | null;
}
```

**Do** consume only `props`. **Don't** import dashboard internals
(`@/components/ui/*`, `@/lib/auth`, `@/hooks/*`) — your bundle must
work as a standalone ESM module loaded into an unknown host.

## Views (current scaffold)

| View       | Status     | Real data source (when wired)                          |
|------------|------------|--------------------------------------------------------|
| Overview   | ✅ smoke   | KPIs from `/sentiment/daily_overall` aggregates        |
| Documents  | placeholder | `/sentiment/documents?source_type=&from=&to=`         |
| Emotions   | placeholder | `/sentiment/daily_emotions` (Plutchik 8-axis)         |
| Entities   | placeholder | `/sentiment/daily_by_entity`, `/sentiment/canonical_entities` |
| Settings   | placeholder | `/sentiment/settings` (org-scoped config)              |

The Overview view is a working hot-load smoke test that dumps the host
context. The other 4 are intentionally minimal placeholders pointing at
where to wire the real API call from `../api/`.

## Limits (v1)

- React (~18 KB gz) is inlined per bundle. v2 (import maps) will share
  React from the host, dropping ~150 KB total per plugin.
- No CSS bundling pipeline yet — use inline styles or styled-jsx.
- Dashboard caches the plugin ZIP in-memory for 1 h. To force a reload
  after an update, disable + re-enable the plugin from the admin UI.

## License

[MIT](../LICENSE) (root of the sentimental-plugin repo).
