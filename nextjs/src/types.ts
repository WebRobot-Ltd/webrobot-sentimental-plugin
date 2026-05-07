// PluginViewProps — copy of the host contract from
// webrobot-elt-clouddashboard/frontend/plugins/ui/types.ts.
// Plugins should NOT import from the host directly; they pin a copy here so
// the bundle is self-contained. Adding fields to the host is non-breaking;
// removing/renaming is breaking.
export type AppRole =
  | 'super_admin'
  | 'admin'
  | 'developer'
  | 'viewer'
  | 'authenticated';

export interface PluginInstallation {
  id: number;
  plugin_id: string;
  plugin_name?: string;
  plugin_type?: string;
  version?: string;
  build_type?: string;
  build_number?: number;
  organization_id?: string;
  enabled?: boolean;
  installed_at?: string;
  description?: string;
}

export interface PluginViewProps {
  pluginId: string;
  viewId: string;
  componentName: string;
  installations: PluginInstallation[];
  token: string | null;
  user: {
    role: AppRole | null;
    organizationId: string | null;
    isSemiManaged: boolean;
  };
  apiBaseUrl: string;
  buildType: string | null;
}

// Sentiment-specific shapes. These mirror the API responses the api/ module
// exposes (see eu.webrobot.sentiment.api.SentimentApi). The dashboard only
// reads these — never POSTs — so types are pure response shapes.
export interface SentimentDocument {
  id: number;
  organization_id: string;
  source_type: string;
  source_url?: string | null;
  author?: string | null;
  external_id?: string | null;
  text: string;
  language?: string | null;
  polarity: number;       // -1.0 .. +1.0
  label: 'positive' | 'negative' | 'neutral';
  confidence: number;     // 0.0 .. 1.0
  published_at?: string | null;
  analyzed_at: string;
}

export interface PlutchikScores {
  joy: number;
  trust: number;
  fear: number;
  surprise: number;
  sadness: number;
  disgust: number;
  anger: number;
  anticipation: number;
}

export interface SentimentEntity {
  id: number;
  document_id: number;
  entity_type: 'PERSON' | 'ORG' | 'BRAND' | 'PRODUCT' | 'LOC' | 'EVENT' | 'TOPIC' | 'OTHER';
  entity_text: string;
  canonical_id?: number | null;
  start_char?: number | null;
  end_char?: number | null;
}

export interface SentimentAspect {
  id: number;
  document_id: number;
  entity_id: number;
  polarity: number;
  confidence: number;
}

export interface DailyAggregate {
  day: string;          // ISO date
  source_type?: string | null;
  doc_count: number;
  avg_polarity: number;
  positive_count: number;
  negative_count: number;
  neutral_count: number;
}
