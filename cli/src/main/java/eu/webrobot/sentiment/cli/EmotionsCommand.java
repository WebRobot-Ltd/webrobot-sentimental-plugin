package eu.webrobot.sentiment.cli;

import com.fasterxml.jackson.databind.JsonNode;
import eu.webrobot.cli.sdk.WebroCliContext;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.LinkedHashMap;
import java.util.Map;

@Command(
    name = "emotions",
    description = "Plutchik 8-emotion vector aggregated over the period."
)
public class EmotionsCommand implements Runnable {
    static WebroCliContext context;

    @Option(names = {"--entity"},      description = "Optional entity-text filter (ILIKE)")
    String entityText;

    @Option(names = {"--entity-type"}, description = "Optional entity_type filter (PERSON/ORG/BRAND/...)")
    String entityType;

    @Option(names = {"--from"}) String from;
    @Option(names = {"--to"})   String to;

    @Override
    public void run() {
        Map<String, Object> q = new LinkedHashMap<>();
        if (entityText != null) q.put("entity_text", entityText);
        if (entityType != null) q.put("entity_type", entityType);
        if (from != null)       q.put("from",        from);
        if (to != null)         q.put("to",          to);

        JsonNode resp = context.api().get("/webrobot/api/sentiment/emotions", q);
        context.output().json(resp);
    }
}
