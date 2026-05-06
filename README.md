# WebRobot Sentimental Plugin

LLM-powered rich sentiment analysis for WebRobot pipelines.

Output for each analyzed text:
- **Polarity** — `-1.0..+1.0` continuous score
- **Label** — `positive` / `negative` / `neutral`
- **Confidence** — `0.0..1.0`
- **Plutchik 8-emotion vector** — joy, trust, fear, surprise, sadness, disgust, anger, anticipation
- **Named entities** — PERSON / ORG / BRAND / PRODUCT / LOC / EVENT / TOPIC / OTHER
- **Aspect-based sentiment** — per-entity polarity (sentiment *about* each mentioned entity)
- **Language** (ISO-639-1)

## Architecture

Two modules in the same repo:

```
etl/        Scala 2.12 — ETL stages (Gradle build)
api/        Java 11    — REST endpoints (Maven build)
manifest.json
```

`pluginType: "both"` — the platform loads both modules from a single registration.

## Storage model

Documents are normalized across 4 tables. `published_at` (original timestamp) is separate from `analyzed_at` so all time-series queries reflect when the text was *authored*, not when we processed it.

```
sentiment_documents              one row per analyzed text
sentiment_emotions               8 rows per document (Plutchik scores)
sentiment_entities               N rows per document (NER results)
sentiment_aspects                M rows per document (per-entity sentiment)
sentiment_canonical_entities     normalization registry per org

sentiment_daily_overall          materialized aggregates — refreshed by sentiment_refresh_aggregates
sentiment_daily_by_entity
sentiment_daily_emotions
```

Dedup key: `UNIQUE (org_id, text_hash, source_type)`.

## ETL pipeline (typical)

```yaml
- stage: visit                                # browser automation
- stage: extract                              # row: { text, author, post_url, post_timestamp }
- stage: sentiment_analyze                    # adds sentiment_* fields via single LLM call
  args:
    - text_field: text
- stage: sentiment_save                       # atomic write to 4 tables
  args:
    - source_type: forum
    - text_field: text
    - published_at_field: post_timestamp
    - source_url_field: post_url
    - author_field: author
- stage: sentiment_filter                     # optional: keep only negative w/ specific entity
  args:
    - label: negative
    - contains_entity: "Brand X"
```

Schedule a separate refresh pipeline:

```yaml
- stage: sentiment_refresh_aggregates
  args:
    - lookback_days: 90
```

## REST API

All endpoints scope by org_id from JWT.

| Endpoint | Returns | Use |
|----------|--------|-----|
| `POST /sentiment/analyze` | enrichment JSON | on-demand single-text analysis |
| `GET  /sentiment/timeseries?bucket=day` | `[{ts, count, avg_polarity, pos, neg, neu}]` | line/area chart |
| `GET  /sentiment/distribution` | label → count map | pie/donut chart |
| `GET  /sentiment/emotions?entity_text=` | emotion → avg_score map | radar chart |
| `GET  /sentiment/entities/top?type=BRAND` | `[{entity, type, count, avg_polarity}]` | ranked bar chart |
| `GET  /sentiment/compare?entities=A,B,C` | per-entity time series | multi-series line |
| `GET  /sentiment/cooccurrence?entity=X` | `[{co_entity, count, avg_polarity}]` | network/heatmap |
| `GET  /sentiment/documents` | raw documents (filterable) | drill-down |

## Build

```bash
# ETL plugin
cd etl
GITHUB_TOKEN=<your-token> ./gradlew jar

# REST API plugin
cd ../api
mvn package
```

Both JARs are uploaded to the WebRobot plugin registry — the manifest `pluginType: "both"` instructs the platform to load both as a single plugin.

## Agent integration

The endpoints are designed to map directly to MCP tools for Claude Code integration. The accompanying `/webrobot-sentiment` skill (in [webrobot-claude-plugin](https://github.com/WebRobot-Ltd/webrobot-claude-plugin)) exposes these as conversational tools so an agent can answer "show me sentiment for Brand X this month" and emit a chart.
