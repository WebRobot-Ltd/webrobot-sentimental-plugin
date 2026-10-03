package eu.webrobot.sentiment;

import eu.webrobot.plugin.jersey.OrgContext;
import eu.webrobot.plugin.jersey.OrgScoped;
import eu.webrobot.plugin.jersey.WebroPlugin;
import eu.webrobot.plugin.jersey.WebroPluginContext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.*;
import javax.ws.rs.core.*;
import java.util.*;

/**
 * REST API for the sentimental plugin.
 *
 * All endpoints are scoped by org_id resolved from the JWT (via {@link OrgContext}).
 * The whole resource is annotated {@link OrgScoped} — anonymous requests fail 401 at the
 * filter, before any handler runs.
 *
 * Response shapes are chart-ready: time-series → array of {ts, ...}; distributions →
 * label→count map; emotions → emotion→score map (radar). Designed for both the dashboard
 * UI and AI-agent (Claude Code MCP) consumption.
 */
@Path("/webrobot/api/sentiment")
@Produces(MediaType.APPLICATION_JSON)
@OrgScoped
public class SentimentPlugin extends WebroPlugin {

    private WebroPluginContext ctx;

    @Override public String pluginId() { return "sentimental-plugin"; }

    @Override
    public void bootstrap(WebroPluginContext context) {
        this.ctx = context;
        System.out.println("[sentimental-plugin] bootstrapped on " + context.buildType());
    }

    // ── Bootstrap (per-org template clone) ────────────────────────────────────
    //
    // Creates a Project + 2 Agents for the calling organization, each with a
    // pre-defined pipeline YAML embedded below. Same template-cloning shape
    // as price-comparison.plugin / real-estate.plugin: the org gets its own
    // independent copies of the pipelines, customizable per-tenant without
    // touching the other tenants.
    //
    // Agents created:
    //   - Sentiment-forum-monitor-org-N         (scrape forum threads → sentiment)
    //   - Sentiment-review-aggregator-org-N     (scrape product reviews → sentiment)
    //
    // Idempotent: re-running the bootstrap finds existing Project/Agent by name
    // and refreshes the embedded pipeline YAML so plugin upgrades propagate.

    @POST
    @Path("/bootstrap")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response bootstrap(@Context HttpServletRequest req) {
        try {
            String orgId = ctx.orgContext(req).organizationId();
            Map<String, Object> projectRow   = ensureProject(orgId);
            long projectId                   = ((Number) projectRow.get("id")).longValue();
            long forumAgentId   = ensureAgent(orgId, "-forum-monitor",      FORUM_MONITORING_PIPELINE_YAML);
            long reviewAgentId  = ensureAgent(orgId, "-review-aggregator",  REVIEW_AGGREGATOR_PIPELINE_YAML);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("organization_id",   orgId);
            result.put("project_id",        projectId);
            result.put("forum_agent_id",    forumAgentId);
            result.put("review_agent_id",   reviewAgentId);
            result.put("status",            "ready");
            result.put("note",              "Pipelines persisted; tune the YAML per-agent if needed, or " +
                                            "register CronJob via cloud-scheduler plugin.");
            return Response.ok(result).build();
        } catch (Exception e) {
            return Response.serverError().entity(Map.of("error", e.getMessage())).build();
        }
    }

    private static final String PROJECT_NAME_PREFIX = "Sentiment";

    private Map<String, Object> ensureProject(String orgId) {
        String name = PROJECT_NAME_PREFIX + "-org-" + orgId;
        List<Map<String, Object>> existing = ctx.db().query(
            "SELECT id FROM projects WHERE name = ? AND organization_id = ?",
            Arrays.asList(name, orgId));
        if (!existing.isEmpty()) return existing.get(0);
        long id = ctx.db().insertReturning(
            "INSERT INTO projects (name, description, organization_id, enabled, created_at, updated_at) " +
            "VALUES (?, ?, ?, TRUE, NOW(), NOW()) RETURNING id",
            Arrays.asList(name, "Sentiment monitoring — auto-created by sentimental-plugin", orgId));
        return Map.of("id", id, "name", name, "organization_id", orgId);
    }

