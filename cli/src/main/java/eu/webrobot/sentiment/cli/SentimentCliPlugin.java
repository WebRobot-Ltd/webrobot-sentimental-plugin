package eu.webrobot.sentiment.cli;

import eu.webrobot.cli.sdk.WebroCliContext;
import eu.webrobot.cli.sdk.WebroCliPlugin;

import java.util.Arrays;
import java.util.List;

/**
 * Registers the `webrobot sentiment` subtree with the WebRobot CLI host.
 *
 * Discovered automatically when this JAR sits in {@code ~/.webrobot/plugins/}
 * via {@code META-INF/services/eu.webrobot.cli.sdk.WebroCliPlugin}.
 */
public class SentimentCliPlugin implements WebroCliPlugin {

    @Override public String pluginId()    { return "sentiment"; }

    @Override public String description() { return "Sentiment analysis vertical commands"; }

    @Override
    public List<Class<?>> commands() {
        return Arrays.asList(SentimentRoot.class);
    }

    @Override
    public void init(WebroCliContext ctx) {
        // Stash the context so picocli @Command classes can use it.
        // Static is acceptable here — the CLI is a single-shot process.
        SentimentRoot.context        = ctx;
        AnalyzeCommand.context       = ctx;
        TimeseriesCommand.context    = ctx;
        DistributionCommand.context  = ctx;
        EmotionsCommand.context      = ctx;
        TopEntitiesCommand.context   = ctx;
        CompareCommand.context       = ctx;
    }
}
