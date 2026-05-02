package eu.webrobot.sentiment;

import eu.webrobot.plugin.jersey.WebroPlugin;
import eu.webrobot.plugin.jersey.WebroPluginContext;
import eu.webrobot.api.security.OrganizationContextHelper;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.*;
import javax.ws.rs.core.*;
import java.util.*;

@Path("/webrobot/api/sentiment")
@Produces(MediaType.APPLICATION_JSON)
public class SentimentPlugin extends WebroPlugin {

    private WebroPluginContext ctx;

    @Override
    public String pluginId() {
        return "sentimental-plugin";
    }

    @Override
    public void bootstrap(WebroPluginContext context) {
        this.ctx = context;
        System.out.println("[sentimental-plugin] bootstrapped on " + context.buildType());
    }

    /**
     * On-demand single text analysis.
     * POST /webrobot/api/sentiment/analyze
     * Body: { "text": "...", "entity_id": "...", "entity_type": "product", "save": true }
     */
    @POST
    @Path("/analyze")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response analyze(Map<String, Object> body, @Context HttpServletRequest req) {
        String orgId = OrganizationContextHelper.getOrganizationId(req);

        String text = String.valueOf(body.getOrDefault("text", "")).trim();
        if (text.isEmpty()) {
            return Response.status(400).entity(Map.of("error", "Missing 'text' field")).build();
        }

        if (!ctx.llm().isAvailable()) {
            return Response.status(503).entity(Map.of("error", "No LLM provider configured")).build();
        }

        String prompt =
            "Analyze the sentiment of the following text. Reply with a JSON object only, no explanation.\n" +
            "Format: {\"label\": \"positive\"|\"negative\"|\"neutral\", \"score\": <float 0.0-1.0>}\n\n" +
            "Text: " + text;

        String llmResponse = ctx.llm().infer(prompt);
        Map<String, Object> parsed = parseLlmResponse(llmResponse);

        boolean save = Boolean.parseBoolean(String.valueOf(body.getOrDefault("save", "false")));
        String entityId   = String.valueOf(body.getOrDefault("entity_id", ""));
        String entityType = String.valueOf(body.getOrDefault("entity_type", ""));

        if (save && !entityId.isEmpty() && !entityType.isEmpty()) {
            ctx.db().execute(
                "INSERT INTO sentiment_results (org_id, entity_id, entity_type, text_snippet, label, score, analyzed_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, NOW()) " +
                "ON CONFLICT (org_id, entity_id, entity_type) " +
                "DO UPDATE SET label = EXCLUDED.label, score = EXCLUDED.score, analyzed_at = NOW()",
                List.of(orgId, entityId, entityType, text.substring(0, Math.min(500, text.length())),
                        parsed.get("label"), parsed.get("score"))
            );
            parsed.put("saved", true);
        }

        return Response.ok(parsed).build();
    }

    /**
     * List stored sentiment results for the org.
     * GET /webrobot/api/sentiment/results?entity_type=product&label=positive&limit=100
     */
    @GET
    @Path("/results")
    public Response results(
            @QueryParam("entity_type") String entityType,
            @QueryParam("label") String label,
            @QueryParam("limit") @DefaultValue("100") int limit,
            @Context HttpServletRequest req) {

        String orgId = OrganizationContextHelper.getOrganizationId(req);

        List<Map<String, Object>> rows;
        if (label != null && !label.isEmpty()) {
            rows = ctx.db().query(
                "SELECT * FROM sentiment_results WHERE org_id = ? AND entity_type = ? AND label = ? ORDER BY analyzed_at DESC LIMIT ?",
                List.of(orgId, entityType != null ? entityType : "", label, limit)
            );
        } else if (entityType != null && !entityType.isEmpty()) {
            rows = ctx.db().query(
                "SELECT * FROM sentiment_results WHERE org_id = ? AND entity_type = ? ORDER BY analyzed_at DESC LIMIT ?",
                List.of(orgId, entityType, limit)
            );
        } else {
            rows = ctx.db().query(
                "SELECT * FROM sentiment_results WHERE org_id = ? ORDER BY analyzed_at DESC LIMIT ?",
                List.of(orgId, limit)
            );
        }

        return Response.ok(Map.of("results", rows, "count", rows.size())).build();
    }

    /**
     * Label distribution summary for an entity type.
     * GET /webrobot/api/sentiment/summary/{entityType}
     */
    @GET
    @Path("/summary/{entityType}")
    public Response summary(@PathParam("entityType") String entityType, @Context HttpServletRequest req) {
        String orgId = OrganizationContextHelper.getOrganizationId(req);

        List<Map<String, Object>> distribution = ctx.db().query(
            "SELECT label, COUNT(*) as count, ROUND(AVG(score)::numeric, 4) as avg_score " +
            "FROM sentiment_results WHERE org_id = ? AND entity_type = ? GROUP BY label",
            List.of(orgId, entityType)
        );

        List<Map<String, Object>> latest = ctx.db().query(
            "SELECT * FROM sentiment_results WHERE org_id = ? AND entity_type = ? ORDER BY analyzed_at DESC LIMIT 5",
            List.of(orgId, entityType)
        );

        return Response.ok(Map.of(
            "entity_type", entityType,
            "distribution", distribution,
            "latest", latest
        )).build();
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private Map<String, Object> parseLlmResponse(String response) {
        Map<String, Object> result = new HashMap<>();
        try {
            String label = "neutral";
            double score = 0.5;
            java.util.regex.Matcher lm = java.util.regex.Pattern
                .compile("\"label\"\\s*:\\s*\"(positive|negative|neutral)\"")
                .matcher(response);
            if (lm.find()) label = lm.group(1);
            java.util.regex.Matcher sm = java.util.regex.Pattern
                .compile("\"score\"\\s*:\\s*([0-9.]+)")
                .matcher(response);
            if (sm.find()) score = Double.parseDouble(sm.group(1));
            result.put("label", label);
            result.put("score", score);
        } catch (Exception e) {
            result.put("label", "neutral");
            result.put("score", 0.5);
        }
        return result;
    }
}
