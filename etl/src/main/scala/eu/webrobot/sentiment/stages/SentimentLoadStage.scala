package eu.webrobot.sentiment.stages

import eu.webrobot.plugin.sdk.{WArgs, WRow, WSourceStage, WebroStageContext}

class SentimentLoadStage extends WSourceStage {

  override def name: String = "sentiment_load"

  override def produce(args: WArgs, ctx: WebroStageContext): Iterator[WRow] = {
    val entityType = args.string(0, "")
    val label      = args.string(1, "")
    val limit      = args.int(2, 1000)
    val orgId      = ctx.config("webrobot.org.id")

    if (label.nonEmpty) {
      ctx.query(
        "SELECT * FROM sentiment_results WHERE org_id = ? AND entity_type = ? AND label = ? ORDER BY analyzed_at DESC LIMIT ?",
        Seq(orgId, entityType, label, limit)
      )
    } else {
      ctx.query(
        "SELECT * FROM sentiment_results WHERE org_id = ? AND entity_type = ? ORDER BY analyzed_at DESC LIMIT ?",
        Seq(orgId, entityType, limit)
      )
    }
  }
}
