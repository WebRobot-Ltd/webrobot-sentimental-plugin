package eu.webrobot.sentiment.stages

import eu.webrobot.plugin.sdk.{WArgs, WPartitionStage, WRow, WebroStageContext}
import eu.webrobot.sentiment.{JsonMini, SentimentLlm}

/**
 * Enriches each row in a partition with full sentiment analysis output via a single LLM call.
 *
 * Implemented as WPartitionStage (not WTransformStage) because LLM access requires the
 * platform-injected WebroStageContext, which is only available to context-aware stages.
 * WPartitionStage is also a good fit operationally: HTTP-bound LLM calls amortize well
 * when shared connection pooling and back-pressure live at partition scope.
 *
 * Adds these fields to each row (ready to be persisted by sentiment_save):
 *   - sentiment_polarity      : Double  (-1.0..+1.0)
 *   - sentiment_label         : String  (positive|negative|neutral)
 *   - sentiment_confidence    : Double  (0.0..1.0)
 *   - sentiment_language      : String  (ISO-639-1)
 *   - sentiment_emotions_json : JSON string {emotion -> score}
 *   - sentiment_entities_json : JSON string [ {text, type, start, end} ]
 *   - sentiment_aspects_json  : JSON string [ {entity_text, polarity, span} ]
 *   - sentiment_raw_response  : String — original LLM payload (audit trail)
 *   - sentiment_model_used    : String
 */
class SentimentAnalyzeStage extends WPartitionStage {

  override def name: String = "sentiment_analyze"

  override def transformPartition(
    rows: Iterator[WRow], args: WArgs, ctx: WebroStageContext
  ): Iterator[WRow] = {
    val textField = args.string(0, "text")
    val model     = args.string(1, "default")
    val maxChars  = args.int(2, 4000)

    rows.map { row =>
      val rawText = row.str(textField).getOrElse("").trim
      if (rawText.isEmpty) {
        ctx.warn(s"[$name] empty text in field '$textField' — emitting neutral defaults")
        emitNeutral(row, model)
      } else {
        val text     = if (rawText.length > maxChars) rawText.substring(0, maxChars) else rawText
        val prompt   = SentimentLlm.buildPrompt(text)
        val response =
          if (model.isEmpty || model == "default") ctx.llm.infer(prompt)
          else ctx.llm.infer(prompt, model)
        val enrichment = SentimentLlm.parseResponse(response)

        row
          .set("sentiment_polarity",      enrichment.polarity)
          .set("sentiment_label",         enrichment.label)
          .set("sentiment_confidence",    enrichment.confidence)
          .set("sentiment_language",      enrichment.language)
          .set("sentiment_emotions_json", JsonMini.stringify(enrichment.emotions))
          .set("sentiment_entities_json", JsonMini.stringify(enrichment.entities.map(e => Map(
            "text"  -> e.text,
            "type"  -> e.entityType,
            "start" -> e.startOffset.map(_.asInstanceOf[AnyRef]).orNull,
            "end"   -> e.endOffset.map(_.asInstanceOf[AnyRef]).orNull
          ))))
          .set("sentiment_aspects_json", JsonMini.stringify(enrichment.aspects.map(a => Map(
            "entity_text" -> a.entityText,
            "polarity"    -> a.polarity,
            "span"        -> a.span
          ))))
          .set("sentiment_raw_response", enrichment.rawJson)
          .set("sentiment_model_used",   model)
      }
    }
  }

  private def emitNeutral(row: WRow, model: String): WRow =
    row
      .set("sentiment_polarity",      0.0)
      .set("sentiment_label",         "neutral")
      .set("sentiment_confidence",    0.0)
      .set("sentiment_language",      "")
      .set("sentiment_emotions_json", JsonMini.stringify(SentimentLlm.Emotions.map(_ -> 0.0).toMap))
      .set("sentiment_entities_json", "[]")
      .set("sentiment_aspects_json",  "[]")
      .set("sentiment_raw_response",  "")
      .set("sentiment_model_used",    model)
}
