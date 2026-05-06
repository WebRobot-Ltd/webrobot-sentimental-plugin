package eu.webrobot.sentiment.cli;

import com.fasterxml.jackson.databind.JsonNode;
import eu.webrobot.cli.sdk.WebroCliContext;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.LinkedHashMap;
import java.util.Map;

@Command(
    name = "top-entities",
    description = "Top mentioned entities ranked by volume + average polarity."
)
public class TopEntitiesCommand implements Runnable {
    static WebroCliContext context;

    @Option(names = {"--type"},  description = "PERSON/ORG/BRAND/PRODUCT/LOC/EVENT/TOPIC/OTHER")
    String entityType;

    @Option(names = {"--from"})  String from;
    @Option(names = {"--to"})    String to;
    @Option(names = {"--limit"}, defaultValue = "20") int limit;

    @Override
    public void run() {
        Map<String, Object> q = new LinkedHashMap<>();
        if (entityType != null) q.put("type", entityType);
        if (from != null)       q.put("from", from);
        if (to != null)         q.put("to",   to);
        q.put("limit", limit);

        JsonNode resp = context.api().get("/webrobot/api/sentiment/entities/top", q);
        context.output().table(resp.get("entities"));
    }
}
