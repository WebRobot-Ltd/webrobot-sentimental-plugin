package eu.webrobot.sentiment.stages

import eu.webrobot.plugin.sdk.{WArgs, WRow, WSinkStage, WebroStageContext}

class SentimentSaveStage extends WSinkStage {

  override def name: String = "sentiment_save"

  override def consume(row: WRow, args: WArgs, ctx: WebroStageContext): WRow = {
    val entityIdField = args.string(0, "id")
    val entityType    = args.string(1, "")
    val textField     = args.string(2, "text")
    val labelField    = args.string(3, "sentiment_label")
    val scoreField    = args.string(4, "sentiment_score")

    val orgId    = ctx.config("webrobot.org.id")
    val entityId = row.str(entityIdField).getOrElse("")
    val label    = row.str(labelField).getOrElse("neutral")
    val score    = row.double(scoreField).map(java.lang.Double.valueOf).orNull
    val snippet  = row.str(textField).map(_.take(500)).getOrElse("")

    if (entityId.nonEmpty && entityType.nonEmpty) {
      ctx.execute(
        """INSERT INTO sentiment_results (org_id, entity_id, entity_type, text_snippet, label, score, analyzed_at)
          |VALUES (?, ?, ?, ?, ?, ?, NOW())
          |ON CONFLICT (org_id, entity_id, entity_type)
          |DO UPDATE SET label = EXCLUDED.label, score = EXCLUDED.score,
          |              text_snippet = EXCLUDED.text_snippet, analyzed_at = NOW()""".stripMargin,
        Seq(orgId, entityId, entityType, snippet, label, score)
      )
    } else {
      ctx.warn(s"[$name] skipping row: missing entity_id ($entityIdField) or entity_type")
    }

    row
  }
}
