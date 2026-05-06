-- ============================================================
-- Sentimental Plugin — Core Schema
-- ============================================================
-- Documents are the atom of analysis: one analyzed text = one document.
-- Emotions, entities, and aspects are normalized into separate tables
-- so we can query / aggregate across each dimension efficiently.
-- ============================================================

CREATE TABLE IF NOT EXISTS sentiment_documents (
    id              BIGSERIAL PRIMARY KEY,
    org_id          TEXT NOT NULL,

    -- Source provenance
    source_type     TEXT NOT NULL,                  -- forum / review / news / social / other
    source_url      TEXT,
    author          TEXT,
    external_id     TEXT,                           -- ID in source system (post id, review id, etc.)

    -- Timestamps — published_at is the original moment the text was authored;
    -- analyzed_at is when we processed it. Charts time-series ALWAYS use published_at.
    published_at    TIMESTAMPTZ,
    analyzed_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    -- Text fingerprint for dedup
    text_hash       TEXT NOT NULL,
    text_snippet    TEXT,                           -- first 1000 chars
    language        TEXT,

    -- Aggregate sentiment of the whole document
    label           TEXT NOT NULL CHECK (label IN ('positive', 'negative', 'neutral')),
    polarity        DOUBLE PRECISION NOT NULL CHECK (polarity BETWEEN -1.0 AND 1.0),
    confidence      DOUBLE PRECISION,

    -- Audit trail of the LLM call
    model_used      TEXT,
    raw_response    JSONB,

    CONSTRAINT sentiment_documents_unique UNIQUE (org_id, text_hash, source_type)
);

CREATE INDEX sentiment_documents_org_published_idx
    ON sentiment_documents (org_id, published_at DESC);
CREATE INDEX sentiment_documents_org_source_published_idx
    ON sentiment_documents (org_id, source_type, published_at DESC);
CREATE INDEX sentiment_documents_org_label_idx
    ON sentiment_documents (org_id, label);

-- ------------------------------------------------------------
-- Plutchik 8-emotion scores: one row per emotion per document
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sentiment_emotions (
    document_id     BIGINT NOT NULL REFERENCES sentiment_documents(id) ON DELETE CASCADE,
    org_id          TEXT NOT NULL,
    emotion         TEXT NOT NULL CHECK (emotion IN
        ('joy', 'trust', 'fear', 'surprise', 'sadness', 'disgust', 'anger', 'anticipation')),
    score           DOUBLE PRECISION NOT NULL CHECK (score BETWEEN 0.0 AND 1.0),
    PRIMARY KEY (document_id, emotion)
);

CREATE INDEX sentiment_emotions_org_emotion_idx
    ON sentiment_emotions (org_id, emotion);

-- ------------------------------------------------------------
-- Named entities extracted from documents
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sentiment_canonical_entities (
    id              BIGSERIAL PRIMARY KEY,
    org_id          TEXT NOT NULL,
    entity_type     TEXT NOT NULL,
    canonical_name  TEXT NOT NULL,
    aliases         TEXT[] NOT NULL DEFAULT '{}',
    metadata        JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT canonical_entities_unique UNIQUE (org_id, entity_type, canonical_name)
);

CREATE INDEX canonical_entities_aliases_gin
    ON sentiment_canonical_entities USING GIN (aliases);

CREATE TABLE IF NOT EXISTS sentiment_entities (
    id              BIGSERIAL PRIMARY KEY,
    document_id     BIGINT NOT NULL REFERENCES sentiment_documents(id) ON DELETE CASCADE,
    org_id          TEXT NOT NULL,
    canonical_id    BIGINT REFERENCES sentiment_canonical_entities(id) ON DELETE SET NULL,

    text            TEXT NOT NULL,                  -- exact span as it appeared
    entity_type     TEXT NOT NULL,                  -- PERSON / ORG / BRAND / PRODUCT / LOC / EVENT / TOPIC / OTHER
    start_offset    INT,
    end_offset      INT
);

CREATE INDEX sentiment_entities_document_idx        ON sentiment_entities (document_id);
CREATE INDEX sentiment_entities_org_type_idx        ON sentiment_entities (org_id, entity_type);
CREATE INDEX sentiment_entities_canonical_idx       ON sentiment_entities (canonical_id);
CREATE INDEX sentiment_entities_org_text_idx        ON sentiment_entities (org_id, text);

-- ------------------------------------------------------------
-- Aspect-based sentiment: per-entity polarity within a document
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sentiment_aspects (
    id              BIGSERIAL PRIMARY KEY,
    document_id     BIGINT NOT NULL REFERENCES sentiment_documents(id) ON DELETE CASCADE,
    entity_id       BIGINT REFERENCES sentiment_entities(id) ON DELETE SET NULL,
    org_id          TEXT NOT NULL,

    entity_text     TEXT NOT NULL,                  -- denormalized for query without join
    entity_type     TEXT,
    polarity        DOUBLE PRECISION NOT NULL CHECK (polarity BETWEEN -1.0 AND 1.0),
    span            TEXT                            -- the specific phrase carrying this sentiment
);

CREATE INDEX sentiment_aspects_document_idx     ON sentiment_aspects (document_id);
CREATE INDEX sentiment_aspects_org_entity_idx   ON sentiment_aspects (org_id, entity_text);
CREATE INDEX sentiment_aspects_entity_id_idx    ON sentiment_aspects (entity_id);
