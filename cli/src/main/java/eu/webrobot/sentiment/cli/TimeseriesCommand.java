package eu.webrobot.sentiment.cli;

import com.fasterxml.jackson.databind.JsonNode;
import eu.webrobot.cli.sdk.WebroCliContext;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.LinkedHashMap;
import java.util.Map;

@Command(
    name = "timeseries",
    description = "Sentiment time series for the org (or filtered by source_type)."
)
public class TimeseriesCommand implements Runnable {
    static WebroCliContext context;

    @Option(names = {"--source-type"}, description = "Filter by source_type (forum/review/news/...)")
    String sourceType;

    @Option(names = {"--from"}, description = "Inclusive lower bound on published_at (ISO date)")
    String from;

    @Option(names = {"--to"}, description = "Exclusive upper bound on published_at (ISO date)")
    String to;

    @Option(names = {"--bucket"}, description = "Aggregation bucket (hour|day|week|month)", defaultValue = "day")
    String bucket;

    @Override
    public void run() {
        Map<String, Object> q = new LinkedHashMap<>();
        if (sourceType != null) q.put("source_type", sourceType);
        if (from != null)       q.put("from",        from);
        if (to != null)         q.put("to",          to);
        q.put("bucket", bucket);

        JsonNode resp = context.api().get("/webrobot/api/sentiment/timeseries", q);
        // The endpoint returns {bucket, series: [...]}. Render the rows as a table.
        context.output().table(resp.get("series"));
    }
}
