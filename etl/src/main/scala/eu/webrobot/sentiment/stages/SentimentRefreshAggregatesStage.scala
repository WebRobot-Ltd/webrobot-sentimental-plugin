package eu.webrobot.sentiment.stages

import eu.webrobot.plugin.sdk.{WArgs, WRow, WSourceStage, WebroStageContext}

/**
 * Recomputes the materialized aggregate tables for the current org over the last N days.
 * Acts as a SourceStage that emits a single summary row, so it can be embedded in a
 * scheduled pipeline (or run standalone via webrobot pipeline run).
 *
 * Tables refreshed:
 *   - sentiment_daily_overall
 *   - sentiment_daily_by_entity
 *   - sentiment_daily_emotions
 */
class SentimentRefreshAggregatesStage extends WSourceStage {

  override def name: String = "sentiment_refresh_aggregates"

  override def produce(args: WArgs, ctx: WebroStageContext): Iterator[WRow] = {
    val lookbackDays = args.int(0, 90)
    val orgId        = ctx.config("webrobot.org.id")

    ctx.log(s"[$name] refreshing aggregates for org=$orgId, lookback=${lookbackDays}d")

    ctx.transaction { conn =>
      // ── Daily overall ───────────────────────────────────────────────────
      val deleteOverall = conn.prepareStatement(
        "DELETE FROM sentiment_daily_overall WHERE org_id = ? AND day >= CURRENT_DATE - ?::int"
      )
      deleteOverall.setString(1, orgId); deleteOverall.setInt(2, lookbackDays)
      deleteOverall.executeUpdate(); deleteOverall.close()

      val insertOverall = conn.prepareStatement(
        """INSERT INTO sentiment_daily_overall
          |  (org_id, day, source_type, doc_count, avg_polarity,
          |   positive_count, negative_count, neutral_count, refreshed_at)
          |SELECT
          |  org_id,
          |  date_trunc('day', published_at)::date AS day,
          |  source_type,
          |  COUNT(*),
          |  AVG(polarity),
          |  COUNT(*) FILTER (WHERE label = 'positive'),
          |  COUNT(*) FILTER (WHERE label = 'negative'),
          |  COUNT(*) FILTER (WHERE label = 'neutral'),
          |  NOW()
          |FROM sentiment_documents
          |WHERE org_id = ?
          |  AND published_at >= CURRENT_DATE - ?::int
          |GROUP BY org_id, date_trunc('day', published_at), source_type""".stripMargin
      )
      insertOverall.setString(1, orgId); insertOverall.setInt(2, lookbackDays)
      val nOverall = insertOverall.executeUpdate(); insertOverall.close()
      ctx.log(s"[$name] sentiment_daily_overall: $nOverall rows")

      // ── Daily by entity ─────────────────────────────────────────────────
      val deleteByEntity = conn.prepareStatement(
        "DELETE FROM sentiment_daily_by_entity WHERE org_id = ? AND day >= CURRENT_DATE - ?::int"
      )
      deleteByEntity.setString(1, orgId); deleteByEntity.setInt(2, lookbackDays)
      deleteByEntity.executeUpdate(); deleteByEntity.close()

      val insertByEntity = conn.prepareStatement(
        """INSERT INTO sentiment_daily_by_entity
          |  (org_id, day, entity_text, entity_type, canonical_id,
          |   mention_count, avg_polarity,
          |   positive_count, negative_count, neutral_count, refreshed_at)
          |SELECT
          |  d.org_id,
          |  date_trunc('day', d.published_at)::date AS day,
          |  e.text,
          |  e.entity_type,
          |  MAX(e.canonical_id),
          |  COUNT(*),
          |  AVG(COALESCE(a.polarity, d.polarity)),
          |  COUNT(*) FILTER (WHERE COALESCE(
          |    CASE WHEN a.polarity IS NOT NULL THEN
          |      CASE WHEN a.polarity > 0.15 THEN 'positive'
          |           WHEN a.polarity < -0.15 THEN 'negative'
          |           ELSE 'neutral' END
          |    END,
          |    d.label) = 'positive'),
          |  COUNT(*) FILTER (WHERE COALESCE(
          |    CASE WHEN a.polarity IS NOT NULL THEN
          |      CASE WHEN a.polarity > 0.15 THEN 'positive'
          |           WHEN a.polarity < -0.15 THEN 'negative'
          |           ELSE 'neutral' END
          |    END,
          |    d.label) = 'negative'),
          |  COUNT(*) FILTER (WHERE COALESCE(
          |    CASE WHEN a.polarity IS NOT NULL THEN
          |      CASE WHEN a.polarity > 0.15 THEN 'positive'
          |           WHEN a.polarity < -0.15 THEN 'negative'
          |           ELSE 'neutral' END
          |    END,
          |    d.label) = 'neutral'),
          |  NOW()
          |FROM sentiment_documents d
          |JOIN sentiment_entities  e ON e.document_id = d.id
          |LEFT JOIN sentiment_aspects a
          |       ON a.document_id = d.id AND a.entity_id = e.id
          |WHERE d.org_id = ?
          |  AND d.published_at >= CURRENT_DATE - ?::int
          |GROUP BY d.org_id, date_trunc('day', d.published_at), e.text, e.entity_type""".stripMargin
      )
      insertByEntity.setString(1, orgId); insertByEntity.setInt(2, lookbackDays)
      val nByEntity = insertByEntity.executeUpdate(); insertByEntity.close()
      ctx.log(s"[$name] sentiment_daily_by_entity: $nByEntity rows")

      // ── Daily emotions ──────────────────────────────────────────────────
      val deleteEmotions = conn.prepareStatement(
        "DELETE FROM sentiment_daily_emotions WHERE org_id = ? AND day >= CURRENT_DATE - ?::int"
      )
      deleteEmotions.setString(1, orgId); deleteEmotions.setInt(2, lookbackDays)
      deleteEmotions.executeUpdate(); deleteEmotions.close()

      val insertEmotions = conn.prepareStatement(
        """INSERT INTO sentiment_daily_emotions (org_id, day, emotion, avg_score, doc_count, refreshed_at)
          |SELECT d.org_id,
          |       date_trunc('day', d.published_at)::date AS day,
          |       em.emotion,
          |       AVG(em.score),
          |       COUNT(DISTINCT d.id),
          |       NOW()
          |FROM sentiment_documents d
          |JOIN sentiment_emotions  em ON em.document_id = d.id
          |WHERE d.org_id = ?
          |  AND d.published_at >= CURRENT_DATE - ?::int
          |GROUP BY d.org_id, date_trunc('day', d.published_at), em.emotion""".stripMargin
      )
      insertEmotions.setString(1, orgId); insertEmotions.setInt(2, lookbackDays)
      val nEmotions = insertEmotions.executeUpdate(); insertEmotions.close()
      ctx.log(s"[$name] sentiment_daily_emotions: $nEmotions rows")

      Iterator.empty[WRow]
    }

    // Emit one summary row so downstream consumers (datasets, exports) see the run happened
    Iterator.single(
      eu.webrobot.plugin.sdk.WRow.empty
        .set("refresh_org_id",       orgId)
        .set("refresh_lookback_days", lookbackDays)
        .set("refreshed_at",          java.time.Instant.now().toString)
    )
  }
}
