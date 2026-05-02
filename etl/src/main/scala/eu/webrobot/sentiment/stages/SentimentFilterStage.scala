package eu.webrobot.sentiment.stages

import eu.webrobot.plugin.sdk.{WArgs, WRow, WFilterStage}

class SentimentFilterStage extends WFilterStage {

  override def name: String = "sentiment_filter"

  override def include(row: WRow, args: WArgs): Boolean = {
    val labelFilter = args.string(0, "")
    val minScore    = args.double(1, 0.0)

    val labelOk = labelFilter.isEmpty || row.str("sentiment_label").exists(_ == labelFilter)
    val scoreOk = row.double("sentiment_score").exists(_ >= minScore)

    labelOk && scoreOk
  }
}
