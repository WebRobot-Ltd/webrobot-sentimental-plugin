import type { PluginViewProps } from './types';
import { ViewShell, NotImplemented } from './lib/ViewShell';

// TODO: settings UI for per-organization sentiment-plugin config.
// Likely controls:
//   - LLM provider / alias used by sentiment_analyze
//   - max_chars truncation default
//   - polarity threshold for "neutral" classification (currently hardcoded)
//   - cron schedule for sentiment_refresh_aggregates
//   - retention window for sentiment_documents
// Read/write goes through GET/PUT /api/sentiment/settings (org-scoped).
export default function Settings(props: PluginViewProps) {
  const { user } = props;
  if (user.role !== 'super_admin' && user.role !== 'admin') {
    return (
      <ViewShell title="Settings" props={props}>
        <p style={{ color: '#a00' }}>
          Forbidden — only admin / super_admin can access sentiment plugin settings.
          Current role: <code>{String(user.role)}</code>.
        </p>
      </ViewShell>
    );
  }

  return (
    <ViewShell title="Settings" props={props}>
      <NotImplemented
        hint="Org-scoped configuration for the sentiment plugin: LLM provider, max_chars, neutral threshold, aggregation cron, retention."
        apiHint="GET/PUT /sentiment/settings"
      />
    </ViewShell>
  );
}
