package ch.unisg.worldpulse.signup.workers;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.zeebe.client.api.response.ActivatedJob;
import io.camunda.zeebe.spring.client.annotation.JobWorker;
import io.camunda.zeebe.spring.common.exception.ZeebeBpmnError;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class ActivateAccountWorker {

  private static final Logger LOG = LoggerFactory.getLogger(ActivateAccountWorker.class);
  private static final String TOPIC_NAME = "worldpulse";

  private final KafkaTemplate<String, String> kafkaTemplate;
  private final ObjectMapper objectMapper;

  public ActivateAccountWorker(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
    this.kafkaTemplate = kafkaTemplate;
    this.objectMapper = objectMapper;
  }

  @JobWorker(type = "activate-account")
  public Map<String, Object> handle(ActivatedJob job) {
    Map<String, Object> vars = job.getVariablesAsMap();
    String userId = getString(vars, "userId");
    String email = getString(vars, "email");
    String name = getString(vars, "name");
    String tier = getString(vars, "tier");
    String traceid = getString(vars, "traceid");
    boolean forceActivationFailure = getBoolean(vars, "forceActivationFailure");

    if (forceActivationFailure) {
      LOG.warn("Activation failed by scenario flag (traceid={}, userId={})", traceid, userId);
      throw new ZeebeBpmnError(
          "ACTIVATION_ERROR",
          "Forced activation failure for saga compensation path",
          Map.of("activationFailureReason", "forced-by-request"));
    }

    String activatedAt = Instant.now().toString();
    String apiToken = UUID.randomUUID().toString();

    // Publish AccountActivatedEvent to Kafka so downstream consumers
    // (analytics, welcome flows, audit) can react via choreography.
    Map<String, Object> payload = new HashMap<>();
    payload.put("userId", userId);
    payload.put("email", email);
    payload.put("name", name);
    payload.put("tier", tier);
    payload.put("activatedAt", activatedAt);

    publishAccountActivatedEvent(payload, traceid, userId);

    return Map.of(
        "accountActive", true,
        "activatedAt", activatedAt,
        "apiToken", apiToken
    );
  }

  private String getString(Map<String, Object> vars, String key) {
    Object value = vars.get(key);
    if (value == null) {
      LOG.warn("Missing process variable '{}' — check BPMN I/O mappings", key);
      return "";
    }
    return value.toString();
  }

  private boolean getBoolean(Map<String, Object> vars, String key) {
    Object value = vars.get(key);
    if (value instanceof Boolean boolValue) {
      return boolValue;
    }
    return value != null && Boolean.parseBoolean(value.toString());
  }

  private void publishAccountActivatedEvent(Map<String, Object> payload, String traceid, String userId) {
    try {
      Map<String, Object> event = new HashMap<>();
      event.put("type", "AccountActivatedEvent");
      event.put("id", UUID.randomUUID().toString());
      event.put("source", "WorldPulse-Signup");
      event.put("time", Instant.now().toString());
      event.put("data", payload);
      event.put("datacontenttype", "application/json");
      event.put("specversion", "1.0");
      event.put("traceid", traceid);
      event.put("correlationid", null);
      event.put("group", "worldpulse");

      String json = objectMapper.writeValueAsString(event);
      ProducerRecord<String, String> record = new ProducerRecord<>(TOPIC_NAME, json);
      record.headers().add("type", "AccountActivatedEvent".getBytes(StandardCharsets.UTF_8));
      kafkaTemplate.send(record);

      LOG.info("Published AccountActivatedEvent to Kafka (traceid={}, userId={})", traceid, userId);
    } catch (Exception e) {
      LOG.warn("Failed to publish AccountActivatedEvent to Kafka (traceid={}): {}", traceid, e.getMessage());
    }
  }
}