    private long ensureAgent(String orgId, String suffix, String pipelineYaml) {
        String name = PROJECT_NAME_PREFIX + suffix + "-org-" + orgId;
        List<Map<String, Object>> existing = ctx.db().query(
            "SELECT id FROM agents WHERE name = ? AND organization_id = ?",
            Arrays.asList(name, orgId));
        if (!existing.isEmpty()) {
            long id = ((Number) existing.get(0).get("id")).longValue();
            // Refresh pipeline_yaml in case the embedded template changed in a plugin upgrade
            ctx.db().execute(
                "UPDATE agents SET pipeline_yaml = ?, updated_at = NOW() WHERE id = ?",
                Arrays.asList(pipelineYaml, id));
            return id;
        }
        return ctx.db().insertReturning(
            "INSERT INTO agents (name, description, organization_id, pipeline_yaml, enabled, " +
            "                    type, execution_mode, created_at, updated_at) " +
            "VALUES (?, ?, ?, ?, TRUE, 'pipeline', 'spark', NOW(), NOW()) RETURNING id",
            Arrays.asList(name,
                          "Sentiment pipeline (" + suffix.replaceFirst("^-", "") + ") — auto-created by sentimental-plugin",
                          orgId, pipelineYaml));
    }

    // ── Embedded pipeline templates ───────────────────────────────────────────

    /** Phase 1: discover forum/community threads, scrape posts, enrich each with sentiment_analyze + sentiment_save. */
    private static final String FORUM_MONITORING_PIPELINE_YAML =
        "pipeline:\n" +
        "  # 1. Discover forum threads via search engine (configure ${FORUM_QUERY} at run time)\n" +
        "  - stage: searchEngine\n" +
        "    args:\n" +
        "      - provider: \"google\"\n" +
        "        query: \"${FORUM_QUERY}\"\n" +
        "        num_results: 30\n" +
        "        enrich: false\n" +
        "  # 2. Visit each discovered thread\n" +
        "  - stage: visit\n" +
        "    args:\n" +
        "      - \"$result_link\"\n" +
        "  # 3. Flatten thread into individual post rows\n" +
        "  - stage: comment_extractor\n" +
        "    args: []\n" +
        "  # 4. LLM enrichment per post → polarity/emotions/entities/aspects\n" +
        "  - stage: sentiment_analyze\n" +
        "    args:\n" +
        "      - text_field: \"text\"\n" +
        "  # 5. Atomic write to sentiment_documents + child tables\n" +
        "  - stage: sentiment_save\n" +
        "    args:\n" +
        "      - source_type: \"forum\"\n" +
        "        text_field: \"text\"\n" +
        "        published_at_field: \"post_timestamp\"\n" +
        "        source_url_field: \"post_url\"\n" +
        "        author_field: \"author\"\n" +
        "        external_id_field: \"post_id\"\n" +
        "output:\n" +
        "  format: \"parquet\"\n" +
        "  path: \"${OUTPUT_PARQUET_PATH}\"\n" +
        "  mode: \"overwrite\"\n";

