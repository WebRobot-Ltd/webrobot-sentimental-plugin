package eu.webrobot.sentiment

import scala.util.Try

/**
 * Builds the JSON-mode LLM prompt and parses the response.
 * Output schema:
 * {
 *   "polarity":   -1.0..+1.0,
 *   "label":      "positive"|"negative"|"neutral",
 *   "confidence": 0.0..1.0,
 *   "language":   "en"|"it"|...,
 *   "emotions":   { joy, trust, fear, surprise, sadness, disgust, anger, anticipation } each 0.0..1.0,
 *   "entities":   [ { "text": "...", "type": "PERSON|ORG|BRAND|PRODUCT|LOC|EVENT|TOPIC|OTHER", "start": 0, "end": 0 } ],
 *   "aspects":    [ { "entity_text": "...", "polarity": -1.0..+1.0, "span": "..." } ]
 * }
 */
object SentimentLlm {

  val Emotions: Seq[String] =
    Seq("joy", "trust", "fear", "surprise", "sadness", "disgust", "anger", "anticipation")

  val EntityTypes: Seq[String] =
    Seq("PERSON", "ORG", "BRAND", "PRODUCT", "LOC", "EVENT", "TOPIC", "OTHER")

  def buildPrompt(text: String): String =
    s"""You are a sentiment analysis engine. Analyze the text below and return ONLY a JSON object — no preface, no explanation, no code fences.
       |
       |Schema (every field is required):
       |{
       |  "polarity": <float -1.0..+1.0, where -1 is strongly negative, 0 is neutral, +1 is strongly positive>,
       |  "label": "positive" | "negative" | "neutral",
       |  "confidence": <float 0.0..1.0, your confidence in the polarity score>,
       |  "language": <ISO-639-1 code: "en", "it", "es", "fr", "de", ...>,
       |  "emotions": {
       |    "joy": 0.0..1.0, "trust": 0.0..1.0, "fear": 0.0..1.0, "surprise": 0.0..1.0,
       |    "sadness": 0.0..1.0, "disgust": 0.0..1.0, "anger": 0.0..1.0, "anticipation": 0.0..1.0
       |  },
       |  "entities": [
       |    { "text": "<exact span as in text>", "type": "PERSON|ORG|BRAND|PRODUCT|LOC|EVENT|TOPIC|OTHER", "start": <int char offset>, "end": <int char offset> }
       |  ],
       |  "aspects": [
       |    { "entity_text": "<entity from entities[]>", "polarity": -1.0..+1.0, "span": "<phrase from text carrying this sentiment>" }
       |  ]
       |}
       |
       |Rules:
       |- entities[] should include all named entities. Use BRAND for commercial brands, PRODUCT for product names, TOPIC for high-level subjects.
       |- aspects[] should contain per-entity sentiment when an entity is discussed with a specific tone.
       |  If the document is generic (no per-entity sentiment), aspects[] can be empty.
       |- All floats must be valid JSON numbers (no NaN, no Infinity).
       |- Return ONLY the JSON. No markdown, no commentary.
       |
       |TEXT:
       |\"\"\"
       |$text
       |\"\"\"""".stripMargin

