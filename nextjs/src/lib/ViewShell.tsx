import type { PluginViewProps } from '../types';

// Common chrome for every sentiment view: header, header context strip
// (org, role, build), and the children. Inlined into each bundle by Vite
// (build runs once per entry → no shared chunks across bundles).
export function ViewShell({
  title,
  children,
  props,
}: {
  title: string;
  children: React.ReactNode;
  props: PluginViewProps;
}) {
  const { pluginId, viewId, user, buildType, token } = props;
  return (
    <div style={{ fontFamily: 'system-ui, sans-serif', padding: 16 }}>
      <div
        style={{
          display: 'flex',
          alignItems: 'baseline',
          gap: 16,
          paddingBottom: 12,
          borderBottom: '1px solid #e5e7eb',
        }}
      >
        <h2 style={{ margin: 0 }}>Sentiment Analysis — {title}</h2>
        <code style={{ color: '#6b7280', fontSize: 12 }}>
          {pluginId}/{viewId} · {String(user.role)} · org={user.organizationId ?? '(global)'} ·{' '}
          {buildType ?? '(no buildType)'} · token={token ? token.slice(0, 8) + '…' : 'null'}
        </code>
      </div>
      <div style={{ paddingTop: 16, color: '#1f2937' }}>{children}</div>
    </div>
  );
}

export function NotImplemented({
  hint,
  apiHint,
}: {
  hint: string;
  apiHint?: string;
}) {
  // Note: the example below is plain text, not interpolated. We wrap it in
  // a single JSX expression so JSX doesn't try to evaluate the inner `{...}`
  // sequences as JSX expressions (which would look up undefined variables
  // like `apiBaseUrl` and throw at render time).
  const fetchExample =
    'fetch(`${apiBaseUrl}/api/...`, { headers: { Authorization: `Bearer ${token}` } })';

  return (
    <div
      style={{
        background: '#fffbeb',
        border: '1px solid #fde68a',
        borderRadius: 6,
        padding: 16,
        color: '#78350f',
      }}
    >
      <strong>Placeholder.</strong> {hint}
      {apiHint && (
        <p style={{ marginBottom: 0, marginTop: 8, color: '#92400e', fontSize: 13 }}>
          When wiring the real view, call <code>{apiHint}</code> via{' '}
          <code>{fetchExample}</code>.
        </p>
      )}
    </div>
  );
}