    /** Phase 1 variant: scrape product reviews from e-commerce listings (single product URL → per-review rows). */
    private static final String REVIEW_AGGREGATOR_PIPELINE_YAML =
        "pipeline:\n" +
        "  # 1. Trigger CSV carrying the product review-page URLs to monitor\n" +
        "  - stage: load_csv\n" +
        "    args:\n" +
        "      - path: \"${INPUT_CSV_PATH}\"\n" +
        "        header: \"true\"\n" +
        "  # 2. Visit each product review page (paginated)\n" +
        "  - stage: visit\n" +
        "    args:\n" +
        "      - \"$review_url\"\n" +
        "  # 3. Extract structured review rows (title, body, rating, author, date)\n" +
        "  - stage: iextract\n" +
        "    args:\n" +
        "      - selector: \"body\"\n" +
        "        method: \"code\"\n" +
        "      - \"Extract from this product reviews page each review as a row with: " +
              "review body text (field: text), rating 1-5 if visible (field: rating), " +
              "author / username (field: author), review date as ISO-8601 if visible " +
              "(field: published_at), review URL or anchor (field: source_url). " +
              "Return ALL reviews on the page, one row each. Preserve any input fields.\"\n" +
        "      - \"\"\n" +
        "  # 4. LLM sentiment enrichment per review\n" +
        "  - stage: sentiment_analyze\n" +
        "    args:\n" +
        "      - text_field: \"text\"\n" +
        "  # 5. Atomic persist\n" +
        "  - stage: sentiment_save\n" +
        "    args:\n" +
        "      - source_type: \"review\"\n" +
        "        text_field: \"text\"\n" +
        "        published_at_field: \"published_at\"\n" +
        "        source_url_field: \"source_url\"\n" +
        "        author_field: \"author\"\n" +
        "output:\n" +
        "  format: \"parquet\"\n" +
        "  path: \"${OUTPUT_PARQUET_PATH}\"\n" +
        "  mode: \"overwrite\"\n";

    // ── On-demand single-text analysis ────────────────────────────────────────

    @POST
    @Path("/analyze")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response analyze(Map<String, Object> body, @Context HttpServletRequest req) {
        String orgId = ctx.orgContext(req).organizationId();
        String text  = String.valueOf(body.getOrDefault("text", "")).trim();
        if (text.isEmpty()) return bad("Missing 'text' field");
        if (!ctx.llm().isAvailable())
            return Response.status(503).entity(Map.of("error", "No LLM provider configured")).build();

        String response = ctx.llm().infer(SentimentLlmPrompt.build(text));
        Map<String, Object> parsed = SentimentLlmPrompt.parse(response);

        if (parseBool(body.get("save"), false)) {
            String sourceType = String.valueOf(body.getOrDefault("source_type", "other"));
            persistFromApi(orgId, sourceType, body, text, parsed, response);
            parsed.put("saved", true);
        }
        return Response.ok(parsed).build();
    }

    // ── Reconcile (close the loop: backfill child enrichment) ─────────────────
    //
    // A document persisted document-level only (e.g. the thin /analyze save path,
    // whose parse() extracts scalars+emotions but NOT entities/aspects, and which
    // writes no child rows) is INVISIBLE to the entity-scoped aggregations
    // (/emotions?entity_text, /entities/top, /cooccurrence, /compare) because those
    // read sentiment_emotions/_entities/_aspects. But every document stores the FULL
    // LLM JSON in raw_response. This reconciler finds documents missing child rows
    // and backfills emotions/entities/aspects by RE-PARSING raw_response — no
    // re-crawl, no re-LLM. Run it BEFORE building aggregates/charts so direct-ingested
    // documents appear in the entity views too. Idempotent.
    @POST
    @Path("/reconcile")
    public Response reconcile(@QueryParam("limit") @DefaultValue("1000") int limit,
                              @Context HttpServletRequest req) {
        String orgId = ctx.orgContext(req).organizationId();
        int cap = (limit > 0 && limit <= 5000) ? limit : 1000;
        List<Map<String, Object>> docs = ctx.db().query(
            "SELECT d.id AS id, d.raw_response AS raw_response FROM sentiment_documents d " +
            "WHERE d.org_id = ? AND d.raw_response IS NOT NULL AND d.raw_response <> '{}' " +
            "  AND NOT EXISTS (SELECT 1 FROM sentiment_emotions e WHERE e.document_id = d.id) " +
            "ORDER BY d.analyzed_at DESC NULLS LAST LIMIT ?",
            Arrays.asList(orgId, cap));
        int reconciled = 0, failed = 0;
        for (Map<String, Object> d : docs) {
            Object idObj = d.get("id");
            long documentId = (idObj instanceof Number) ? ((Number) idObj).longValue()
                    : Long.parseLong(String.valueOf(idObj));
            String raw = d.get("raw_response") == null ? null : String.valueOf(d.get("raw_response"));
            try {
                if (backfillChildren(orgId, documentId, raw)) reconciled++;
            } catch (Exception e) {
                failed++;
                System.out.println("[sentimental-plugin] reconcile doc " + documentId + " failed: " + e);
            }
        }
        return Response.ok(Map.of("scanned", docs.size(), "reconciled", reconciled, "failed", failed)).build();
    }

