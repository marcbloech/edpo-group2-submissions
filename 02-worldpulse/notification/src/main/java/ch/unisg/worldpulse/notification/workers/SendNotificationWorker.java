package ch.unisg.worldpulse.notification.workers;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import ch.unisg.worldpulse.notification.application.NotificationService;
import ch.unisg.worldpulse.notification.messages.Message;
import ch.unisg.worldpulse.notification.messages.MessageSender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.zeebe.client.api.response.ActivatedJob;
import io.camunda.zeebe.spring.client.annotation.JobWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class SendNotificationWorker {

  private static final Logger LOG = LoggerFactory.getLogger(SendNotificationWorker.class);

  private final NotificationService notificationService;
  private final ObjectMapper objectMapper;
  private final MessageSender messageSender;

  public SendNotificationWorker(NotificationService notificationService,
                                ObjectMapper objectMapper,
                                MessageSender messageSender) {
    this.notificationService = notificationService;
    this.objectMapper = objectMapper;
    this.messageSender = messageSender;
  }

  @JobWorker(type = "send-notification")
  public Map<String, Object> handle(ActivatedJob job) {
    Map<String, Object> vars = job.getVariablesAsMap();
    String notificationType = getString(vars, "notificationType");
    String traceid = getString(vars, "traceid");
    String email = getString(vars, "email");
    String name = getString(vars, "name");
    String tier = getString(vars, "tier");

    Map<String, Object> dataMap = new HashMap<>();
    dataMap.put("name", name);
    dataMap.put("email", email);
    dataMap.put("tier", tier);
    copyIfPresent(vars, dataMap, "paymentAmount");
    copyIfPresent(vars, dataMap, "transactionId");
    copyIfPresent(vars, dataMap, "refundSuccess");
    copyIfPresent(vars, dataMap, "refundSkipped");
    copyIfPresent(vars, dataMap, "refundTransactionId");
    copyIfPresent(vars, dataMap, "currentTier");
    copyIfPresent(vars, dataMap, "targetTier");
    copyIfPresent(vars, dataMap, "reason");
    copyIfPresent(vars, dataMap, "deactivatedAt");
    copyIfPresent(vars, dataMap, "upgradedAt");
    copyIfPresent(vars, dataMap, "upgradeApplied");

    JsonNode data = objectMapper.valueToTree(dataMap);

    try {
      if ("SignupRequestedEvent".equals(notificationType)) {
        notificationService.notifySignup(traceid, data);
      } else if ("PaymentReceivedEvent".equals(notificationType)) {
        notificationService.notifyPaymentReceived(traceid, data);
      } else if ("PaymentFailedEvent".equals(notificationType)) {
        notificationService.notifyPaymentFailed(traceid, data);
      } else if ("TechnicalError".equals(notificationType)) {
        notificationService.notifyTechnicalError(traceid, data);
      } else if ("SignupValidationFailedEvent".equals(notificationType)) {
        notificationService.notifySignupValidationFailed(traceid, data);
      } else if ("AccountActivationFailedEvent".equals(notificationType)) {
        notificationService.notifyAccountActivationFailed(traceid, data);
      } else if ("UpgradeRequestedEvent".equals(notificationType)) {
        notificationService.notifyUpgradeRequested(traceid, data);
      } else if ("UpgradeCompletedEvent".equals(notificationType)) {
        notificationService.notifyUpgradeCompleted(traceid, data);
      } else if ("UpgradePaymentFailedEvent".equals(notificationType)) {
        notificationService.notifyUpgradePaymentFailed(traceid, data);
      } else if ("AccountDeactivationRequestedEvent".equals(notificationType)) {
        notificationService.notifyAccountDeactivationRequested(traceid, data);
      } else if ("AccountDeactivatedEvent".equals(notificationType)) {
        notificationService.notifyAccountDeactivated(traceid, data);
      } else {
        LOG.warn("Unknown notificationType '{}', using safe fallback (traceid={})", notificationType, traceid);
        return Map.of(
            "notificationSent", false,
            "notificationFallbackUsed", true,
            "notificationError", "unknown-notification-type",
            "notificationId", ""
        );
      }

      String notificationId = UUID.randomUUID().toString();

      // Publish outcome to Kafka so downstream consumers (audit, analytics)
      // can react to notification activity via choreography.
      publishNotificationEvent(notificationType, notificationId, traceid, email, name, tier, true);

      return Map.of(
          "notificationSent", true,
          "notificationFallbackUsed", false,
          "notificationError", "",
          "notificationId", notificationId
      );
    } catch (Exception ex) {
      LOG.error("Notification failed, using non-blocking fallback (traceid={})", traceid, ex);

      publishNotificationEvent(notificationType, "", traceid, email, name, tier, false);

      return Map.of(
          "notificationSent", false,
          "notificationFallbackUsed", true,
          "notificationError", "notification-dispatch-failed",
          "notificationId", ""
      );
    }
  }

  private void publishNotificationEvent(String notificationType, String notificationId,
                                        String traceid, String email, String name,
                                        String tier, boolean success) {
    Map<String, Object> payload = new HashMap<>();
    payload.put("notificationType", notificationType);
    payload.put("notificationId", notificationId);
    payload.put("email", email);
    payload.put("name", name);
    payload.put("tier", tier);
    payload.put("success", success);

    String eventType = success ? "NotificationSentEvent" : "NotificationFailedEvent";

    try {
      messageSender.send(new Message<>(eventType, traceid, payload));
      LOG.info("Published {} to Kafka (traceid={}, type={})", eventType, traceid, notificationType);
    } catch (Exception e) {
      LOG.warn("Failed to publish {} to Kafka (traceid={}): {}", eventType, traceid, e.getMessage());
    }
  }

  private String getString(Map<String, Object> vars, String key) {
    Object value = vars.get(key);
    if (value == null) {
      LOG.warn("Missing process variable '{}' — check BPMN I/O mappings", key);
      return "";
    }
    return value.toString();
  }

  private void copyIfPresent(Map<String, Object> vars, Map<String, Object> target, String key) {
    if (vars.containsKey(key) && vars.get(key) != null) {
      target.put(key, vars.get(key));
    }
  }
}
