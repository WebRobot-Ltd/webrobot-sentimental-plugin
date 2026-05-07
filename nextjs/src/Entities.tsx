import type { PluginViewProps } from './types';
import { ViewShell, NotImplemented } from './lib/ViewShell';

// TODO: replace with NER / aspect-based sentiment browser:
//   GET /api/sentiment/daily_by_entity?from=&to=&entity_type=
//   GET /api/sentiment/canonical_entities?type=
// Show top entities by mention count with avg polarity. Click → drill-down:
// daily polarity for that entity + sample documents that mention it.
export default function Entities(props: PluginViewProps) {
  return (
    <ViewShell title="Entities" props={props}>
      <NotImplemented
        hint="NER + aspect-based per-entity sentiment. Top mentioned brands / products / orgs / topics with avg polarity, click for daily drill-down."
        apiHint="GET /sentiment/daily_by_entity?from=&to=&entity_type="
      />
    </ViewShell>
  );
}