    /**
     * Backfill emotions/entities/aspects for one document from its stored raw_response (the full LLM
     * JSON). Mirrors SentimentSaveStage's child persistence. DELETE-then-INSERT → idempotent. Returns
     * true when at least one child row was written.
     */
    private boolean backfillChildren(String orgId, long documentId, String raw) throws Exception {
        if (raw == null || raw.isBlank()) return false;
        JsonNode root = MAPPER.readTree(raw);
        // idempotent: clear any existing children first
        ctx.db().execute("DELETE FROM sentiment_emotions WHERE document_id = ?", Arrays.asList(documentId));
        ctx.db().execute("DELETE FROM sentiment_aspects  WHERE document_id = ?", Arrays.asList(documentId));
        ctx.db().execute("DELETE FROM sentiment_entities WHERE document_id = ?", Arrays.asList(documentId));

        boolean wrote = false;
        // emotions — the 8 Plutchik dimensions (same list the prompt/save use)
        JsonNode emo = root.path("emotions");
        for (String e : SentimentLlmPrompt.EMOTIONS) {
            double score = emo.path(e).asDouble(0.0);
            ctx.db().execute(
                "INSERT INTO sentiment_emotions (document_id, org_id, emotion, score) VALUES (?, ?, ?, ?)",
                Arrays.asList(documentId, orgId, e, score));
            wrote = true;
        }
        // entities — capture id by text to link aspects
        Map<String, Long> entityIdByText = new HashMap<>();
        Map<String, String> entityTypeByText = new HashMap<>();
        JsonNode ents = root.path("entities");
        if (ents.isArray()) {
            for (JsonNode en : ents) {
                String text = en.path("text").asText("").trim();
                if (text.isEmpty()) continue;
                String type = en.path("type").asText("OTHER");
                Integer start = en.has("start") && !en.path("start").isNull() ? en.path("start").asInt() : null;
                Integer end   = en.has("end")   && !en.path("end").isNull()   ? en.path("end").asInt()   : null;
                long entId = ctx.db().insertReturning(
                    "INSERT INTO sentiment_entities " +
                    " (document_id, org_id, canonical_id, text, entity_type, start_offset, end_offset) " +
                    "VALUES (?, ?, NULL, ?, ?, ?, ?) RETURNING id",
                    Arrays.asList(documentId, orgId, text, type, start, end));
                entityIdByText.put(text, entId);
                entityTypeByText.put(text, type);
                wrote = true;
            }
        }
        // aspects — link to the entity id by text when present
        JsonNode asp = root.path("aspects");
        if (asp.isArray()) {
            for (JsonNode a : asp) {
                String entityText = a.path("entity_text").asText("").trim();
                if (entityText.isEmpty()) continue;
                Long entityId = entityIdByText.get(entityText);
                double polarity = a.path("polarity").asDouble(0.0);
                String span = a.path("span").asText(null);
                ctx.db().execute(
                    "INSERT INTO sentiment_aspects " +
                    " (document_id, entity_id, org_id, entity_text, entity_type, polarity, span) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    Arrays.asList(documentId, entityId, orgId, entityText,
                        entityTypeByText.getOrDefault(entityText, "OTHER"), polarity,
                        (span == null || span.isEmpty()) ? null : span));
                wrote = true;
            }
        }
        return wrote;
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── Time series ──────────────────────────────────────────────────────────

