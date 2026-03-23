package ch.unisg.worldpulse.signup.workers;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import ch.unisg.worldpulse.signup.messages.Message;
import ch.unisg.worldpulse.signup.messages.MessageSender;
import io.camunda.zeebe.client.api.response.ActivatedJob;
import io.camunda.zeebe.spring.client.annotation.JobWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ActivateAccountWorker {

  private static final Logger LOG = LoggerFactory.getLogger(ActivateAccountWorker.class);

  private final MessageSender messageSender;

  public ActivateAccountWorker(MessageSender messageSender) {
    this.messageSender = messageSender;
  }

  @JobWorker(type = "activate-account")
  public Map<String, Object> handle(ActivatedJob job) {
    Map<String, Object> vars = job.getVariablesAsMap();
    String userId = getString(vars, "userId");
    String email = getString(vars, "email");
    String name = getString(vars, "name");
    String tier = getString(vars, "tier");
    String traceid = getString(vars, "traceid");

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

    try {
      messageSender.send(new Message<>("AccountActivatedEvent", traceid, payload));
      LOG.info("Published AccountActivatedEvent to Kafka (traceid={}, userId={})", traceid, userId);
    } catch (Exception e) {
      LOG.warn("Failed to publish AccountActivatedEvent to Kafka (traceid={}): {}", traceid, e.getMessage());
    }

    return Map.of(
        "accountActive", true,
        "activatedAt", activatedAt,
        "apiToken", apiToken
    );
  }

  private String getString(Map<String, Object> vars, String key) {
    Object value = vars.get(key);
    return value == null ? "" : value.toString();
  }
}
