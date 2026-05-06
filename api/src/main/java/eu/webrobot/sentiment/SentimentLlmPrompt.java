package eu.webrobot.sentiment;

import java.security.MessageDigest;
import java.util.*;
import java.util.regex.*;

/**
 * Java twin of the Scala SentimentLlm: builds the JSON-mode prompt and parses the response.
 * Used by the REST API for on-demand single-text analysis (the heavy batch path lives in the ETL plugin).
 */
public final class SentimentLlmPrompt {

    private SentimentLlmPrompt() {}

    public static final List<String> EMOTIONS = List.of(
            "joy", "trust", "fear", "surprise", "sadness", "disgust", "anger", "anticipation");

    public static String build(String text) {
        return "You are a sentiment analysis engine. Analyze the text below and return ONLY a JSON object.\n" +
               "Schema:\n" +
               "{\n" +
               "  \"polarity\": <float -1.0..+1.0>,\n" +
               "  \"label\": \"positive\" | \"negative\" | \"neutral\",\n" +
               "  \"confidence\": <float 0.0..1.0>,\n" +
               "  \"language\": <ISO-639-1>,\n" +
               "  \"emotions\": { \"joy\":..., \"trust\":..., \"fear\":..., \"surprise\":..., \"sadness\":..., \"disgust\":..., \"anger\":..., \"anticipation\":... },\n" +
               "  \"entities\": [ { \"text\":..., \"type\":\"PERSON|ORG|BRAND|PRODUCT|LOC|EVENT|TOPIC|OTHER\", \"start\":..., \"end\":... } ],\n" +
               "  \"aspects\":  [ { \"entity_text\":..., \"polarity\":..., \"span\":... } ]\n" +
               "}\n\n" +
               "Return ONLY the JSON object, no markdown, no commentary.\n\n" +
               "TEXT:\n\"\"\"\n" + text + "\n\"\"\"";
    }

    /**
     * Tolerant best-effort parse — matches the Scala behaviour: missing fields → safe defaults.
     */
    public static Map<String, Object> parse(String response) {
        Map<String, Object> out = new LinkedHashMap<>();
        double polarity   = extractDouble(response, "polarity",   0.0);
        String label      = extractString(response, "label",      deriveLabel(polarity));
        double confidence = extractDouble(response, "confidence", 0.5);
        String language   = extractString(response, "language",   "");

        if (!Set.of("positive", "negative", "neutral").contains(label)) {
            label = deriveLabel(polarity);
        }

        out.put("polarity",   clamp(polarity, -1.0, 1.0));
        out.put("label",      label);
        out.put("confidence", clamp(confidence, 0.0, 1.0));
        out.put("language",   language);

        // Emotion scores
        Map<String, Object> emotions = new LinkedHashMap<>();
        for (String e : EMOTIONS) {
            emotions.put(e, clamp(extractDouble(response, e, 0.0), 0.0, 1.0));
        }
        out.put("emotions", emotions);

        return out;
    }

    public static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    private static double extractDouble(String s, String key, double def) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(-?[0-9.]+)").matcher(s);
        if (m.find()) {
            try { return Double.parseDouble(m.group(1)); } catch (Exception ignored) {}
        }
        return def;
    }

    private static String extractString(String s, String key, String def) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"").matcher(s);
        return m.find() ? m.group(1) : def;
    }

    private static String deriveLabel(double polarity) {
        if (polarity > 0.15)  return "positive";
        if (polarity < -0.15) return "negative";
        return "neutral";
    }

    private static double clamp(double d, double lo, double hi) {
        return Math.max(lo, Math.min(hi, d));
    }
}
