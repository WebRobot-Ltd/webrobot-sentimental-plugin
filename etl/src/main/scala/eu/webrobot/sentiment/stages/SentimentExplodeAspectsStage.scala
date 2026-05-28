package eu.webrobot.sentiment.stages

import eu.webrobot.plugin.sdk.{WArgs, WExpandStage, WRow}
import eu.webrobot.sentiment.JsonMini

/**
 * Denormalizes the sentiment_aspects_json payload (produced by sentiment_analyze)
 * into ONE row per (document × aspect). Companion of sentiment_save: the latter
 * persists the normalized DB shape (sentiment_documents + sentiment_aspects),
 * this one produces a Trino/CSV/Parquet-friendly flat shape so client queries
 * don't have to UNNEST json_extract every time.
 *
 * Place AFTER sentiment_save in the pipeline so the DB persist still sees the
 * intact document-shape row, while save_csv / save_parquet downstream sees the
 * exploded flat rows:
 *
 *   sentiment_analyze → sentiment_save → sentiment_explode_aspects → save_csv
 *
 * Args (positional):
 *   args[0] aspects_field           — default "sentiment_aspects_json"
 *   args[1] keep_empty               — default false: rows with zero aspects
 *                                       are DROPPED. Set true to keep one row
 *                                       per document with NULL aspect columns
 *                                       (useful when you want the document
 *                                       count to match the input).
 *   args[2] entities_field           — default "sentiment_entities_json".
 *                                       When set + the entity is found by
 *                                       text-match, the row also carries
 *                                       aspect_entity_type (otherwise NULL).
 *
 * Adds to each emitted row:
 *   aspect_entity_text   String   — entity the aspect refers to ("Bitcoin")
 *   aspect_polarity      Double   — per-entity sentiment polarity (-1..+1)
 *   aspect_span          String   — text snippet driving the polarity
 *   aspect_entity_type   String   — NER type (ORG/PERSON/LOC/MONEY/OTHER),
 *                                   NULL when entities lookup misses
 *   aspect_index         Int      — 0-based position within the document's aspect list
 *
 * All original row fields (document-level: comment_text, author, posted_at,
 * sentiment_polarity, sentiment_label, etc.) are preserved on every emitted row,
 * so a Trino query like:
 *
 *   SELECT AVG(aspect_polarity), COUNT(*)
 *   FROM   investing_btc_sentiment_flat
 *   WHERE  aspect_entity_text ILIKE 'bitcoin'
 *     AND  posted_at >= NOW() - INTERVAL '7' DAY
 *
 * works without any json_extract or UNNEST.
 */
class SentimentExplodeAspectsStage extends WExpandStage {

  override def name: String = "sentiment_explode_aspects"

  override def expand(row: WRow, args: WArgs): Iterator[WRow] = {
    val aspectsField  = args.string(0, "sentiment_aspects_json")
    val keepEmpty     = args.bool(1, default = false)
    val entitiesField = args.string(2, "sentiment_entities_json")

    val aspectsJson  = row.str(aspectsField).getOrElse("[]")
    val entitiesJson = row.str(entitiesField).getOrElse("[]")

    val aspects     = parseAspects(aspectsJson)
    val typeByText  = parseEntityTypeIndex(entitiesJson)

    if (aspects.isEmpty) {
      if (keepEmpty) Iterator(emitNullAspect(row))
      else           Iterator.empty
    } else {
      aspects.iterator.zipWithIndex.map { case (a, idx) =>
        val entType = typeByText.getOrElse(a.text, null)
        row
          .set("aspect_entity_text", a.text)
          .set("aspect_polarity",    a.polarity)
          .set("aspect_span",        a.span)
          .set("aspect_entity_type", entType)
          .set("aspect_index",       idx)
      }
    }
  }

  private def emitNullAspect(row: WRow): WRow =
    row
      .set("aspect_entity_text", null)
      .set("aspect_polarity",    null)
      .set("aspect_span",        null)
      .set("aspect_entity_type", null)
      .set("aspect_index",       -1)

  // ── parsers (mirror SentimentSaveStage shapes; kept private so this file is self-contained) ──

  private case class AspectLite(text: String, polarity: Double, span: String)

  private def parseAspects(s: String): Seq[AspectLite] =
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
          if (text.nonEmpty) Some(AspectLite(text, pol, span)) else None
        case _ => None
      }
      case _ => Seq.empty
    }

  /** Returns a text → entity_type map for fast aspect→entity enrichment. */
  private def parseEntityTypeIndex(s: String): Map[String, String] =
    JsonMini.parse(s) match {
      case arr: Seq[Any] @unchecked =>
        arr.collect {
          case m: Map[String, Any] @unchecked =>
            val text = m.getOrElse("text", "").toString.trim
            val tpe  = m.getOrElse("type", "OTHER").toString.toUpperCase
            text -> tpe
        }.filter(_._1.nonEmpty).toMap
      case _ => Map.empty
    }
}
