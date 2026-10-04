-- ============================================================
-- Sentimental Plugin — per-run / per-topic scoping
-- ============================================================
-- Documents accumulate across runs for the same org (dedup key is
-- (org_id, text_hash, source_type)), so the aggregation endpoints used to
-- mix every past run of an org into one view. `campaign` is a caller-chosen
-- token (the app generates one per launch, or reuses one per topic) that the
-- analyst stamps on every document it persists, so the charts/dataset can be
-- scoped to a single run/topic. NULL = legacy/unscoped (still shown when no
-- campaign filter is passed).
-- ============================================================

ALTER TABLE sentiment_documents ADD COLUMN IF NOT EXISTS campaign TEXT;

CREATE INDEX IF NOT EXISTS sentiment_documents_org_campaign_idx
    ON sentiment_documents (org_id, campaign);
