import { useEffect, useState } from 'react';
import type { PluginViewProps } from './types';
import { ViewShell } from './lib/ViewShell';

// Smoke-test view: proves the dashboard fetched this bundle from MinIO,
// executed it via blob-URL `import()`, and resolved the default export.
// Replace with real KPIs (docs analyzed today, polarity trend, top
// entities) when wiring sentiment_daily_overall + by_entity aggregates.
export default function Overview(props: PluginViewProps) {
  const { installations, apiBaseUrl, token } = props;
  const [now, setNow] = useState<string>(() => new Date().toISOString());

  useEffect(() => {
    const id = setInterval(() => setNow(new Date().toISOString()), 1000);
    return () => clearInterval(id);
  }, []);

  return (
    <ViewShell title="Overview" props={props}>
      <p>
        Hot-load smoke test. Replace this body with the real KPI cards once the
        api/ module's <code>/sentiment/daily_overall</code> endpoint is wired:
      </p>
      <ul style={{ marginTop: 8 }}>
        <li><strong>Total docs analyzed</strong> (today / 7d / 30d)</li>
        <li><strong>Polarity trend</strong> (avg by day)</li>
        <li><strong>Top entities</strong> (by mention count, with avg sentiment)</li>
        <li><strong>Dominant emotion</strong> per timeframe (Plutchik 8)</li>
      </ul>

      <table style={{ borderCollapse: 'collapse', marginTop: 16, fontSize: 13 }}>
        <tbody>
          <tr><Td k="apiBaseUrl">{apiBaseUrl || '(same-origin)'}</Td></tr>
          <tr><Td k="token">{token ? `${token.slice(0, 12)}…` : 'null'}</Td></tr>
          <tr><Td k="installations">{installations.length}</Td></tr>
          <tr><Td k="bundle ticked">{now}</Td></tr>
        </tbody>
      </table>
    </ViewShell>
  );
}

function Td({ k, children }: { k: string; children: React.ReactNode }) {
  return (
    <>
      <td style={{ padding: '4px 12px 4px 0', color: '#6b7280', fontFamily: 'monospace' }}>{k}</td>
      <td style={{ padding: '4px 0', fontFamily: 'monospace' }}>{children}</td>
    </>
  );
}
