package eu.webrobot.sentiment.stages

import eu.webrobot.plugin.sdk.{WArgs, WRow, WSourceStage, WebroStageContext}

/**
 * Source: loads documents from sentiment_documents filtered by source_type / time window / label.
 * Pipeline downstream can use the rows to drive enrichment, comparison, or export jobs.
 */
class SentimentLoadStage extends WSourceStage {

  override def name: String = "sentiment_load"

  override def produce(args: WArgs, ctx: WebroStageContext): Iterator[WRow] = {
    val sourceType = args.string(0, "")
    val fromDate   = args.string(1, "")
    val toDate     = args.string(2, "")
    val label      = args.string(3, "")
    val limit      = args.int(4, 1000)
    val orgId      = ctx.config("webrobot.org.id")

    val params = scala.collection.mutable.ListBuffer[Any](orgId)
    val whereParts = scala.collection.mutable.ListBuffer("org_id = ?")

    if (sourceType.nonEmpty) { whereParts += "source_type = ?";        params += sourceType }
    if (fromDate.nonEmpty)   { whereParts += "published_at >= ?::date"; params += fromDate }
    if (toDate.nonEmpty)     { whereParts += "published_at <  ?::date"; params += toDate }
    if (label.nonEmpty)      { whereParts += "label = ?";              params += label }

    val sql =
      s"""SELECT id, org_id, source_type, source_url, author, external_id,
         |       published_at, analyzed_at, language,
         |       label, polarity, confidence, text_snippet
         |FROM sentiment_documents
         |WHERE ${whereParts.mkString(" AND ")}
         |ORDER BY published_at DESC NULLS LAST, analyzed_at DESC
         |LIMIT ?""".stripMargin

    params += limit
    ctx.query(sql, params.toList)
  }
}
