import type { PluginViewProps } from './types';
import { ViewShell, NotImplemented } from './lib/ViewShell';

// TODO: replace with Plutchik 8-emotion radar / heatmap reading from
//   GET /api/sentiment/daily_emotions?from=&to=&source_type=
// Each row in sentiment_daily_emotions has the 8 axes (joy, trust, fear,
// surprise, sadness, disgust, anger, anticipation). Aggregate by day or
// average across the window.
export default function Emotions(props: PluginViewProps) {
  return (
    <ViewShell title="Emotions" props={props}>
      <NotImplemented
        hint="Plutchik 8-emotion vector aggregated over time. Could render as a radar chart (recharts / d3) — keep it inline so the bundle stays under ~250 KB gzipped."
        apiHint="GET /sentiment/daily_emotions?from=&to=&source_type="
      />
    </ViewShell>
  );
}
