package eu.webrobot.sentiment.stages

import eu.webrobot.plugin.sdk.{WArgs, WRow, WFilterStage}
import eu.webrobot.sentiment.{JsonMini, SentimentLlm}

/**
 * Filters rows post-analysis by:
 *  - sentiment_label
 *  - polarity range
 *  - dominant emotion (the one with the highest score in sentiment_emotions_json)
 *  - presence of an entity (case-insensitive match in sentiment_entities_json)
 */
class SentimentFilterStage extends WFilterStage {

  override def name: String = "sentiment_filter"

  override def include(row: WRow, args: WArgs): Boolean = {
    val labelFilter      = args.string(0, "").trim
    val minPolarity      = args.double(1, -1.0)
    val maxPolarity      = args.double(2, 1.0)
    val dominantEmotion  = args.string(3, "").trim.toLowerCase
    val containsEntity   = args.string(4, "").trim.toLowerCase

    val polarity = row.double("sentiment_polarity").getOrElse(0.0)
    if (polarity < minPolarity || polarity > maxPolarity) return false

    if (labelFilter.nonEmpty && !row.str("sentiment_label").exists(_ == labelFilter)) return false

    if (dominantEmotion.nonEmpty) {
      val map = parseScoreMap(row.str("sentiment_emotions_json").getOrElse("{}"))
      if (map.isEmpty) return false
      val (top, _) = map.maxBy(_._2)
      if (top != dominantEmotion) return false
    }

    if (containsEntity.nonEmpty) {
      val entitiesJson = row.str("sentiment_entities_json").getOrElse("[]")
      val found = JsonMini.parse(entitiesJson) match {
        case arr: Seq[Any] @unchecked => arr.exists {
          case m: Map[String, Any] @unchecked => m.getOrElse("text", "").toString.toLowerCase.contains(containsEntity)
          case _ => false
        }
        case _ => false
      }
      if (!found) return false
    }

    true
  }

  private def parseScoreMap(s: String): Map[String, Double] = JsonMini.parse(s) match {
    case m: Map[String, Any] @unchecked => m.collect {
      case (k, v: Double) => k -> v
      case (k, v: Long)   => k -> v.toDouble
      case (k, v: Int)    => k -> v.toDouble
    }
    case _ => Map.empty
  }
}
