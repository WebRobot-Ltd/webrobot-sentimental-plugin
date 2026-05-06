package eu.webrobot.sentiment.cli;

import com.fasterxml.jackson.databind.JsonNode;
import eu.webrobot.cli.sdk.WebroCliContext;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.LinkedHashMap;
import java.util.Map;

@Command(
    name = "distribution",
    description = "Label distribution (positive/negative/neutral counts)."
)
public class DistributionCommand implements Runnable {
    static WebroCliContext context;

    @Option(names = {"--source-type"}) String sourceType;
    @Option(names = {"--from"})        String from;
    @Option(names = {"--to"})          String to;

    @Override
    public void run() {
        Map<String, Object> q = new LinkedHashMap<>();
        if (sourceType != null) q.put("source_type", sourceType);
        if (from != null)       q.put("from",        from);
        if (to != null)         q.put("to",          to);

        JsonNode resp = context.api().get("/webrobot/api/sentiment/distribution", q);
        context.output().json(resp);
    }
}