    @GET
    @Path("/timeseries")
    public Response timeseries(@QueryParam("source_type") String sourceType,
                               @QueryParam("from")        String fromDate,
                               @QueryParam("to")          String toDate,
                               @QueryParam("bucket")      @DefaultValue("day") String bucket,
                               @Context HttpServletRequest req) {
        String orgId = ctx.orgContext(req).organizationId();
        String trunc = sanitizeBucket(bucket);

        StringBuilder sql = new StringBuilder(
            "SELECT date_trunc('" + trunc + "', published_at)::date AS ts, " +
            "       COUNT(*) AS count, AVG(polarity) AS avg_polarity, " +
            "       COUNT(*) FILTER (WHERE label='positive') AS positive, " +
            "       COUNT(*) FILTER (WHERE label='negative') AS negative, " +
            "       COUNT(*) FILTER (WHERE label='neutral')  AS neutral " +
            "FROM sentiment_documents WHERE org_id = ? AND published_at IS NOT NULL "
        );
        List<Object> params = new ArrayList<>();
        params.add(orgId);
        if (notEmpty(sourceType)) { sql.append("AND source_type = ? ");        params.add(sourceType); }
        if (notEmpty(fromDate))   { sql.append("AND published_at >= ?::date "); params.add(fromDate); }
        if (notEmpty(toDate))     { sql.append("AND published_at <  ?::date "); params.add(toDate); }
        sql.append("GROUP BY ts ORDER BY ts ASC");

        return Response.ok(Map.of(
            "series", ctx.db().query(sql.toString(), params),
            "bucket", trunc)).build();
    }

    // ── Label distribution ───────────────────────────────────────────────────

    @GET
    @Path("/distribution")
    public Response distribution(@QueryParam("source_type") String sourceType,
                                 @QueryParam("from")        String fromDate,
                                 @QueryParam("to")          String toDate,
                                 @Context HttpServletRequest req) {
        String orgId = ctx.orgContext(req).organizationId();

        StringBuilder sql = new StringBuilder(
            "SELECT label, COUNT(*) AS count, AVG(polarity) AS avg_polarity " +
            "FROM sentiment_documents WHERE org_id = ? "
        );
        List<Object> params = new ArrayList<>(); params.add(orgId);
        if (notEmpty(sourceType)) { sql.append("AND source_type = ? ");         params.add(sourceType); }
        if (notEmpty(fromDate))   { sql.append("AND published_at >= ?::date "); params.add(fromDate); }
        if (notEmpty(toDate))     { sql.append("AND published_at <  ?::date "); params.add(toDate); }
        sql.append("GROUP BY label");

        Map<String, Object> dist = new LinkedHashMap<>();
        for (Map<String, Object> r : ctx.db().query(sql.toString(), params)) {
            dist.put(String.valueOf(r.get("label")), r.get("count"));
        }
        return Response.ok(Map.of("distribution", dist)).build();
    }

    // ── Emotion radar ────────────────────────────────────────────────────────

    @GET
    @Path("/emotions")
    public Response emotions(@QueryParam("entity_text") String entityText,
                             @QueryParam("entity_type") String entityType,
                             @QueryParam("from")        String fromDate,
                             @QueryParam("to")          String toDate,
                             @Context HttpServletRequest req) {
        String orgId = ctx.orgContext(req).organizationId();

        StringBuilder sql = new StringBuilder(
            "SELECT em.emotion, AVG(em.score) AS avg_score, COUNT(DISTINCT d.id) AS doc_count " +
            "FROM sentiment_documents d JOIN sentiment_emotions em ON em.document_id = d.id "
        );
        List<Object> params = new ArrayList<>(); params.add(orgId);
        if (notEmpty(entityText)) {
            sql.append("JOIN sentiment_entities e ON e.document_id = d.id ");
            sql.append("WHERE d.org_id = ? AND e.text ILIKE ? ");
            params.add(entityText);
        } else {
            sql.append("WHERE d.org_id = ? ");
        }
        if (notEmpty(entityType) && notEmpty(entityText)) {
            sql.append("AND e.entity_type = ? "); params.add(entityType);
        }
        if (notEmpty(fromDate)) { sql.append("AND d.published_at >= ?::date "); params.add(fromDate); }
        if (notEmpty(toDate))   { sql.append("AND d.published_at <  ?::date "); params.add(toDate); }
        sql.append("GROUP BY em.emotion ORDER BY em.emotion");

        Map<String, Object> radar = new LinkedHashMap<>();
        for (Map<String, Object> r : ctx.db().query(sql.toString(), params)) {
            radar.put(String.valueOf(r.get("emotion")), r.get("avg_score"));
        }
        return Response.ok(Map.of("emotions", radar)).build();
    }

