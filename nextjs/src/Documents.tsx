import type { PluginViewProps } from './types';
import { ViewShell, NotImplemented } from './lib/ViewShell';

// TODO: replace with paginated list reading from
//   GET /api/sentiment/documents?source_type=&from=&to=&label=&limit=
// (api/ module: SentimentApi.listDocuments). Filters: date range,
// source_type, polarity range, label. Each row links to a detail panel
// showing emotions / entities / aspects for that document.
export default function Documents(props: PluginViewProps) {
  return (
    <ViewShell title="Documents" props={props}>
      <NotImplemented
        hint="Paginated browser of analyzed documents (sentiment_documents joined to sentiment_emotions / sentiment_entities / sentiment_aspects)."
        apiHint="GET /sentiment/documents?source_type=&from=&to=&label=&limit="
      />
    </ViewShell>
  );
}
