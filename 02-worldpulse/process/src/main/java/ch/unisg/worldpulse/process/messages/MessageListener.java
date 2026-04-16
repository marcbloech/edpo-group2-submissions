package ch.unisg.worldpulse.process.messages;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.camunda.zeebe.client.ZeebeClient;

@Component
public class MessageListener {

    private static final Logger LOG = LoggerFactory.getLogger(MessageListener.class);
    private static final String TOPIC_NAME = "worldpulse";
    private static final String SIGNUP_REQUESTED_EVENT = "SignupRequestedEvent";
    private static final String UPGRADE_REQUESTED_EVENT = "UpgradeRequestedEvent";
    private static final String DEACTIVATION_REQUESTED_EVENT = "AccountDeactivationRequestedEvent";
    private static final String SIGNUP_PROCESS_ID = "signup-process";
    private static final String UPGRADE_PROCESS_ID = "upgrade-process";
    private static final String DEACTIVATION_PROCESS_ID = "deactivation-process";
    private static final int MAX_PROCESS_START_ATTEMPTS = 3;
    private static final Duration RETRY_DELAY = Duration.ofMillis(500);

    private final ObjectMapper objectMapper;
    private final ZeebeClient zeebeClient;
    private final ProcessDeadLetterPublisher deadLetterPublisher;

    public MessageListener(
            ObjectMapper objectMapper,
            ZeebeClient zeebeClient,
            ProcessDeadLetterPublisher deadLetterPublisher) {
        this.objectMapper = objectMapper;
        this.zeebeClient = zeebeClient;
        this.deadLetterPublisher = deadLetterPublisher;
    }

    @KafkaListener(id = "worldpulse-process", topics = TOPIC_NAME)
    public void handleEvent(String messageJson, @Header("type") String messageType) throws Exception {
        if (!isSupportedIngressEvent(messageType)) {
            return;
        }

        JsonNode message = objectMapper.readTree(messageJson);
        JsonNode data = message.get("data");
        String traceid = getString(message, "traceid");
        if (traceid.isBlank()) {
            traceid = UUID.randomUUID().toString();
        }

        String processId = resolveProcessId(messageType);
        Map<String, Object> variables = extractVariables(messageType, data, traceid);
        String name = String.valueOf(variables.getOrDefault("name", "unknown"));

        LOG.info("Received {}, starting {} for '{}' (traceid={})", messageType, processId, name, traceid);

        Exception lastException = null;
        for (int attempt = 1; attempt <= MAX_PROCESS_START_ATTEMPTS; attempt++) {
            try {
                zeebeClient.newCreateInstanceCommand()
                        .bpmnProcessId(processId)
                        .latestVersion()
                        .variables(variables)
                        .send()
                        .join();
                return;
            } catch (Exception ex) {
                lastException = ex;
                LOG.warn(
                        "Process start attempt {}/{} failed (traceid={}): {}",
                        attempt,
                        MAX_PROCESS_START_ATTEMPTS,
                        traceid,
                        ex.getMessage());
                Thread.sleep(RETRY_DELAY.toMillis());
            }
        }

        LOG.error("Process start failed after {} attempts (event={}, traceid={})", MAX_PROCESS_START_ATTEMPTS, messageType, traceid, lastException);
        deadLetterPublisher.publishProcessStartFailure(messageJson, messageType, traceid, lastException == null ? "unknown" : lastException.getMessage());
    }

    private boolean isSupportedIngressEvent(String messageType) {
        return SIGNUP_REQUESTED_EVENT.equals(messageType)
                || UPGRADE_REQUESTED_EVENT.equals(messageType)
                || DEACTIVATION_REQUESTED_EVENT.equals(messageType);
    }

    private String resolveProcessId(String messageType) {
        if (SIGNUP_REQUESTED_EVENT.equals(messageType)) {
            return SIGNUP_PROCESS_ID;
        }
        if (UPGRADE_REQUESTED_EVENT.equals(messageType)) {
            return UPGRADE_PROCESS_ID;
        }
        return DEACTIVATION_PROCESS_ID;
    }

    private Map<String, Object> extractVariables(String messageType, JsonNode data, String traceid) {
        Map<String, Object> variables = new HashMap<>();
        String name = getString(data, "name");
        String email = getString(data, "email");
        String userId = getString(data, "userId");
        if (userId.isBlank()) {
            userId = UUID.randomUUID().toString();
        }

        variables.put("userId", userId);
        variables.put("name", name);
        variables.put("email", email);
        variables.put("traceid", traceid);

        if (SIGNUP_REQUESTED_EVENT.equals(messageType)) {
            variables.put("tier", getString(data, "tier"));
            variables.put("forceActivationFailure", getBoolean(data, "forceActivationFailure"));
            variables.put("forcePaymentFailure", getBoolean(data, "forcePaymentFailure"));
            variables.put("forcePaymentError", getBoolean(data, "forcePaymentError"));
            return variables;
        }

        if (UPGRADE_REQUESTED_EVENT.equals(messageType)) {
            String currentTier = getString(data, "currentTier");
            String targetTier = getString(data, "targetTier");
            variables.put("currentTier", currentTier);
            variables.put("targetTier", targetTier);
            variables.put("tier", targetTier);
            return variables;
        }

        variables.put("tier", getString(data, "tier"));
        variables.put("reason", getString(data, "reason"));
        return variables;
    }

    private String getString(JsonNode node, String key) {
        if (node == null || !node.has(key) || node.get(key).isNull()) {
            return "";
        }
        return node.get(key).asText("");
    }

    private boolean getBoolean(JsonNode node, String key) {
        return node != null && node.has(key) && node.get(key).asBoolean(false);
    }
}