    // ── Top entities ─────────────────────────────────────────────────────────

    @GET
    @Path("/entities/top")
    public Response topEntities(@QueryParam("type")  String entityType,
                                @QueryParam("from")  String fromDate,
                                @QueryParam("to")    String toDate,
                                @QueryParam("limit") @DefaultValue("20") int limit,
                                @Context HttpServletRequest req) {
        String orgId = ctx.orgContext(req).organizationId();

        StringBuilder sql = new StringBuilder(
            "SELECT e.text AS entity, e.entity_type AS type, COUNT(*) AS count, " +
            "       AVG(COALESCE(a.polarity, d.polarity)) AS avg_polarity " +
            "FROM sentiment_entities e " +
            "JOIN  sentiment_documents d ON d.id = e.document_id " +
            "LEFT JOIN sentiment_aspects  a ON a.document_id = d.id AND a.entity_id = e.id " +
            "WHERE e.org_id = ? "
        );
        List<Object> params = new ArrayList<>(); params.add(orgId);
        if (notEmpty(entityType)) { sql.append("AND e.entity_type = ? ");         params.add(entityType); }
        if (notEmpty(fromDate))   { sql.append("AND d.published_at >= ?::date "); params.add(fromDate); }
        if (notEmpty(toDate))     { sql.append("AND d.published_at <  ?::date "); params.add(toDate); }
        sql.append("GROUP BY e.text, e.entity_type ORDER BY count DESC LIMIT ?");
        params.add(limit);

        return Response.ok(Map.of("entities", ctx.db().query(sql.toString(), params))).build();
    }

    // ── Compare entities over time ───────────────────────────────────────────

    @GET
    @Path("/compare")
    public Response compare(@QueryParam("entities") String entitiesCsv,
                            @QueryParam("from")     String fromDate,
                            @QueryParam("to")       String toDate,
                            @QueryParam("bucket")   @DefaultValue("day") String bucket,
                            @Context HttpServletRequest req) {
        String orgId = ctx.orgContext(req).organizationId();
        if (!notEmpty(entitiesCsv)) return bad("entities query param required");
        String trunc = sanitizeBucket(bucket);

        List<String> entities = Arrays.stream(entitiesCsv.split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.toList());

        Map<String, List<Map<String, Object>>> series = new LinkedHashMap<>();
        for (String entity : entities) {
            StringBuilder sql = new StringBuilder(
                "SELECT date_trunc('" + trunc + "', d.published_at)::date AS ts, " +
                "       COUNT(*) AS count, AVG(COALESCE(a.polarity, d.polarity)) AS avg_polarity " +
                "FROM sentiment_entities e JOIN sentiment_documents d ON d.id = e.document_id " +
                "LEFT JOIN sentiment_aspects a ON a.document_id = d.id AND a.entity_id = e.id " +
                "WHERE e.org_id = ? AND e.text ILIKE ? AND d.published_at IS NOT NULL "
            );
            List<Object> params = new ArrayList<>(); params.add(orgId); params.add(entity);
            if (notEmpty(fromDate)) { sql.append("AND d.published_at >= ?::date "); params.add(fromDate); }
            if (notEmpty(toDate))   { sql.append("AND d.published_at <  ?::date "); params.add(toDate); }
            sql.append("GROUP BY ts ORDER BY ts ASC");
            series.put(entity, ctx.db().query(sql.toString(), params));
        }
        return Response.ok(Map.of("bucket", trunc, "series", series)).build();
    }

    // ── Co-occurrence ────────────────────────────────────────────────────────

