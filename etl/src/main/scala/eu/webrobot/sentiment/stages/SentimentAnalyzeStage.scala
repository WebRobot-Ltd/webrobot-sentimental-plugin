package eu.webrobot.sentiment.stages

import eu.webrobot.plugin.sdk.{WArgs, WRow, WTransformStage, WebroStageContext}

class SentimentAnalyzeStage extends WTransformStage {

  override def name: String = "sentiment_analyze"

  override def transform(row: WRow, args: WArgs, ctx: WebroStageContext): WRow = {
    val textField  = args.string(0, "text")
    val model      = args.string(1, "default")
    val saveResult = args.bool(2, false)
    val entityType = args.string(3, "")

    val text = row.str(textField).getOrElse("").trim
    if (text.isEmpty) {
      ctx.warn(s"[$name] skipping row: '$textField' is empty")
      return row.set("sentiment_label", "neutral").set("sentiment_score", 0.0)
    }

    val prompt =
      s"""Analyze the sentiment of the following text. Reply with a JSON object only, no explanation.
         |Format: {"label": "positive"|"negative"|"neutral", "score": <float 0.0-1.0>}
         |
         |Text: $text""".stripMargin

    val response = ctx.llm().infer(prompt, model)

    val (label, score) = parseLlmResponse(response)

    val enriched = row
      .set("sentiment_label", label)
      .set("sentiment_score", score)

    if (saveResult && entityType.nonEmpty) {
      val entityId = row.str("id").orElse(row.str("ean")).getOrElse("")
      val orgId    = ctx.config("webrobot.org.id")
      val snippet  = text.take(500)
      ctx.execute(
        """INSERT INTO sentiment_results (org_id, entity_id, entity_type, text_snippet, label, score, analyzed_at)
          |VALUES (?, ?, ?, ?, ?, ?, NOW())
          |ON CONFLICT (org_id, entity_id, entity_type)
          |DO UPDATE SET label = EXCLUDED.label, score = EXCLUDED.score, analyzed_at = NOW()""".stripMargin,
        Seq(orgId, entityId, entityType, snippet, label, java.lang.Double.valueOf(score))
      )
    }

    enriched
  }

  private def parseLlmResponse(response: String): (String, Double) = {
    val labelPattern = """"label"\s*:\s*"(positive|negative|neutral)"""".r
    val scorePattern = """"score"\s*:\s*([0-9.]+)""".r
    val label = labelPattern.findFirstMatchIn(response).map(_.group(1)).getOrElse("neutral")
    val score = scorePattern.findFirstMatchIn(response).flatMap(m => m.group(1).toDoubleOption).getOrElse(0.5)
    (label, score)
  }
}
