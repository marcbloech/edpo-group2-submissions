package ch.unisg.worldpulse.streams;

import ch.unisg.worldpulse.streams.avro.AlertStats;
import ch.unisg.worldpulse.streams.avro.CorrelatedAlert;
import ch.unisg.worldpulse.streams.avro.CorrelatedAlertSummary;
import ch.unisg.worldpulse.streams.avro.PaymentSummary;
import ch.unisg.worldpulse.streams.avro.UserActivity;
import ch.unisg.worldpulse.streams.avro.UserDashboard;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StoreQueryParameters;
import org.apache.kafka.streams.errors.InvalidStateStoreException;
import org.apache.kafka.streams.state.KeyValueIterator;
import org.apache.kafka.streams.state.QueryableStoreTypes;
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore;
import org.apache.kafka.streams.state.ReadOnlyWindowStore;
import org.apache.kafka.streams.state.WindowStoreIterator;
import org.apache.kafka.streams.kstream.Windowed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * D1-D4: Interactive query REST server for the WorldPulse Analytics Dashboard.
 *
 * Uses the JDK built-in HttpServer (no extra dependencies).
 * Modeled after Lab 13 Part 2 MonitorService.java.
 *
 * Endpoints (D2 — non-windowed):
 *   GET /api/users              → list all entries in "user-dashboard" store
 *   GET /api/users/{userId}     → single UserDashboard record
 *   GET /api/users/{userId}/activity  → UserActivity record
 *   GET /api/users/{userId}/payments  → PaymentSummary record
 *
 * Endpoints (D3 — windowed):
 *   GET /api/alerts/stats/{symbol}?from=<epochMs>&to=<epochMs>  → AlertStats windows
 *   GET /api/signups/stats/{tier}?from=<epochMs>&to=<epochMs>   → signup counts per window
 *   GET /api/logins/stats/{location}?from=<epochMs>&to=<epochMs> → login counts per window
 *   (default window: last 1 hour if from/to omitted)
 *
 * Endpoint (D4 — health):
 *   GET /api/status             → KafkaStreams state + store names + uptime
 *
 * Returns 503 while the analytics topology is not yet in RUNNING state.
 *
 * Ref: Implementation Plan §3 (App B, interactive queries), §10 step 12
 */
public class QueryService {

    private static final Logger logger = LoggerFactory.getLogger(QueryService.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final KafkaStreams analyticsStreams;
    private final CorrelatedAlertBuffer correlatedAlertBuffer;
    private final SuppressedSignupBuffer signupBuffer;
    private final SuppressedSummaryBuffer summaryBuffer;
    private final int port;
    private final long startedAt = System.currentTimeMillis();

    private HttpServer server;

    public QueryService(KafkaStreams analyticsStreams,
                        CorrelatedAlertBuffer correlatedAlertBuffer,
                        SuppressedSignupBuffer signupBuffer,
                        SuppressedSummaryBuffer summaryBuffer,
                        int port) {
        this.analyticsStreams = analyticsStreams;
        this.correlatedAlertBuffer = correlatedAlertBuffer;
        this.signupBuffer = signupBuffer;
        this.summaryBuffer = summaryBuffer;
        this.port = port;
    }

    // ── D1: Start embedded HTTP server ────────────────────────────────────────────

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);

        // D2: Non-windowed user store endpoints
        server.createContext("/api/users", this::handleUsers);

        // D3: Windowed alert stats endpoint
        server.createContext("/api/alerts/stats", this::handleAlertStats);

        // D3: Windowed signup counts endpoint
        server.createContext("/api/signups/stats", this::handleSignupStats);

        // D3: Windowed login counts endpoint
        server.createContext("/api/logins/stats", this::handleLoginStats);

        // C6/D5: KStream-KStream windowed join output — tailed via background consumer
        server.createContext("/api/correlated-alerts/summary", this::handleCorrelatedAlertsSummary);
        server.createContext("/api/correlated-alerts", this::handleCorrelatedAlerts);

        // Demo reset: clears the correlated-alerts buffer
        server.createContext("/api/reset", this::handleReset);

        // D4: Health/status endpoint
        server.createContext("/api/status", this::handleStatus);