    @GET
    @Path("/cooccurrence")
    public Response cooccurrence(@QueryParam("entity") String entity,
                                 @QueryParam("from")   String fromDate,
                                 @QueryParam("to")     String toDate,
                                 @QueryParam("limit")  @DefaultValue("30") int limit,
                                 @Context HttpServletRequest req) {
        String orgId = ctx.orgContext(req).organizationId();
        if (!notEmpty(entity)) return bad("entity query param required");

        StringBuilder sql = new StringBuilder(
            "SELECT e2.text AS co_entity, e2.entity_type AS type, " +
            "       COUNT(*) AS count, AVG(d.polarity) AS avg_polarity " +
            "FROM sentiment_entities e1 " +
            "JOIN  sentiment_entities e2 ON e1.document_id = e2.document_id AND e1.id <> e2.id " +
            "JOIN  sentiment_documents d ON d.id = e1.document_id " +
            "WHERE e1.org_id = ? AND e1.text ILIKE ? "
        );
        List<Object> params = new ArrayList<>(); params.add(orgId); params.add(entity);
        if (notEmpty(fromDate)) { sql.append("AND d.published_at >= ?::date "); params.add(fromDate); }
        if (notEmpty(toDate))   { sql.append("AND d.published_at <  ?::date "); params.add(toDate); }
        sql.append("GROUP BY e2.text, e2.entity_type ORDER BY count DESC LIMIT ?");
        params.add(limit);

        return Response.ok(Map.of("entity", entity, "cooccurring", ctx.db().query(sql.toString(), params))).build();
    }

    // ── Recent documents (raw drill-down) ────────────────────────────────────

