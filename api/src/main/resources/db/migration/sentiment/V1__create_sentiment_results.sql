CREATE TABLE IF NOT EXISTS sentiment_results (
    id          BIGSERIAL PRIMARY KEY,
    org_id      TEXT NOT NULL,
    entity_id   TEXT NOT NULL,
    entity_type TEXT NOT NULL,
    text_snippet TEXT,
    label       TEXT NOT NULL CHECK (label IN ('positive', 'negative', 'neutral')),
    score       DOUBLE PRECISION,
    analyzed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT sentiment_results_unique UNIQUE (org_id, entity_id, entity_type)
);

CREATE INDEX sentiment_results_org_type_idx  ON sentiment_results (org_id, entity_type);
CREATE INDEX sentiment_results_org_label_idx ON sentiment_results (org_id, label);
