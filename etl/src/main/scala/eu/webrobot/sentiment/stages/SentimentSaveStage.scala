package eu.webrobot.sentiment.stages

import eu.webrobot.plugin.sdk.{WArgs, WRow, WSinkStage, WebroStageContext}
import eu.webrobot.sentiment.{JsonMini, SentimentLlm}

import java.security.MessageDigest
import java.sql.Timestamp
import java.time.{Instant, OffsetDateTime, ZoneOffset}

/**
 * Atomic sink: persists an enriched row across 4 tables in a single transaction.
 *   sentiment_documents   ← one row
 *   sentiment_emotions    ← 8 rows (Plutchik)
 *   sentiment_entities    ← N rows
 *   sentiment_aspects     ← M rows (linked back to entity_id when match by text)
 *
 * Deduplicates by (org_id, text_hash, source_type) — re-runs upsert into documents
 * and replace child rows so that re-analysis of the same text overwrites previous result.
 */
class SentimentSaveStage extends WSinkStage {
  import SentimentSaveStage.TapOps


  override def name: String = "sentiment_save"

  override def consume(row: WRow, args: WArgs, ctx: WebroStageContext): WRow = {
    val sourceType        = args.string(0, "other")
    val textField         = args.string(1, "text")
    val publishedAtField  = args.string(2, "published_at")
    val sourceUrlField    = args.string(3, "source_url")
    val authorField       = args.string(4, "author")
    val externalIdField   = args.string(5, "external_id")

    val orgId = ctx.config("webrobot.org.id")
    val text  = row.str(textField).getOrElse("").trim

    if (text.isEmpty) {
      ctx.warn(s"[$name] skipping row: empty $textField")
      return row
    }

    val polarity   = row.double("sentiment_polarity").getOrElse(0.0)
    val label      = row.str("sentiment_label").getOrElse("neutral")
    val confidence = row.double("sentiment_confidence").map(java.lang.Double.valueOf).orNull
    val language   = row.str("sentiment_language").getOrElse("")

    val emotionsJson = row.str("sentiment_emotions_json").getOrElse("{}")
    val entitiesJson = row.str("sentiment_entities_json").getOrElse("[]")
    val aspectsJson  = row.str("sentiment_aspects_json").getOrElse("[]")
    val rawResponse  = row.str("sentiment_raw_response").getOrElse("")
    val modelUsed    = row.str("sentiment_model_used").getOrElse("default")

    val publishedAt = parseTimestamp(row.str(publishedAtField))
    val sourceUrl   = row.str(sourceUrlField).getOrElse("")
    val author      = row.str(authorField).getOrElse("")
    val externalId  = row.str(externalIdField).getOrElse("")
    val textHash    = sha256(text)
    val snippet     = text.take(1000)

    ctx.transaction { conn =>
      // 1) Upsert document; capture document_id
      val upsertDoc = conn.prepareStatement(
        """INSERT INTO sentiment_documents
          |  (org_id, source_type, source_url, author, external_id,
          |   published_at, analyzed_at, text_hash, text_snippet, language,
          |   label, polarity, confidence, model_used, raw_response)
          |VALUES (?, ?, ?, ?, ?, ?, NOW(), ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
          |ON CONFLICT (org_id, text_hash, source_type)
          |DO UPDATE SET
          |  published_at = COALESCE(EXCLUDED.published_at, sentiment_documents.published_at),
          |  analyzed_at  = NOW(),
          |  text_snippet = EXCLUDED.text_snippet,
          |  language     = EXCLUDED.language,
          |  label        = EXCLUDED.label,
          |  polarity     = EXCLUDED.polarity,
          |  confidence   = EXCLUDED.confidence,
          |  model_used   = EXCLUDED.model_used,
          |  raw_response = EXCLUDED.raw_response
          |RETURNING id""".stripMargin
      )
      upsertDoc.setString(1, orgId)
      upsertDoc.setString(2, sourceType)
      upsertDoc.setString(3, nullIfEmpty(sourceUrl))
      upsertDoc.setString(4, nullIfEmpty(author))
      upsertDoc.setString(5, nullIfEmpty(externalId))
      publishedAt match {
        case Some(ts) => upsertDoc.setTimestamp(6, ts)
        case None     => upsertDoc.setNull(6, java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
      }
      upsertDoc.setString(7, textHash)
      upsertDoc.setString(8, snippet)
      upsertDoc.setString(9, nullIfEmpty(language))
      upsertDoc.setString(10, label)
      upsertDoc.setDouble(11, polarity)
      if (confidence == null) upsertDoc.setNull(12, java.sql.Types.DOUBLE) else upsertDoc.setDouble(12, confidence)
      upsertDoc.setString(13, modelUsed)
      upsertDoc.setString(14, if (rawResponse.isEmpty) "{}" else rawResponse)

      val rs = upsertDoc.executeQuery()
      if (!rs.next()) throw new RuntimeException(s"[$name] insert returned no id")
      val documentId = rs.getLong(1)
      rs.close()
      upsertDoc.close()

      // 2) Replace child rows (idempotent re-run)
      conn.prepareStatement("DELETE FROM sentiment_emotions WHERE document_id = ?").tap(_.setLong(1, documentId)).executeUpdate()
      conn.prepareStatement("DELETE FROM sentiment_aspects  WHERE document_id = ?").tap(_.setLong(1, documentId)).executeUpdate()
      conn.prepareStatement("DELETE FROM sentiment_entities WHERE document_id = ?").tap(_.setLong(1, documentId)).executeUpdate()

      // 3) Insert emotions
      val emotionsMap = parseEmotionsJson(emotionsJson)
      val insertEmotion = conn.prepareStatement(
        "INSERT INTO sentiment_emotions (document_id, org_id, emotion, score) VALUES (?, ?, ?, ?)"
      )
      SentimentLlm.Emotions.foreach { e =>
        insertEmotion.setLong(1, documentId)
        insertEmotion.setString(2, orgId)
        insertEmotion.setString(3, e)
        insertEmotion.setDouble(4, emotionsMap.getOrElse(e, 0.0))
        insertEmotion.addBatch()
      }
      insertEmotion.executeBatch()
      insertEmotion.close()

      // 4) Insert entities, capture id by text for aspect linking
      val entityList = parseEntitiesJson(entitiesJson)
      val insertEntity = conn.prepareStatement(
        """INSERT INTO sentiment_entities
          |  (document_id, org_id, canonical_id, text, entity_type, start_offset, end_offset)
          |VALUES (?, ?, NULL, ?, ?, ?, ?)
          |RETURNING id, text""".stripMargin
      )
      val entityIdByText = scala.collection.mutable.Map.empty[String, Long]
      entityList.foreach { e =>
        insertEntity.setLong(1, documentId)
        insertEntity.setString(2, orgId)
        insertEntity.setString(3, e.text)
        insertEntity.setString(4, e.entityType)
        e.startOffset match {
          case Some(s) => insertEntity.setInt(5, s)
          case None    => insertEntity.setNull(5, java.sql.Types.INTEGER)
        }
        e.endOffset match {
          case Some(en) => insertEntity.setInt(6, en)
          case None     => insertEntity.setNull(6, java.sql.Types.INTEGER)
        }
        val ers = insertEntity.executeQuery()
        if (ers.next()) entityIdByText.update(ers.getString(2), ers.getLong(1))
        ers.close()
      }
      insertEntity.close()

      // 5) Insert aspects
      val aspectList = parseAspectsJson(aspectsJson)
      val insertAspect = conn.prepareStatement(
        """INSERT INTO sentiment_aspects
          |  (document_id, entity_id, org_id, entity_text, entity_type, polarity, span)
          |VALUES (?, ?, ?, ?, ?, ?, ?)""".stripMargin
      )
      aspectList.foreach { a =>
        insertAspect.setLong(1, documentId)
        entityIdByText.get(a.entityText) match {
          case Some(eid) => insertAspect.setLong(2, eid)
          case None      => insertAspect.setNull(2, java.sql.Types.BIGINT)
        }
        insertAspect.setString(3, orgId)
        insertAspect.setString(4, a.entityText)
        insertAspect.setString(5, entityList.find(_.text == a.entityText).map(_.entityType).getOrElse("OTHER"))
        insertAspect.setDouble(6, a.polarity)
        insertAspect.setString(7, nullIfEmpty(a.span))
        insertAspect.addBatch()
      }
      if (aspectList.nonEmpty) insertAspect.executeBatch()
      insertAspect.close()
    }

    row.set("_sentiment_saved", true)
  }

  // ── helpers ──────────────────────────────────────────────────────────────

  private def parseEmotionsJson(s: String): Map[String, Double] =
    JsonMini.parse(s) match {
      case m: Map[String, Any] @unchecked =>
        m.collect {
          case (k, v: Double) => k -> v
          case (k, v: Long)   => k -> v.toDouble
          case (k, v: Int)    => k -> v.toDouble
        }
      case _ => Map.empty
    }

  private def parseEntitiesJson(s: String): Seq[eu.webrobot.sentiment.Entity] =
    JsonMini.parse(s) match {
      case arr: Seq[Any] @unchecked => arr.flatMap {
        case m: Map[String, Any] @unchecked =>
          val text  = m.getOrElse("text", "").toString.trim
          val tpe   = m.getOrElse("type", "OTHER").toString.toUpperCase
          val start = m.get("start").collect { case n: Long => n.toInt; case n: Double => n.toInt; case n: Int => n }
          val end   = m.get("end").collect   { case n: Long => n.toInt; case n: Double => n.toInt; case n: Int => n }
          if (text.nonEmpty) Some(eu.webrobot.sentiment.Entity(text, tpe, start, end)) else None
        case _ => None
      }
      case _ => Seq.empty
    }

  private def parseAspectsJson(s: String): Seq[eu.webrobot.sentiment.Aspect] =
    JsonMini.parse(s) match {
      case arr: Seq[Any] @unchecked => arr.flatMap {
        case m: Map[String, Any] @unchecked =>
          val text = m.getOrElse("entity_text", "").toString.trim
          val pol  = m.get("polarity").collect {
            case n: Double => n
            case n: Long   => n.toDouble
            case n: Int    => n.toDouble
          }.getOrElse(0.0)
          val span = m.getOrElse("span", "").toString
          if (text.nonEmpty) Some(eu.webrobot.sentiment.Aspect(text, pol, span)) else None
        case _ => None
      }
      case _ => Seq.empty
    }

  private def parseTimestamp(opt: Option[String]): Option[Timestamp] = opt.flatMap { s =>
    val t = s.trim
    if (t.isEmpty) None
    else scala.util.Try {
      // Try ISO-8601 with offset, then plain instant
      val odt = OffsetDateTime.parse(t)
      Timestamp.from(odt.toInstant)
    }.orElse(scala.util.Try {
      Timestamp.from(Instant.parse(t))
    }).orElse(scala.util.Try {
      // Fallback: epoch millis
      new Timestamp(t.toLong)
    }).toOption
  }

  private def sha256(s: String): String = {
    val md = MessageDigest.getInstance("SHA-256")
    md.digest(s.getBytes("UTF-8")).map("%02x".format(_)).mkString
  }

  private def nullIfEmpty(s: String): String = if (s == null || s.isEmpty) null else s
}

object SentimentSaveStage {
  // Top-level value class avoids "value class may not be a member of another class"
  implicit class TapOps[A](val a: A) extends AnyVal {
    def tap(f: A => Unit): A = { f(a); a }
  }
}