    @GET
    @Path("/documents")
    public Response documents(@QueryParam("source_type") String sourceType,
                              @QueryParam("label")       String label,
                              @QueryParam("entity")      String entity,
                              @QueryParam("from")        String fromDate,
                              @QueryParam("to")          String toDate,
                              @QueryParam("limit")       @DefaultValue("100") int limit,
                              @Context HttpServletRequest req) {
        String orgId = ctx.orgContext(req).organizationId();
        StringBuilder sql = new StringBuilder(
            // analyzed_at is in the ORDER BY, so with SELECT DISTINCT it MUST also be in the select
            // list (Postgres: "for SELECT DISTINCT, ORDER BY expressions must appear in select list").
            // DISTINCT is needed because the optional entities JOIN can duplicate a document row.
            "SELECT DISTINCT d.id, d.source_type, d.source_url, d.author, d.published_at, " +
            "       d.analyzed_at, d.label, d.polarity, d.confidence, d.language, d.text_snippet " +
            "FROM sentiment_documents d "
        );
        List<Object> params = new ArrayList<>();
        if (notEmpty(entity)) {
            sql.append("JOIN sentiment_entities e ON e.document_id = d.id ");
            sql.append("WHERE d.org_id = ? AND e.text ILIKE ? ");
            params.add(orgId); params.add(entity);
        } else {
            sql.append("WHERE d.org_id = ? ");
            params.add(orgId);
        }
        if (notEmpty(sourceType)) { sql.append("AND d.source_type = ? ");        params.add(sourceType); }
        if (notEmpty(label))      { sql.append("AND d.label = ? ");              params.add(label); }
        if (notEmpty(fromDate))   { sql.append("AND d.published_at >= ?::date "); params.add(fromDate); }
        if (notEmpty(toDate))     { sql.append("AND d.published_at <  ?::date "); params.add(toDate); }
        sql.append("ORDER BY d.published_at DESC NULLS LAST, d.analyzed_at DESC LIMIT ?");
        params.add(limit);

        return Response.ok(Map.of("documents", ctx.db().query(sql.toString(), params))).build();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void persistFromApi(String orgId, String sourceType, Map<String, Object> body,
                                String text, Map<String, Object> parsed, String rawResponse) {
        String publishedAt = cleanTimestamp(body.get("published_at"));
        String sourceUrl   = body.get("source_url")   != null ? String.valueOf(body.get("source_url"))   : null;
        String author      = body.get("author")       != null ? String.valueOf(body.get("author"))       : null;
        String externalId  = body.get("external_id")  != null ? String.valueOf(body.get("external_id"))  : null;
        String textHash    = SentimentLlmPrompt.sha256(text);
        // The LLM output goes into a jsonb column. Claude (via the OAuth subscription) often wraps the
        // JSON object in ```json fences or surrounding prose, so the RAW string is not valid JSON and the
        // ?::jsonb cast throws "invalid input syntax for type json". parse() survives that (it is regex-
        // based), but the insert does not — sanitise to the bare, valid JSON object first. The extracted
        // {..} block still carries emotions/entities/aspects, so reconcile keeps working.
        String rawJson = cleanJson(rawResponse, parsed);

        ctx.db().execute(
            "INSERT INTO sentiment_documents " +
            " (org_id, source_type, source_url, author, external_id, " +
            "  published_at, analyzed_at, text_hash, text_snippet, language, " +
            "  label, polarity, confidence, model_used, raw_response) " +
            "VALUES (?, ?, ?, ?, ?, ?::timestamptz, NOW(), ?, ?, ?, ?, ?, ?, ?, ?::jsonb) " +
            "ON CONFLICT (org_id, text_hash, source_type) DO UPDATE SET " +
            "  analyzed_at = NOW(), label = EXCLUDED.label, polarity = EXCLUDED.polarity, " +
            "  confidence = EXCLUDED.confidence, raw_response = EXCLUDED.raw_response",
            Arrays.asList(orgId, sourceType, sourceUrl, author, externalId, publishedAt,
                textHash, text.substring(0, Math.min(1000, text.length())),
                parsed.get("language"), parsed.get("label"), parsed.get("polarity"),
                parsed.get("confidence"), "default", rawJson)
        );
        // Note: emotions/entities/aspects child rows are NOT persisted from this thin API path.
        // Use the ETL pipeline (sentiment_analyze + sentiment_save) for full enrichment.
    }

    /**
     * Reduce an LLM response to a valid JSON object string safe for a {@code ?::jsonb} cast.
     * Strips markdown ``` fences and any prose around the object, then keeps the outermost
     * {@code {..}} block and validates it parses. On any failure, falls back to re-serialising the
     * already-parsed scalars+emotions so the row is still valid JSON (reconcile then has less to
     * backfill, but the insert never throws).
     */
    private static String cleanJson(String raw, Map<String, Object> fallback) {
        try {
            if (raw != null) {
                String s = raw.trim();
                if (s.startsWith("```")) {                     // ```json\n{...}\n```  or  ```\n{...}\n```
                    int nl = s.indexOf('\n');
                    if (nl >= 0) s = s.substring(nl + 1);
                    int fence = s.lastIndexOf("```");
                    if (fence >= 0) s = s.substring(0, fence);
                    s = s.trim();
                }
                int a = s.indexOf('{');
                int b = s.lastIndexOf('}');
                if (a >= 0 && b > a) {
                    String candidate = s.substring(a, b + 1);
                    MAPPER.readTree(candidate);                // throws if not valid JSON
                    return candidate;
                }
            }
        } catch (Exception ignored) { /* fall through to the reconstructed fallback */ }
        try { return MAPPER.writeValueAsString(fallback); } catch (Exception e) { return "{}"; }
    }

    /** Accept only an ISO-ish date / datetime for the {@code ?::timestamptz} cast; anything else → null. */
    private static String cleanTimestamp(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        if (s.isEmpty() || !s.matches("\\d{4}-\\d{2}-\\d{2}([ T].*)?")) return null;
        return s;
    }

    private static String sanitizeBucket(String b) {
        String v = b == null ? "day" : b.toLowerCase();
        if ("hour".equals(v))  return "hour";
        if ("week".equals(v))  return "week";
        if ("month".equals(v)) return "month";
        return "day";
    }

    private static boolean parseBool(Object o, boolean def) {
        if (o == null) return def;
        if (o instanceof Boolean) return (Boolean) o;
        return Boolean.parseBoolean(String.valueOf(o));
    }

    private static boolean notEmpty(String s) { return s != null && !s.isEmpty(); }

    private static Response bad(String msg) {
        return Response.status(400).entity(Map.of("error", msg)).build();
    }
}
