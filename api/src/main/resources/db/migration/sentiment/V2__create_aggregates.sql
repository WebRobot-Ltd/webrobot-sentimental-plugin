-- ============================================================
-- Pre-aggregated tables for chart endpoints.
-- Refreshed by scheduled job (sentiment_refresh_aggregates) — not real-time.
-- ============================================================

CREATE TABLE IF NOT EXISTS sentiment_daily_overall (
    org_id          TEXT NOT NULL,
    day             DATE NOT NULL,
    source_type     TEXT NOT NULL,
    doc_count       BIGINT NOT NULL,
    avg_polarity    DOUBLE PRECISION NOT NULL,
    positive_count  BIGINT NOT NULL,
    negative_count  BIGINT NOT NULL,
    neutral_count   BIGINT NOT NULL,
    refreshed_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    PRIMARY KEY (org_id, day, source_type)
);

CREATE TABLE IF NOT EXISTS sentiment_daily_by_entity (
    org_id          TEXT NOT NULL,
    day             DATE NOT NULL,
    entity_text     TEXT NOT NULL,
    entity_type     TEXT NOT NULL,
    canonical_id    BIGINT,
    mention_count   BIGINT NOT NULL,
    avg_polarity    DOUBLE PRECISION NOT NULL,
    positive_count  BIGINT NOT NULL,
    negative_count  BIGINT NOT NULL,
    neutral_count   BIGINT NOT NULL,
    refreshed_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    PRIMARY KEY (org_id, day, entity_type, entity_text)
);

CREATE INDEX sentiment_daily_by_entity_org_day_idx
    ON sentiment_daily_by_entity (org_id, day DESC, mention_count DESC);

CREATE TABLE IF NOT EXISTS sentiment_daily_emotions (
    org_id          TEXT NOT NULL,
    day             DATE NOT NULL,
    emotion         TEXT NOT NULL,
    avg_score       DOUBLE PRECISION NOT NULL,
    doc_count       BIGINT NOT NULL,
    refreshed_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    PRIMARY KEY (org_id, day, emotion)
);
