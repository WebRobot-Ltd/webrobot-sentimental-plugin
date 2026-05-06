package eu.webrobot.sentiment.cli;

import com.fasterxml.jackson.databind.JsonNode;
import eu.webrobot.cli.sdk.WebroCliContext;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.HashMap;
import java.util.Map;

@Command(
    name = "analyze",
    description = "Analyze the sentiment of a single text on demand."
)
public class AnalyzeCommand implements Runnable {
    static WebroCliContext context;

    @Parameters(paramLabel = "TEXT", description = "Text to analyze")
    String text;

    @Option(names = {"--source-type"}, description = "Source category", defaultValue = "other")
    String sourceType;

    @Option(names = {"--save"}, description = "Persist the result", defaultValue = "false")
    boolean save;

    @Option(names = {"--entity-id"}, description = "Entity id (when saving)")
    String entityId;

    @Option(names = {"--entity-type"}, description = "Entity type (when saving)")
    String entityType;

    @Override
    public void run() {
        Map<String, Object> body = new HashMap<>();
        body.put("text", text);
        body.put("source_type", sourceType);
        body.put("save", save);
        if (entityId   != null) body.put("entity_id",   entityId);
        if (entityType != null) body.put("entity_type", entityType);

        JsonNode resp = context.api().post("/webrobot/api/sentiment/analyze", body);
        context.output().json(resp);
    }
}