        server.setExecutor(null); // default executor (one thread per request)
        server.start();
        logger.info("Interactive query REST server listening on port {}", port);
        logger.info("Endpoints: /api/users, /api/alerts/stats, /api/signups/stats, /api/logins/stats, /api/correlated-alerts, /api/correlated-alerts/summary, /api/reset, /api/status");
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            logger.info("Interactive query REST server stopped");
        }
    }

    // ── Readiness guard ───────────────────────────────────────────────────────────

    private boolean isReady() {
        return analyticsStreams != null
                && analyticsStreams.state() == KafkaStreams.State.RUNNING;
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // D2: Non-windowed user endpoints  (user-dashboard, user-activity, payment-history)
    // ═══════════════════════════════════════════════════════════════════════════════

    private void handleUsers(HttpExchange exchange) throws IOException {
        if (!isReady()) {
            sendJson(exchange, 503, notReadyNode());
            return;
        }

        String path = exchange.getRequestURI().getPath(); // e.g. /api/users/abc/payments
        String[] parts = path.replaceAll("^/+|/+$", "").split("/");
        // parts: ["api", "users"] or ["api", "users", "{id}"] or ["api", "users", "{id}", "activity"]

        try {
            if (parts.length == 2) {
                // GET /api/users — list all dashboard entries
                listAllUsers(exchange);
            } else if (parts.length == 3) {
                // GET /api/users/{userId}
                getUser(exchange, parts[2]);
            } else if (parts.length == 4 && "activity".equals(parts[3])) {
                // GET /api/users/{userId}/activity
                getUserActivity(exchange, parts[2]);
            } else if (parts.length == 4 && "payments".equals(parts[3])) {
                // GET /api/users/{userId}/payments
                getUserPayments(exchange, parts[2]);
            } else {
                sendJson(exchange, 404, errorNode("Not found: " + path));
            }
        } catch (InvalidStateStoreException e) {
            logger.warn("Store not available: {}", e.getMessage());
            sendJson(exchange, 503, errorNode("State store not available — topology may still be initializing"));
        }
    }

    /** GET /api/users — all entries in user-dashboard store. */
    private void listAllUsers(HttpExchange exchange) throws IOException {
        ReadOnlyKeyValueStore<String, UserDashboard> store = keyValueStore("user-dashboard");
        ArrayNode results = MAPPER.createArrayNode();
        try (KeyValueIterator<String, UserDashboard> it = store.all()) {
            while (it.hasNext()) {
                KeyValue<String, UserDashboard> kv = it.next();
                results.add(dashboardToJson(kv.value));
            }
        }
        sendJson(exchange, 200, results);
    }

    /** GET /api/users/{userId} — single UserDashboard record. */
    private void getUser(HttpExchange exchange, String userId) throws IOException {
        ReadOnlyKeyValueStore<String, UserDashboard> store = keyValueStore("user-dashboard");
        UserDashboard record = store.get(userId);
        if (record == null) {
            sendJson(exchange, 404, errorNode("User not found: " + userId));
        } else {
            sendJson(exchange, 200, dashboardToJson(record));
        }
    }

    /** GET /api/users/{userId}/activity — UserActivity record. */
    private void getUserActivity(HttpExchange exchange, String userId) throws IOException {
        ReadOnlyKeyValueStore<String, UserActivity> store = keyValueStore("user-activity");
        UserActivity record = store.get(userId);
        if (record == null) {
            sendJson(exchange, 404, errorNode("User activity not found: " + userId));
        } else {
            sendJson(exchange, 200, activityToJson(record));
        }
    }

    /** GET /api/users/{userId}/payments — PaymentSummary record. */
    private void getUserPayments(HttpExchange exchange, String userId) throws IOException {
        ReadOnlyKeyValueStore<String, PaymentSummary> store = keyValueStore("payment-history");
        PaymentSummary record = store.get(userId);
        if (record == null) {
            sendJson(exchange, 404, errorNode("Payment history not found: " + userId));
        } else {
            sendJson(exchange, 200, paymentToJson(record));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // D3: Windowed endpoints  (alert-stats, signup-counts)
    // Note: these stores are populated by Marc's C4 (windowed aggregations).
    // Until C4 is wired, queries return an empty array (store not found → 503).
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * GET /api/alerts/stats/{symbol}?from=<epochMs>&to=<epochMs>
     *
     * Queries the "alert-stats" ReadOnlyWindowStore for a given symbol/topic key.
     * Returns all window records within [from, to]. Defaults to last 1 hour.
     *
     * Ref: Plan §3 (App B), Lab 13 Part 2 MonitorService windowed fetch pattern.
     */
    private void handleAlertStats(HttpExchange exchange) throws IOException {
        if (!isReady()) {
            sendJson(exchange, 503, notReadyNode());
            return;
        }

        String path = exchange.getRequestURI().getPath(); // /api/alerts/stats/{symbol}
        String[] parts = path.replaceAll("^/+|/+$", "").split("/");
        if (parts.length < 4) {
            sendJson(exchange, 400, errorNode("Usage: /api/alerts/stats/{symbol}?from=<ms>&to=<ms>"));
            return;
        }

        String symbol = parts[3];
        long[] range = parseTimeRange(exchange.getRequestURI().getQuery());

        try {
            ReadOnlyWindowStore<String, AlertStats> store = windowStore("alert-stats");
            ArrayNode results = MAPPER.createArrayNode();

            try (WindowStoreIterator<AlertStats> it = store.fetch(
                    symbol, Instant.ofEpochMilli(range[0]), Instant.ofEpochMilli(range[1]))) {
                while (it.hasNext()) {
                    KeyValue<Long, AlertStats> kv = it.next();
                    ObjectNode window = MAPPER.createObjectNode();
                    window.put("windowStart", kv.key);
                    window.put("windowEnd", kv.key + 60_000); // 1-min tumbling window
                    window.put("symbolOrTopic", symbol);
                    AlertStats stats = kv.value;
                    window.put("alertCount", stats.getAlertCount());
                    window.put("avgChangePercent", stats.getAvgChangePercent());
                    window.put("lastRiskLevel", stats.getLastRiskLevel());
                    results.add(window);
                }
            }
            sendJson(exchange, 200, results);
        } catch (InvalidStateStoreException e) {
            logger.warn("alert-stats store not available (C4 not yet wired): {}", e.getMessage());
            sendJson(exchange, 503, errorNode(
                    "alert-stats store not available — waiting for windowed aggregations (C4)"));
        }
    }

    /**
     * GET /api/signups/stats/{tier}?from=<epochMs>&to=<epochMs>
     *
     * Reads from the SuppressedSignupBuffer — which tails the suppress output
     * topic "signup-counts-final". Only contains final window results (not
     * intermediate counts), matching true suppress semantics.
     */
    private void handleSignupStats(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String[] parts = path.replaceAll("^/+|/+$", "").split("/");
        if (parts.length < 4) {
            sendJson(exchange, 400, errorNode("Usage: /api/signups/stats/{tier}?from=<ms>&to=<ms>"));
            return;
        }

        String tier = parts[3].toUpperCase();
        long[] range = parseTimeRange(exchange.getRequestURI().getQuery());

        ArrayNode results = MAPPER.createArrayNode();
        for (SuppressedSignupBuffer.WindowedCount wc : signupBuffer.getByTier(tier)) {
            if (wc.windowStart >= range[0] && wc.windowStart <= range[1]) {
                ObjectNode window = MAPPER.createObjectNode();
                window.put("windowStart", wc.windowStart);
                window.put("windowEnd", wc.windowEnd);
                window.put("tier", wc.tier);
                window.put("signupCount", wc.count);
                results.add(window);
            }
        }
        sendJson(exchange, 200, results);
    }

    /**
     * GET /api/logins/stats/{location}?from=<epochMs>&to=<epochMs>
     *
     * Queries the "login-stats" ReadOnlyWindowStore for a given location key.
     * Returns window counts within [from, to]. Defaults to last 1 hour.
     *
     * Window size: 30s for demo (production would be ~1hr). No suppress — emits intermediate results in real time.
     */
    private void handleLoginStats(HttpExchange exchange) throws IOException {
        if (!isReady()) {
            sendJson(exchange, 503, notReadyNode());
            return;
        }

        String path = exchange.getRequestURI().getPath(); // /api/logins/stats/{location}
        String[] parts = path.replaceAll("^/+|/+$", "").split("/");
        if (parts.length < 4) {
            sendJson(exchange, 400, errorNode("Usage: /api/logins/stats/{location}?from=<ms>&to=<ms>"));
            return;
        }

        // Normalise the location to lower-case to match the key normalisation
        // applied in AnalyticsDashboardTopology#normalizeLocation. Without
        // this, `/api/logins/stats/zurich` returned [] because the store was
        // keyed by whatever case the producer happened to use ("Zurich").
        String location = parts[3].trim().toLowerCase(java.util.Locale.ROOT);

        long[] range = parseTimeRange(exchange.getRequestURI().getQuery());

        try {
            ReadOnlyWindowStore<String, Long> store = windowStore("login-stats");
            ArrayNode results = MAPPER.createArrayNode();

            // TODO (learn 2 — medium): also compute a running total across the window range
            //   and attach it to the response (e.g. wrap `results` in an ObjectNode that has
            //   { "windows": [...], "totalLogins": <sum> }). Initialise a `long total = 0`
            //   before the loop and add `kv.value` inside it. Good practice for accumulating
            //   while iterating — same pattern you'd use for "average per window" later.

            try (WindowStoreIterator<Long> it = store.fetch(
                    location, Instant.ofEpochMilli(range[0]), Instant.ofEpochMilli(range[1]))) {
                while (it.hasNext()) {
                    KeyValue<Long, Long> kv = it.next();
                    ObjectNode window = MAPPER.createObjectNode();
                    window.put("windowStart", kv.key);
                    window.put("windowEnd", kv.key + 30_000L); // 30-second tumbling window
                    // TODO (learn 3 — easy): add human-readable ISO-8601 timestamps next to
                    //   the epoch-ms fields. Use Instant.ofEpochMilli(kv.key).toString() and
                    //   put it as "windowStartIso". Makes the response readable in a browser
                    //   without converting epoch ms by hand during demos.
                    window.put("location", location);
                    window.put("loginCount", kv.value);
                    results.add(window);
                }
            }
            sendJson(exchange, 200, results);
        } catch (InvalidStateStoreException e) {
            logger.warn("login-stats store not available: {}", e.getMessage());
            sendJson(exchange, 503, errorNode(
                    "login-stats store not available — waiting for windowed aggregations"));
        }
    }

    // TODO (learn 4 — harder): add a new endpoint `GET /api/logins/stats` (no path param)
    //   that returns counts for *every* location in the window. Steps:
    //     1. Register a new context in start() — but careful: HttpServer matches by prefix,
    //        so `/api/logins/stats` already routes to handleLoginStats. You can detect the
    //        no-param case at the top of handleLoginStats by checking parts.length == 3.
    //     2. Use `store.fetchAll(fromTime, toTime)` instead of `store.fetch(key, ...)`.
    //        It returns a KeyValueIterator<Windowed<String>, Long>. The key is wrapped in
    //        a Windowed<String>, so call `.key().key()` to get the location string and
    //        `.key().window().start()` for windowStart.
    //     3. Build the same JSON shape as the per-location endpoint, but include "location"
    //        from the windowed key. Reference: Lab 13 Part 2 MonitorService.java
    //        (the `fetchAll`-based aggregate endpoint).

    // ═══════════════════════════════════════════════════════════════════════════════
    // C6/D5: Correlated alerts — tailed from the correlated-alerts topic
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * GET /api/correlated-alerts — newest-first list of recent KStream-KStream
     * join outputs (market + social alerts in the same sector within 5 minutes).
     * Optional ?limit=<n> caps the response size; defaults to the buffer's full snapshot.
     */
    private void handleCorrelatedAlerts(HttpExchange exchange) throws IOException {
        if (correlatedAlertBuffer == null) {
            sendJson(exchange, 503, errorNode("Correlated alerts buffer not initialized"));
            return;
        }

        int limit = parseLimit(exchange.getRequestURI().getQuery(), Integer.MAX_VALUE);
        ArrayNode results = MAPPER.createArrayNode();
        int count = 0;
        for (CorrelatedAlert alert : correlatedAlertBuffer.snapshot()) {
            if (count >= limit) break;
            results.add(correlatedAlertToJson(alert));
            count++;
        }
        sendJson(exchange, 200, results);
    }

    private ObjectNode correlatedAlertToJson(CorrelatedAlert c) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("sector", c.getSector());
        node.put("marketSymbol", c.getMarketSymbol());
        node.put("marketAlertType", c.getMarketAlertType());
        node.put("marketRiskLevel", c.getMarketRiskLevel());
        if (c.getMarketChangePercent() != null) {
            node.put("marketChangePercent", c.getMarketChangePercent());
        } else {
            node.putNull("marketChangePercent");
        }
        node.put("socialTopic", c.getSocialTopic());
        if (c.getSocialPostCount() != null) {
            node.put("socialPostCount", c.getSocialPostCount());
        } else {
            node.putNull("socialPostCount");
        }
        node.put("socialRiskLevel", c.getSocialRiskLevel());
        node.put("correlationTimestamp", c.getCorrelationTimestamp() != null
                ? c.getCorrelationTimestamp().toString() : null);
        return node;
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // C7: Correlated Alert Summary — windowed aggregation of C6 join output
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * GET /api/correlated-alerts/summary
     *
     * Reads from the SuppressedSummaryBuffer — which tails the suppress output
     * topic "correlated-alerts-summary-final". Only contains final window results.
     */
    private void handleCorrelatedAlertsSummary(HttpExchange exchange) throws IOException {
        ArrayNode results = MAPPER.createArrayNode();
        for (CorrelatedAlertSummary summary : summaryBuffer.snapshot()) {
            ObjectNode node = summaryToJson(summary);
            if (summary.getWindowStart() != null) {
                node.put("windowStart", summary.getWindowStart().toEpochMilli());
            }
            if (summary.getWindowEnd() != null) {
                node.put("windowEnd", summary.getWindowEnd().toEpochMilli());
            }
            results.add(node);
        }
        sendJson(exchange, 200, results);
    }

    private ObjectNode summaryToJson(CorrelatedAlertSummary s) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("sector", s.getSector());
        node.put("matchCount", s.getMatchCount());

        ArrayNode symbols = node.putArray("marketSymbols");
        if (s.getMarketSymbols() != null) {
            s.getMarketSymbols().forEach(sym -> symbols.add(sym.toString()));
        }

        ArrayNode topics = node.putArray("socialTopics");
        if (s.getSocialTopics() != null) {
            s.getSocialTopics().forEach(t -> topics.add(t.toString()));
        }

        if (s.getMaxAbsMarketChange() != null) {
            node.put("maxAbsMarketChange", s.getMaxAbsMarketChange());
        } else {
            node.putNull("maxAbsMarketChange");
        }
        node.put("maxRiskLevel", s.getMaxRiskLevel());
        return node;
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // Demo reset endpoint
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * POST /api/reset — clears the correlated-alerts buffer so the dashboard
     * starts fresh before a new demo scenario. Also handles OPTIONS for CORS.
     */
    private void handleReset(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();

        if ("OPTIONS".equals(method)) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "POST, OPTIONS");
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        if (!"POST".equals(method)) {
            sendJson(exchange, 405, errorNode("Method not allowed — use POST"));
            return;
        }

        if (correlatedAlertBuffer != null) {
            correlatedAlertBuffer.clear();
        }
        if (signupBuffer != null) {
            signupBuffer.clear();
        }
        if (summaryBuffer != null) {
            summaryBuffer.clear();
        }

        logger.info("Demo reset: all buffers cleared");
        ObjectNode result = MAPPER.createObjectNode();
        result.put("status", "reset");
        result.put("clearedBuffer", true);
        sendJson(exchange, 200, result);
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // D4: Health / status endpoint
    // ═══════════════════════════════════════════════════════════════════════════════

    /** GET /api/status — KafkaStreams state, topology description, store names, uptime. */
    private void handleStatus(HttpExchange exchange) throws IOException {
        ObjectNode status = MAPPER.createObjectNode();

        status.put("service", "WorldPulse Stream Processing");
        status.put("uptimeSeconds", (System.currentTimeMillis() - startedAt) / 1000);

        // Analytics topology state
        String analyticsState = analyticsStreams != null
                ? analyticsStreams.state().name()
                : "NOT_STARTED";
        status.put("analyticsTopologyState", analyticsState);
        status.put("ready", isReady());

        // Available stores (only accessible when RUNNING)
        ArrayNode stores = status.putArray("storeNames");
        stores.add("user-activity");
        stores.add("payment-history");
        stores.add("user-dashboard");
        stores.add("alert-stats");
        stores.add("signup-counts");
        stores.add("login-stats");
        stores.add("correlated-alerts-summary");

        ArrayNode endpoints = status.putArray("endpoints");
        endpoints.add("GET /api/users");
        endpoints.add("GET /api/users/{userId}");
        endpoints.add("GET /api/users/{userId}/activity");
        endpoints.add("GET /api/users/{userId}/payments");
        endpoints.add("GET /api/alerts/stats/{symbol}?from=<ms>&to=<ms>");
        endpoints.add("GET /api/signups/stats/{tier}?from=<ms>&to=<ms>");
        endpoints.add("GET /api/logins/stats/{location}?from=<ms>&to=<ms>");
        endpoints.add("GET /api/correlated-alerts?limit=<n>");
        endpoints.add("GET /api/correlated-alerts/summary?from=<ms>&to=<ms>");
        endpoints.add("POST /api/reset");
        endpoints.add("GET /api/status");

        sendJson(exchange, 200, status);
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // Store accessors
    // ═══════════════════════════════════════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    private <V> ReadOnlyKeyValueStore<String, V> keyValueStore(String storeName) {
        return analyticsStreams.store(
                StoreQueryParameters.fromNameAndType(storeName, QueryableStoreTypes.keyValueStore()));
    }

    @SuppressWarnings("unchecked")
    private <V> ReadOnlyWindowStore<String, V> windowStore(String storeName) {
        return analyticsStreams.store(
                StoreQueryParameters.fromNameAndType(storeName, QueryableStoreTypes.windowStore()));
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // Avro → JSON conversion helpers (manual — avoids Jackson/Avro SpecificRecord friction)
    // ═══════════════════════════════════════════════════════════════════════════════

    private ObjectNode dashboardToJson(UserDashboard d) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("userId", d.getUserId());
        node.put("name", d.getName());
        node.put("email", d.getEmail());
        node.put("currentTier", d.getCurrentTier());
        node.put("lifecycleEventCount", d.getLifecycleEventCount());
        node.put("lastLifecycleEvent", d.getLastLifecycleEvent());
        node.put("totalPayments", d.getTotalPayments());
        node.put("successfulPayments", d.getSuccessfulPayments());
        node.put("failedPayments", d.getFailedPayments());
        node.put("lastPaymentStatus", d.getLastPaymentStatus());
        return node;
    }

    private ObjectNode activityToJson(UserActivity a) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("userId", a.getUserId());
        node.put("name", a.getName());
        node.put("email", a.getEmail());
        node.put("currentTier", a.getCurrentTier());
        node.put("eventCount", a.getEventCount());
        node.put("lastEventType", a.getLastEventType());
        node.put("lastEventTimestamp", a.getLastEventTimestamp() != null
                ? a.getLastEventTimestamp().toString() : null);
        return node;
    }

    private ObjectNode paymentToJson(PaymentSummary p) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("userId", p.getUserId());
        node.put("totalPayments", p.getTotalPayments());
        node.put("successfulPayments", p.getSuccessfulPayments());
        node.put("failedPayments", p.getFailedPayments());
        node.put("lastPaymentStatus", p.getLastPaymentStatus());
        node.put("lastPaymentTimestamp", p.getLastPaymentTimestamp() != null
                ? p.getLastPaymentTimestamp().toString() : null);
        return node;
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // HTTP helpers
    // ═══════════════════════════════════════════════════════════════════════════════

    private void sendJson(HttpExchange exchange, int statusCode, Object body) throws IOException {
        byte[] bytes = MAPPER.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private ObjectNode errorNode(String message) {
        return MAPPER.createObjectNode().put("error", message);
    }

    private ObjectNode notReadyNode() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("error", "Analytics topology not ready");
        node.put("state", analyticsStreams != null
                ? analyticsStreams.state().name() : "NOT_STARTED");
        node.put("hint", "Wait for state = RUNNING before querying stores");
        return node;
    }

    /**
     * Parses ?limit=<n> query param. Returns the supplied default when missing
     * or unparseable. Caps at 1000 to keep responses bounded.
     */
    private int parseLimit(String query, int defaultLimit) {
        if (query == null) return defaultLimit;
        for (String param : query.split("&")) {
            String[] kv = param.split("=", 2);
            if (kv.length == 2 && "limit".equals(kv[0])) {
                try {
                    int n = Integer.parseInt(kv[1]);
                    return Math.min(Math.max(n, 0), 1000);
                } catch (NumberFormatException ignored) {}
            }
        }
        return defaultLimit;
    }

    /**
     * Parses ?from=<epochMs>&to=<epochMs> query params.
     * Defaults to [now - 1 hour, now] if omitted.
     */
    private long[] parseTimeRange(String query) {
        long now = System.currentTimeMillis();
        long from = now - 3_600_000L; // 1 hour ago
        long to = now;

        if (query != null) {
            for (String param : query.split("&")) {
                String[] kv = param.split("=", 2);
                if (kv.length == 2) {
                    try {
                        if ("from".equals(kv[0])) from = Long.parseLong(kv[1]);
                        if ("to".equals(kv[0]))   to   = Long.parseLong(kv[1]);
                    } catch (NumberFormatException ignored) {}
                }
            }
        }
        return new long[]{from, to};
    }
}
