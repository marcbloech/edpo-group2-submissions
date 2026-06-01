package ch.unisg.worldpulse.streams;

import static org.assertj.core.api.Assertions.assertThat;

import ch.unisg.worldpulse.streams.avro.AlertStats;
import ch.unisg.worldpulse.streams.avro.CorrelatedAlert;
import ch.unisg.worldpulse.streams.avro.CorrelatedAlertSummary;
import ch.unisg.worldpulse.streams.avro.EnrichedAlert;
import ch.unisg.worldpulse.streams.avro.NormalizedSignup;
import ch.unisg.worldpulse.streams.avro.PaymentSummary;
import ch.unisg.worldpulse.streams.avro.RiskLevel;
import ch.unisg.worldpulse.streams.avro.Tier;
import ch.unisg.worldpulse.streams.avro.UserActivity;
import ch.unisg.worldpulse.streams.avro.UserDashboard;
import ch.unisg.worldpulse.streams.serialization.avro.AvroSerdes;
import ch.unisg.worldpulse.streams.serialization.json.JsonSerdes;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.Instant;
import java.util.Properties;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.TopologyTestDriver;
import org.apache.kafka.streams.state.KeyValueStore;
import org.apache.kafka.streams.state.ValueAndTimestamp;
import org.apache.kafka.streams.state.WindowStore;
import org.apache.kafka.streams.state.WindowStoreIterator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class AnalyticsDashboardTopologyTest {

    private static final ObjectMapper mapper = new ObjectMapper();
    private TopologyTestDriver testDriver;
    private org.apache.kafka.streams.TestInputTopic<String, JsonNode> worldpulseTopic;
    private org.apache.kafka.streams.TestInputTopic<String, NormalizedSignup> signupsNormalizedTopic;
    private org.apache.kafka.streams.TestInputTopic<String, EnrichedAlert> alertsEnrichedTopic;
    private org.apache.kafka.streams.TestInputTopic<String, CorrelatedAlert> correlatedAlertsTopic;

    @BeforeEach
    void setup() {
        Properties props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, "test-analytics-dashboard");
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "dummy:9092");
        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.StringSerde.class);
        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.StringSerde.class);

        var topology = AnalyticsDashboardTopology.build();
        testDriver = new TopologyTestDriver(topology, props);

        worldpulseTopic = testDriver.createInputTopic(
                "worldpulse",
                new StringSerializer(),
                JsonSerdes.JsonNode().serializer());

        signupsNormalizedTopic = testDriver.createInputTopic(
                "signups-normalized",
                new StringSerializer(),
                AvroSerdes.NormalizedSignup().serializer());

        alertsEnrichedTopic = testDriver.createInputTopic(
                "alerts-enriched",
                new StringSerializer(),
                AvroSerdes.EnrichedAlert().serializer());

        correlatedAlertsTopic = testDriver.createInputTopic(
                "correlated-alerts",
                new StringSerializer(),
                AvroSerdes.CorrelatedAlert().serializer());
    }

    @AfterEach
    void teardown() {
        if (testDriver != null) {
            testDriver.close();
        }
    }

    // --- Helper: build a CloudEvents envelope like the Python EventPublisher ---

    private JsonNode cloudEvent(String eventType, ObjectNode data) {
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("type", eventType);
        envelope.put("id", java.util.UUID.randomUUID().toString());
        envelope.put("source", "test");
        envelope.put("time", Instant.now().toString());
        envelope.set("data", data);
        envelope.put("datacontenttype", "application/json");
        envelope.put("specversion", "1.0");
        envelope.put("traceid", java.util.UUID.randomUUID().toString());
        envelope.putNull("correlationid");
        envelope.put("group", "worldpulse");
        return envelope;
    }

    private ObjectNode signupData(String userId, String name, String email, String tier) {
        ObjectNode data = mapper.createObjectNode();
        data.put("userId", userId);
        data.put("name", name);
        data.put("email", email);
        data.put("tier", tier);
        return data;
    }

    private ObjectNode paymentData(String userId, String name, String email, String tier) {
        return signupData(userId, name, email, tier);
    }

    private Headers headersWithType(String eventType) {
        RecordHeaders headers = new RecordHeaders();
        headers.add("type", eventType.getBytes());
        return headers;
    }

    private void pipeWithHeaders(String key, JsonNode value, String eventType) {
        worldpulseTopic.pipeInput(
                new org.apache.kafka.streams.test.TestRecord<>(
                        key, value, headersWithType(eventType)));
    }

    // ===== C1: userId-keyed KTables =====

    @Nested
    @DisplayName("C1: User Activity KTable")
    class UserActivityKTableTest {

        @Test
        @DisplayName("SignupRequestedEvent creates a user-activity entry keyed by userId")
        void signupCreatesUserActivity() {
            ObjectNode data = signupData("user-1", "Marc", "marc@test.com", "PRO");
            JsonNode event = cloudEvent("SignupRequestedEvent", data);

            pipeWithHeaders("some-key", event, "SignupRequestedEvent");

            KeyValueStore<String, UserActivity> store =
                    testDriver.getKeyValueStore("user-activity");
            UserActivity activity = store.get("user-1");

            assertThat(activity).isNotNull();
            assertThat(activity.getUserId()).isEqualTo("user-1");
            assertThat(activity.getName()).isEqualTo("Marc");
            assertThat(activity.getEmail()).isEqualTo("marc@test.com");
            assertThat(activity.getCurrentTier()).isEqualTo("PRO");
            assertThat(activity.getEventCount()).isEqualTo(1L);
            assertThat(activity.getLastEventType()).isEqualTo("SignupRequestedEvent");
        }

        @Test
        @DisplayName("Multiple events for same user increment eventCount")
        void multipleEventsIncrementCount() {
            ObjectNode signup = signupData("user-1", "Marc", "marc@test.com", "FREE");
            pipeWithHeaders("k1", cloudEvent("SignupRequestedEvent", signup), "SignupRequestedEvent");

            ObjectNode upgrade = signupData("user-1", "Marc", "marc@test.com", "PRO");
            upgrade.put("currentTier", "FREE");
            upgrade.put("targetTier", "PRO");
            pipeWithHeaders("k2", cloudEvent("UpgradeRequestedEvent", upgrade), "UpgradeRequestedEvent");

            KeyValueStore<String, UserActivity> store =
                    testDriver.getKeyValueStore("user-activity");
            UserActivity activity = store.get("user-1");

            assertThat(activity).isNotNull();
            assertThat(activity.getEventCount()).isEqualTo(2L);
            assertThat(activity.getLastEventType()).isEqualTo("UpgradeRequestedEvent");
            assertThat(activity.getCurrentTier()).isEqualTo("PRO");
        }

        @Test
        @DisplayName("Different users get separate entries")
        void differentUsersAreSeparate() {
            pipeWithHeaders("k1",
                    cloudEvent("SignupRequestedEvent",
                            signupData("user-1", "Marc", "marc@test.com", "FREE")),
                    "SignupRequestedEvent");
            pipeWithHeaders("k2",
                    cloudEvent("SignupRequestedEvent",
                            signupData("user-2", "Arman", "arman@test.com", "PRO")),
                    "SignupRequestedEvent");

            KeyValueStore<String, UserActivity> store =
                    testDriver.getKeyValueStore("user-activity");

            assertThat(store.get("user-1")).isNotNull();
            assertThat(store.get("user-1").getName()).isEqualTo("Marc");
            assertThat(store.get("user-2")).isNotNull();
            assertThat(store.get("user-2").getName()).isEqualTo("Arman");
        }

        @Test
        @DisplayName("Irrelevant events (MarketAlertEvent) are ignored")
        void marketEventsIgnored() {
            ObjectNode data = mapper.createObjectNode();
            data.put("symbol", "AAPL");
            data.put("alertType", "PRICE_SPIKE");
            data.put("currentPrice", 150.0);
            data.put("changePercent", 5.3);
            pipeWithHeaders("k1", cloudEvent("MarketAlertEvent", data), "MarketAlertEvent");

            KeyValueStore<String, UserActivity> store =
                    testDriver.getKeyValueStore("user-activity");
            // The store should be empty — no user activity from market events
            try (var iter = store.all()) {
                assertThat(iter.hasNext()).isFalse();
            }
        }
    }

    @Nested
    @DisplayName("C1: Payment History KTable")
    class PaymentHistoryKTableTest {

        @Test
        @DisplayName("PaymentReceivedEvent creates a payment-history entry")
        void paymentReceivedCreatesEntry() {
            ObjectNode data = paymentData("user-1", "Marc", "marc@test.com", "PRO");
            pipeWithHeaders("k1", cloudEvent("PaymentReceivedEvent", data), "PaymentReceivedEvent");

            KeyValueStore<String, PaymentSummary> store =
                    testDriver.getKeyValueStore("payment-history");
            PaymentSummary summary = store.get("user-1");

            assertThat(summary).isNotNull();
            assertThat(summary.getUserId()).isEqualTo("user-1");
            assertThat(summary.getTotalPayments()).isEqualTo(1L);
            assertThat(summary.getSuccessfulPayments()).isEqualTo(1L);
            assertThat(summary.getFailedPayments()).isEqualTo(0L);
            assertThat(summary.getLastPaymentStatus()).isEqualTo("SUCCESS");
        }

        @Test
        @DisplayName("PaymentFailedEvent increments failedPayments")
        void paymentFailedIncrementsFailures() {
            ObjectNode data = paymentData("user-1", "Marc", "marc@test.com", "PRO");
            pipeWithHeaders("k1", cloudEvent("PaymentFailedEvent", data), "PaymentFailedEvent");

            KeyValueStore<String, PaymentSummary> store =
                    testDriver.getKeyValueStore("payment-history");
            PaymentSummary summary = store.get("user-1");

            assertThat(summary).isNotNull();
            assertThat(summary.getTotalPayments()).isEqualTo(1L);
            assertThat(summary.getSuccessfulPayments()).isEqualTo(0L);
            assertThat(summary.getFailedPayments()).isEqualTo(1L);
            assertThat(summary.getLastPaymentStatus()).isEqualTo("FAILED");
        }

        @Test
        @DisplayName("Mixed payment events accumulate correctly")
        void mixedPaymentsAccumulate() {
            ObjectNode data = paymentData("user-1", "Marc", "marc@test.com", "PRO");

            pipeWithHeaders("k1", cloudEvent("PaymentReceivedEvent", data), "PaymentReceivedEvent");
            pipeWithHeaders("k2", cloudEvent("PaymentReceivedEvent", data), "PaymentReceivedEvent");
            pipeWithHeaders("k3", cloudEvent("PaymentFailedEvent", data), "PaymentFailedEvent");

            KeyValueStore<String, PaymentSummary> store =
                    testDriver.getKeyValueStore("payment-history");
            PaymentSummary summary = store.get("user-1");

            assertThat(summary).isNotNull();
            assertThat(summary.getTotalPayments()).isEqualTo(3L);
            assertThat(summary.getSuccessfulPayments()).isEqualTo(2L);
            assertThat(summary.getFailedPayments()).isEqualTo(1L);
            assertThat(summary.getLastPaymentStatus()).isEqualTo("FAILED");
        }
    }

    // ===== C2: KTable-KTable Join → UserDashboard =====

    @Nested
    @DisplayName("C2: KTable-KTable Join (UserActivity + PaymentSummary → UserDashboard)")
    class UserDashboardJoinTest {

        @Test
        @DisplayName("Signup + payment for same userId produces a joined UserDashboard")
        void joinProducesDashboard() {
            // Pipe a signup event → populates user-activity KTable
            pipeWithHeaders("k1",
                    cloudEvent("SignupRequestedEvent",
                            signupData("user-1", "Marc", "marc@test.com", "PRO")),
                    "SignupRequestedEvent");

            // Pipe a payment event → populates payment-history KTable
            pipeWithHeaders("k2",
                    cloudEvent("PaymentReceivedEvent",
                            paymentData("user-1", "Marc", "marc@test.com", "PRO")),
                    "PaymentReceivedEvent");

            // The join should fire and produce a UserDashboard entry
            KeyValueStore<String, UserDashboard> store =
                    testDriver.getKeyValueStore("user-dashboard");
            UserDashboard dashboard = store.get("user-1");

            assertThat(dashboard).isNotNull();
            // Fields from UserActivity side
            assertThat(dashboard.getUserId()).isEqualTo("user-1");
            assertThat(dashboard.getName()).isEqualTo("Marc");
            assertThat(dashboard.getEmail()).isEqualTo("marc@test.com");
            assertThat(dashboard.getCurrentTier()).isEqualTo("PRO");
            assertThat(dashboard.getLifecycleEventCount()).isEqualTo(1L);
            assertThat(dashboard.getLastLifecycleEvent()).isEqualTo("SignupRequestedEvent");
            // Fields from PaymentSummary side
            assertThat(dashboard.getTotalPayments()).isEqualTo(1L);
            assertThat(dashboard.getSuccessfulPayments()).isEqualTo(1L);
            assertThat(dashboard.getFailedPayments()).isEqualTo(0L);
            assertThat(dashboard.getLastPaymentStatus()).isEqualTo("SUCCESS");
        }

        @Test
        @DisplayName("Dashboard updates when either side changes")
        void dashboardUpdatesOnEitherSide() {
            // Initial signup + payment
            pipeWithHeaders("k1",
                    cloudEvent("SignupRequestedEvent",
                            signupData("user-1", "Marc", "marc@test.com", "FREE")),
                    "SignupRequestedEvent");
            pipeWithHeaders("k2",
                    cloudEvent("PaymentReceivedEvent",
                            paymentData("user-1", "Marc", "marc@test.com", "FREE")),
                    "PaymentReceivedEvent");

            // Now upgrade the user → user-activity side updates, join fires again
            ObjectNode upgrade = signupData("user-1", "Marc", "marc@test.com", "FREE");
            upgrade.put("targetTier", "ENTERPRISE");
            pipeWithHeaders("k3", cloudEvent("UpgradeRequestedEvent", upgrade), "UpgradeRequestedEvent");

            KeyValueStore<String, UserDashboard> store =
                    testDriver.getKeyValueStore("user-dashboard");
            UserDashboard dashboard = store.get("user-1");

            assertThat(dashboard).isNotNull();
            // User side should reflect the upgrade
            assertThat(dashboard.getCurrentTier()).isEqualTo("ENTERPRISE");
            assertThat(dashboard.getLifecycleEventCount()).isEqualTo(2L);
            assertThat(dashboard.getLastLifecycleEvent()).isEqualTo("UpgradeRequestedEvent");
            // Payment side unchanged
            assertThat(dashboard.getTotalPayments()).isEqualTo(1L);
            assertThat(dashboard.getSuccessfulPayments()).isEqualTo(1L);
        }

        @Test
        @DisplayName("Dashboard entry exists with only signup (left join — no payments yet)")
        void entryExistsWithOnlySignup() {
            // Only a signup, no payment → leftJoin produces a result with zero payment fields
            pipeWithHeaders("k1",
                    cloudEvent("SignupRequestedEvent",
                            signupData("user-1", "Marc", "marc@test.com", "FREE")),
                    "SignupRequestedEvent");

            KeyValueStore<String, UserDashboard> store =
                    testDriver.getKeyValueStore("user-dashboard");

            UserDashboard dashboard = store.get("user-1");
            assertThat(dashboard).isNotNull();
            assertThat(dashboard.getUserId()).isEqualTo("user-1");
            assertThat(dashboard.getCurrentTier()).isEqualTo("FREE");
            assertThat(dashboard.getTotalPayments()).isEqualTo(0L);
            assertThat(dashboard.getSuccessfulPayments()).isEqualTo(0L);
            assertThat(dashboard.getFailedPayments()).isEqualTo(0L);
            assertThat(dashboard.getLastPaymentStatus()).isEqualTo("");
        }
    }

    // ===== C4: Windowed Aggregations =====

    @Nested
    @DisplayName("C4a: Alert Stats — 1-minute tumbling window")
    class AlertStatsWindowedTest {

        private EnrichedAlert makeAlert(String symbol, RiskLevel risk, double changePercent, Instant ts) {
            EnrichedAlert alert = new EnrichedAlert();
            alert.setAlertId(java.util.UUID.randomUUID().toString());
            alert.setSource("test");
            alert.setAlertType("PRICE_SPIKE");
            alert.setSymbol(symbol);
            alert.setTopic(null);
            alert.setUserId(null);
            alert.setDescription("Test alert for " + symbol);
            alert.setRiskLevel(risk);
            alert.setChangePercent(changePercent);
            alert.setPostCount(null);
            alert.setTimestamp(ts);
            return alert;
        }

        @Test
        @DisplayName("Alerts within same 1-min window aggregate together")
        void alertsAggregateInWindow() {
            Instant base = Instant.parse("2026-05-04T10:00:00Z");

            // Three alerts for AAPL within the same 1-minute window
            // Must pass explicit timestamps so the TopologyTestDriver uses them for windowing
            alertsEnrichedTopic.pipeInput("AAPL", makeAlert("AAPL", RiskLevel.HIGH, -5.0, base), base);
            alertsEnrichedTopic.pipeInput("AAPL", makeAlert("AAPL", RiskLevel.MEDIUM, -3.0, base.plusSeconds(20)), base.plusSeconds(20));
            alertsEnrichedTopic.pipeInput("AAPL", makeAlert("AAPL", RiskLevel.LOW, -1.0, base.plusSeconds(40)), base.plusSeconds(40));

            // Query the windowed store for AAPL in the time range covering this window
            WindowStore<String, ValueAndTimestamp<AlertStats>> store =
                    testDriver.getTimestampedWindowStore("alert-stats");
            try (var iter = store.fetch("AAPL", base.minusSeconds(1), base.plusSeconds(61))) {
                assertThat(iter.hasNext()).isTrue();
                AlertStats stats = iter.next().value.value();
                assertThat(stats.getAlertCount()).isEqualTo(3L);
                assertThat(stats.getSymbolOrTopic()).isEqualTo("AAPL");
                assertThat(stats.getLastRiskLevel()).isEqualTo("LOW");
                // avgChangePercent = (-5.0 + -3.0 + -1.0) / 3 = -3.0
                assertThat(stats.getAvgChangePercent()).isCloseTo(-3.0, org.assertj.core.data.Offset.offset(0.01));
            }
        }

        @Test
        @DisplayName("Alerts in different 1-min windows are separate")
        void separateWindows() {
            Instant window1 = Instant.parse("2026-05-04T10:00:00Z");
            Instant window2 = Instant.parse("2026-05-04T10:01:30Z"); // 90s later = different window

            alertsEnrichedTopic.pipeInput("AAPL", makeAlert("AAPL", RiskLevel.HIGH, -5.0, window1), window1);
            alertsEnrichedTopic.pipeInput("AAPL", makeAlert("AAPL", RiskLevel.LOW, -1.0, window2), window2);

            WindowStore<String, ValueAndTimestamp<AlertStats>> store =
                    testDriver.getTimestampedWindowStore("alert-stats");

            // Window 1 should have 1 alert
            try (var iter = store.fetch("AAPL", window1.minusSeconds(1), window1.plusSeconds(59))) {
                assertThat(iter.hasNext()).isTrue();
                assertThat(iter.next().value.value().getAlertCount()).isEqualTo(1L);
            }

            // Window 2 should also have 1 alert (window aligned to epoch, so fetch a wider range)
            try (var iter = store.fetch("AAPL", window2.minusSeconds(31), window2.plusSeconds(61))) {
                assertThat(iter.hasNext()).isTrue();
                assertThat(iter.next().value.value().getAlertCount()).isEqualTo(1L);
            }
        }

        @Test
        @DisplayName("Different symbols get independent window entries")
        void differentSymbolsSeparate() {
            Instant base = Instant.parse("2026-05-04T10:00:00Z");

            alertsEnrichedTopic.pipeInput("AAPL", makeAlert("AAPL", RiskLevel.HIGH, -5.0, base), base);
            alertsEnrichedTopic.pipeInput("MSFT", makeAlert("MSFT", RiskLevel.LOW, 2.0, base.plusSeconds(10)), base.plusSeconds(10));

            WindowStore<String, ValueAndTimestamp<AlertStats>> store =
                    testDriver.getTimestampedWindowStore("alert-stats");

            try (var iter = store.fetch("AAPL", base.minusSeconds(1), base.plusSeconds(61))) {
                assertThat(iter.hasNext()).isTrue();
                assertThat(iter.next().value.value().getSymbolOrTopic()).isEqualTo("AAPL");
            }
            try (var iter = store.fetch("MSFT", base.minusSeconds(1), base.plusSeconds(61))) {
                assertThat(iter.hasNext()).isTrue();
                assertThat(iter.next().value.value().getSymbolOrTopic()).isEqualTo("MSFT");
            }
        }
    }

    @Nested
    @DisplayName("C4b: Signup Counts — 1-minute tumbling window")
    class SignupCountsWindowedTest {

        private NormalizedSignup makeTimedSignup(String userId, Tier tier, Instant ts) {
            NormalizedSignup signup = new NormalizedSignup();
            signup.setUserId(userId);
            signup.setName("Test User");
            signup.setEmail("test@test.com");
            signup.setTier(tier);
            signup.setEventType("SignupRequestedEvent");
            signup.setTimestamp(ts);
            return signup;
        }

        @Test
        @DisplayName("Signups within same 1-min window are counted by tier")
        void signupsCountedByTier() {
            // Base aligns to a minute boundary so window [10:00:00, 10:01:00) is well-defined.
            // Two FREE signups at +0s and +30s share that window; the PREMIUM signup at +90s
            // falls in the next window [10:01:00, 10:02:00).
            Instant base = Instant.parse("2026-05-04T10:00:00Z");

            signupsNormalizedTopic.pipeInput("k1", makeTimedSignup("u1", Tier.FREE, base), base);
            signupsNormalizedTopic.pipeInput("k2", makeTimedSignup("u2", Tier.FREE, base.plusSeconds(30)), base.plusSeconds(30));
            signupsNormalizedTopic.pipeInput("k3", makeTimedSignup("u3", Tier.PREMIUM, base.plusSeconds(90)), base.plusSeconds(90));

            WindowStore<String, ValueAndTimestamp<Long>> store =
                    testDriver.getTimestampedWindowStore("signup-counts");

            // FREE tier should have 2 signups in the first 1-min window
            try (var iter = store.fetch("FREE", base.minusSeconds(1), base.plusSeconds(301))) {
                assertThat(iter.hasNext()).isTrue();
                assertThat(iter.next().value.value()).isEqualTo(2L);
            }

            // PREMIUM should have 1 in the next window
            try (var iter = store.fetch("PREMIUM", base.minusSeconds(1), base.plusSeconds(301))) {
                assertThat(iter.hasNext()).isTrue();
                assertThat(iter.next().value.value()).isEqualTo(1L);
            }
        }
    }

    // ===== C7: Correlated Alert Summary — windowed aggregation with suppress =====

    @Nested
    @DisplayName("C7: Correlated Alert Summary — 1-min tumbling window with suppress")
    class CorrelatedAlertSummaryTest {

        private CorrelatedAlert makeCorrelated(String sector, String symbol, double change,
                                                String riskLevel, String topic, int postCount,
                                                String socialRisk, Instant ts) {
            CorrelatedAlert alert = new CorrelatedAlert();
            alert.setSector(sector);
            alert.setMarketSymbol(symbol);
            alert.setMarketAlertType("PRICE_SPIKE");
            alert.setMarketRiskLevel(riskLevel);
            alert.setMarketChangePercent(change);
            alert.setSocialTopic(topic);
            alert.setSocialPostCount(postCount);
            alert.setSocialRiskLevel(socialRisk);
            alert.setCorrelationTimestamp(ts);
            return alert;
        }

        @Test
        @DisplayName("Multiple correlated alerts in same sector/window aggregate into one summary")
        void aggregatesWithinWindow() {
            Instant base = Instant.parse("2026-05-04T10:00:00Z");

            // Simulate the cartesian product from C6: 3 alerts in Energy/Commodities
            correlatedAlertsTopic.pipeInput("Energy/Commodities",
                    makeCorrelated("Energy/Commodities", "CL=F", 8.3, "CRITICAL",
                            "Iran oil strike", 1, "LOW", base),
                    base);
            correlatedAlertsTopic.pipeInput("Energy/Commodities",
                    makeCorrelated("Energy/Commodities", "XOM", 4.1, "MEDIUM",
                            "Iran oil strike", 1, "LOW", base.plusSeconds(1)),
                    base.plusSeconds(1));
            correlatedAlertsTopic.pipeInput("Energy/Commodities",
                    makeCorrelated("Energy/Commodities", "CL=F", 8.3, "CRITICAL",
                            "oil prices", 1, "LOW", base.plusSeconds(2)),
                    base.plusSeconds(2));

            // Advance stream time past the 1-min window + 30s grace to trigger suppress
            correlatedAlertsTopic.pipeInput("Energy/Commodities",
                    makeCorrelated("Energy/Commodities", "CL=F", 1.0, "LOW",
                            "dummy", 1, "LOW", base.plusSeconds(91)),
                    base.plusSeconds(91));

            WindowStore<String, CorrelatedAlertSummary> store =
                    testDriver.getWindowStore("correlated-alerts-summary");

            try (var iter = store.fetch("Energy/Commodities",
                    base.minusSeconds(1), base.plusSeconds(61))) {
                assertThat(iter.hasNext()).isTrue();
                CorrelatedAlertSummary summary = iter.next().value;

                assertThat(summary.getSector()).isEqualTo("Energy/Commodities");
                assertThat(summary.getMatchCount()).isEqualTo(3L);
                assertThat(summary.getMarketSymbols()).containsExactlyInAnyOrder("CL=F", "XOM");
                assertThat(summary.getSocialTopics()).containsExactlyInAnyOrder("Iran oil strike", "oil prices");
                assertThat(summary.getMaxAbsMarketChange()).isEqualTo(8.3);
                assertThat(summary.getMaxRiskLevel()).isEqualTo("CRITICAL");
            }
        }

        @Test
        @DisplayName("Different sectors produce separate summaries")
        void separateSectors() {
            Instant base = Instant.parse("2026-05-04T10:00:00Z");

            correlatedAlertsTopic.pipeInput("Energy/Commodities",
                    makeCorrelated("Energy/Commodities", "CL=F", 8.3, "CRITICAL",
                            "oil prices", 1, "LOW", base),
                    base);
            correlatedAlertsTopic.pipeInput("Technology",
                    makeCorrelated("Technology", "AAPL", -5.2, "HIGH",
                            "Grok-X release", 42, "MEDIUM", base.plusSeconds(10)),
                    base.plusSeconds(10));

            // Advance past window close for both
            correlatedAlertsTopic.pipeInput("Energy/Commodities",
                    makeCorrelated("Energy/Commodities", "CL=F", 1.0, "LOW",
                            "dummy", 1, "LOW", base.plusSeconds(91)),
                    base.plusSeconds(91));
            correlatedAlertsTopic.pipeInput("Technology",
                    makeCorrelated("Technology", "AAPL", 1.0, "LOW",
                            "dummy", 1, "LOW", base.plusSeconds(92)),
                    base.plusSeconds(92));

            WindowStore<String, CorrelatedAlertSummary> store =
                    testDriver.getWindowStore("correlated-alerts-summary");

            try (var iter = store.fetch("Energy/Commodities",
                    base.minusSeconds(1), base.plusSeconds(61))) {
                assertThat(iter.hasNext()).isTrue();
                CorrelatedAlertSummary energy = iter.next().value;
                assertThat(energy.getMatchCount()).isEqualTo(1L);
                assertThat(energy.getMarketSymbols()).containsExactly("CL=F");
            }

            try (var iter = store.fetch("Technology",
                    base.minusSeconds(1), base.plusSeconds(61))) {
                assertThat(iter.hasNext()).isTrue();
                CorrelatedAlertSummary tech = iter.next().value;
                assertThat(tech.getMatchCount()).isEqualTo(1L);
                assertThat(tech.getMarketSymbols()).containsExactly("AAPL");
                assertThat(tech.getMaxAbsMarketChange()).isEqualTo(-5.2);
            }
        }

        @Test
        @DisplayName("Risk level tracks the worst across all matches")
        void worstRiskLevelTracked() {
            Instant base = Instant.parse("2026-05-04T10:00:00Z");

            correlatedAlertsTopic.pipeInput("Energy/Commodities",
                    makeCorrelated("Energy/Commodities", "XOM", 4.1, "MEDIUM",
                            "oil prices", 1, "LOW", base),
                    base);
            correlatedAlertsTopic.pipeInput("Energy/Commodities",
                    makeCorrelated("Energy/Commodities", "CL=F", 8.3, "CRITICAL",
                            "Iran oil strike", 1, "HIGH", base.plusSeconds(5)),
                    base.plusSeconds(5));
            correlatedAlertsTopic.pipeInput("Energy/Commodities",
                    makeCorrelated("Energy/Commodities", "XOM", 2.0, "LOW",
                            "oil prices", 1, "LOW", base.plusSeconds(10)),
                    base.plusSeconds(10));

            // Advance past window
            correlatedAlertsTopic.pipeInput("Energy/Commodities",
                    makeCorrelated("Energy/Commodities", "CL=F", 1.0, "LOW",
                            "dummy", 1, "LOW", base.plusSeconds(91)),
                    base.plusSeconds(91));

            WindowStore<String, CorrelatedAlertSummary> store =
                    testDriver.getWindowStore("correlated-alerts-summary");

            try (var iter = store.fetch("Energy/Commodities",
                    base.minusSeconds(1), base.plusSeconds(61))) {
                assertThat(iter.hasNext()).isTrue();
                CorrelatedAlertSummary summary = iter.next().value;
                assertThat(summary.getMaxRiskLevel()).isEqualTo("CRITICAL");
                assertThat(summary.getMatchCount()).isEqualTo(3L);
            }
        }

        @Test
        @DisplayName("Duplicate symbols/topics are not repeated in the lists")
        void deduplicatesSymbolsAndTopics() {
            Instant base = Instant.parse("2026-05-04T10:00:00Z");

            // 5 alerts all with CL=F + "oil prices" — simulates burst duplicates
            for (int i = 0; i < 5; i++) {
                correlatedAlertsTopic.pipeInput("Energy/Commodities",
                        makeCorrelated("Energy/Commodities", "CL=F", 8.3, "CRITICAL",
                                "oil prices", 1, "LOW", base.plusSeconds(i)),
                        base.plusSeconds(i));
            }

            // Advance past window
            correlatedAlertsTopic.pipeInput("Energy/Commodities",
                    makeCorrelated("Energy/Commodities", "CL=F", 1.0, "LOW",
                            "dummy", 1, "LOW", base.plusSeconds(91)),
                    base.plusSeconds(91));

            WindowStore<String, CorrelatedAlertSummary> store =
                    testDriver.getWindowStore("correlated-alerts-summary");

            try (var iter = store.fetch("Energy/Commodities",
                    base.minusSeconds(1), base.plusSeconds(61))) {
                assertThat(iter.hasNext()).isTrue();
                CorrelatedAlertSummary summary = iter.next().value;
                assertThat(summary.getMatchCount()).isEqualTo(5L);
                assertThat(summary.getMarketSymbols()).containsExactly("CL=F");
                assertThat(summary.getSocialTopics()).containsExactly("oil prices");
            }
        }
    }

    // ===== Suppress output topic tests =====

    @Nested
    @DisplayName("Suppress outputs to final topics only after window close")
    class SuppressOutputTest {

        @Test
        @DisplayName("signup-counts-final receives records only after window closes")
        void signupCountsFinalEmitsOnWindowClose() {
            var outputTopic = testDriver.createOutputTopic(
                    "signup-counts-final",
                    Serdes.String().deserializer(),
                    Serdes.Long().deserializer());

            Instant base = Instant.parse("2026-05-04T10:00:00Z");

            // Pipe 3 FREE signups within the same 1-min window
            for (int i = 0; i < 3; i++) {
                NormalizedSignup s = new NormalizedSignup();
                s.setUserId("u" + i);
                s.setName("User " + i);
                s.setEmail("u" + i + "@test.com");
                s.setTier(Tier.FREE);
                s.setEventType("SignupRequestedEvent");
                s.setTimestamp(base.plusSeconds(i * 10));
                signupsNormalizedTopic.pipeInput("k" + i, s, base.plusSeconds(i * 10));
            }

            // Before window closes: output topic should be empty
            assertThat(outputTopic.isEmpty())
                    .as("suppress should hold results until window closes")
                    .isTrue();

            // Advance stream time past window + grace (1min + 30s)
            NormalizedSignup flush = new NormalizedSignup();
            flush.setUserId("flush");
            flush.setName("Flush");
            flush.setEmail("flush@test.com");
            flush.setTier(Tier.FREE);
            flush.setEventType("SignupRequestedEvent");
            flush.setTimestamp(base.plusSeconds(91));
            signupsNormalizedTopic.pipeInput("flush", flush, base.plusSeconds(91));

            // Now the first window should have emitted
            assertThat(outputTopic.isEmpty())
                    .as("suppress should emit after window closes")
                    .isFalse();

            var record = outputTopic.readRecord();
            // Key format: "TIER|windowStart|windowEnd"
            assertThat(record.key()).startsWith("FREE|");
            // 3 signups in the first window (flush is in the next window)
            assertThat(record.value()).isEqualTo(3L);
        }

        @Test
        @DisplayName("correlated-alerts-summary-final receives records only after window closes")
        void correlatedSummaryFinalEmitsOnWindowClose() {
            var outputTopic = testDriver.createOutputTopic(
                    "correlated-alerts-summary-final",
                    Serdes.String().deserializer(),
                    AvroSerdes.CorrelatedAlertSummary().deserializer());

            Instant base = Instant.parse("2026-05-04T10:00:00Z");

            // 2 correlated alerts in Energy sector within the same 1-min window
            CorrelatedAlert a1 = new CorrelatedAlert();
            a1.setSector("Energy/Commodities");
            a1.setMarketSymbol("CL=F");
            a1.setMarketAlertType("PRICE_SPIKE");
            a1.setMarketRiskLevel("CRITICAL");
            a1.setMarketChangePercent(8.3);
            a1.setSocialTopic("Iran oil strike");
            a1.setSocialPostCount(1);
            a1.setSocialRiskLevel("LOW");
            a1.setCorrelationTimestamp(base);

            CorrelatedAlert a2 = new CorrelatedAlert();
            a2.setSector("Energy/Commodities");
            a2.setMarketSymbol("XOM");
            a2.setMarketAlertType("PRICE_SPIKE");
            a2.setMarketRiskLevel("MEDIUM");
            a2.setMarketChangePercent(4.1);
            a2.setSocialTopic("oil prices");
            a2.setSocialPostCount(1);
            a2.setSocialRiskLevel("LOW");
            a2.setCorrelationTimestamp(base.plusSeconds(5));

            correlatedAlertsTopic.pipeInput("Energy/Commodities", a1, base);
            correlatedAlertsTopic.pipeInput("Energy/Commodities", a2, base.plusSeconds(5));

            // Before window closes: empty
            assertThat(outputTopic.isEmpty())
                    .as("suppress should hold summary until window closes")
                    .isTrue();

            // Advance past window + grace
            CorrelatedAlert flush = new CorrelatedAlert();
            flush.setSector("Energy/Commodities");
            flush.setMarketSymbol("CL=F");
            flush.setMarketAlertType("PRICE_SPIKE");
            flush.setMarketRiskLevel("LOW");
            flush.setMarketChangePercent(1.0);
            flush.setSocialTopic("dummy");
            flush.setSocialPostCount(1);
            flush.setSocialRiskLevel("LOW");
            flush.setCorrelationTimestamp(base.plusSeconds(91));
            correlatedAlertsTopic.pipeInput("Energy/Commodities", flush, base.plusSeconds(91));

            // Now summary should have emitted
            assertThat(outputTopic.isEmpty())
                    .as("suppress should emit summary after window closes")
                    .isFalse();

            var record = outputTopic.readRecord();
            CorrelatedAlertSummary summary = record.value();
            assertThat(summary.getSector()).isEqualTo("Energy/Commodities");
            assertThat(summary.getMatchCount()).isEqualTo(2L);
            assertThat(summary.getMarketSymbols()).containsExactlyInAnyOrder("CL=F", "XOM");
            assertThat(summary.getWindowStart()).isNotNull();
            assertThat(summary.getWindowEnd()).isNotNull();
        }
    }
}
