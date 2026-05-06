package eu.webrobot.sentiment.cli;

import com.fasterxml.jackson.databind.JsonNode;
import eu.webrobot.cli.sdk.WebroCliContext;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.LinkedHashMap;
import java.util.Map;

@Command(
    name = "compare",
    description = "Compare sentiment across multiple entities over a time window."
)
public class CompareCommand implements Runnable {
    static WebroCliContext context;

    @Option(names = {"--entities"}, description = "Comma-separated entity texts", required = true)
    String entities;

    @Option(names = {"--from"})   String from;
    @Option(names = {"--to"})     String to;
    @Option(names = {"--bucket"}, defaultValue = "day") String bucket;

    @Override
    public void run() {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("entities", entities);
        if (from != null) q.put("from", from);
        if (to != null)   q.put("to",   to);
        q.put("bucket", bucket);

        JsonNode resp = context.api().get("/webrobot/api/sentiment/compare", q);
        context.output().json(resp);
    }
}