  /**
   * Parses the LLM JSON response into a normalized Scala structure.
   * Tolerant: missing fields fall back to safe defaults; malformed input returns a neutral default.
   */
  def parseResponse(raw: String): SentimentEnrichment = {
    val cleaned = stripFences(raw)
    Try(parseJson(cleaned)).toOption match {
      case Some(m) =>
        val polarity   = numField(m, "polarity").map(clamp(_, -1.0, 1.0)).getOrElse(0.0)
        val label      = strField(m, "label").map(_.toLowerCase).filter(Set("positive", "negative", "neutral")).getOrElse(deriveLabel(polarity))
        val confidence = numField(m, "confidence").map(clamp(_, 0.0, 1.0)).getOrElse(0.5)
        val language   = strField(m, "language").getOrElse("")

        val emotionsMap = m.get("emotions") match {
          case Some(em: Map[String, Any] @unchecked) =>
            Emotions.map { e =>
              e -> numField(em, e).map(clamp(_, 0.0, 1.0)).getOrElse(0.0)
            }.toMap
          case _ => Emotions.map(_ -> 0.0).toMap
        }

        val entities = m.get("entities") match {
          case Some(arr: Seq[Any] @unchecked) => arr.flatMap(parseEntity)
          case _ => Seq.empty
        }

        val aspects = m.get("aspects") match {
          case Some(arr: Seq[Any] @unchecked) => arr.flatMap(parseAspect)
          case _ => Seq.empty
        }

        SentimentEnrichment(polarity, label, confidence, language, emotionsMap, entities, aspects, raw)

      case None =>
        SentimentEnrichment(0.0, "neutral", 0.0, "", Emotions.map(_ -> 0.0).toMap, Seq.empty, Seq.empty, raw)
    }
  }

  private def parseEntity(any: Any): Option[Entity] = any match {
    case m: Map[String, Any] @unchecked =>
      val text = strField(m, "text").getOrElse("").trim
      val tpe  = strField(m, "type").map(_.toUpperCase).filter(EntityTypes.toSet).getOrElse("OTHER")
      val s    = numField(m, "start").map(_.toInt)
      val e    = numField(m, "end").map(_.toInt)
      if (text.nonEmpty) Some(Entity(text, tpe, s, e)) else None
    case _ => None
  }

  private def parseAspect(any: Any): Option[Aspect] = any match {
    case m: Map[String, Any] @unchecked =>
      val entity = strField(m, "entity_text").orElse(strField(m, "entity")).getOrElse("").trim
      val pol    = numField(m, "polarity").map(clamp(_, -1.0, 1.0)).getOrElse(0.0)
      val span   = strField(m, "span").getOrElse("")
      if (entity.nonEmpty) Some(Aspect(entity, pol, span)) else None
    case _ => None
  }

  private def deriveLabel(polarity: Double): String =
    if (polarity > 0.15) "positive" else if (polarity < -0.15) "negative" else "neutral"

  private def clamp(d: Double, lo: Double, hi: Double): Double = math.max(lo, math.min(hi, d))

  private def strField(m: Map[String, Any], k: String): Option[String] = m.get(k).flatMap {
    case s: String => Some(s)
    case other     => Option(other).map(_.toString)
  }
  private def numField(m: Map[String, Any], k: String): Option[Double] = m.get(k).flatMap {
    case d: Double => Some(d)
    case i: Int    => Some(i.toDouble)
    case l: Long   => Some(l.toDouble)
    case s: String => Try(s.toDouble).toOption
    case _         => None
  }

  private def stripFences(s: String): String = {
    val t = s.trim
    if (t.startsWith("```")) {
      val noOpen = t.replaceFirst("""^```[a-zA-Z]*\s*""", "")
      noOpen.replaceFirst("""\s*```\s*$""", "")
    } else t
  }

  // Minimal JSON parser using javax.json (provided by platform) — fall back to scala.util.parsing if needed.
  // Returns nested Map[String, Any] / Seq[Any] / String / Double / Boolean / null
  private def parseJson(s: String): Map[String, Any] = {
    val v = JsonMini.parse(s)
    v match {
      case m: Map[String, Any] @unchecked => m
      case _ => Map.empty
    }
  }
}

// ── Data model ──────────────────────────────────────────────────────────────

final case class Entity(text: String, entityType: String, startOffset: Option[Int], endOffset: Option[Int])

final case class Aspect(entityText: String, polarity: Double, span: String)

final case class SentimentEnrichment(
  polarity:   Double,
  label:      String,
  confidence: Double,
  language:   String,
  emotions:   Map[String, Double],
  entities:   Seq[Entity],
  aspects:    Seq[Aspect],
  rawJson:    String
)
