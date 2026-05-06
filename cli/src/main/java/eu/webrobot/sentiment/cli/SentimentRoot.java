package eu.webrobot.sentiment.cli;

import eu.webrobot.cli.sdk.WebroCliContext;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(
    name = "sentiment",
    description = "Sentiment analysis vertical: time series, distributions, top entities, comparisons.",
    subcommands = {
        AnalyzeCommand.class,
        TimeseriesCommand.class,
        DistributionCommand.class,
        EmotionsCommand.class,
        TopEntitiesCommand.class,
        CompareCommand.class
    }
)
public class SentimentRoot implements Runnable {
    static WebroCliContext context;

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }
}
