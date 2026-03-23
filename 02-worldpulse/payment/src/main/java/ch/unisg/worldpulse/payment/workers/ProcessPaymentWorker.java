package ch.unisg.worldpulse.payment.workers;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import ch.unisg.worldpulse.payment.application.PaymentService;
import ch.unisg.worldpulse.payment.messages.Message;
import ch.unisg.worldpulse.payment.messages.MessageSender;
import io.camunda.zeebe.client.api.response.ActivatedJob;
import io.camunda.zeebe.spring.client.annotation.JobWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ProcessPaymentWorker {

  private static final Logger LOG = LoggerFactory.getLogger(ProcessPaymentWorker.class);

  private final PaymentService paymentService;
  private final MessageSender messageSender;

  public ProcessPaymentWorker(PaymentService paymentService, MessageSender messageSender) {
    this.paymentService = paymentService;
    this.messageSender = messageSender;
  }

  @JobWorker(type = "process-payment")
  public Map<String, Object> handle(ActivatedJob job) {
    Map<String, Object> vars = job.getVariablesAsMap();
    String userId = getString(vars, "userId");
    String tier = getString(vars, "tier");
    String traceid = getString(vars, "traceid");

    boolean success = paymentService.processPayment(userId, tier);

    long amount = getAmountForTier(tier);
    String transactionId = success ? UUID.randomUUID().toString() : null;

    // Publish business event to Kafka so downstream consumers can react
    // independently of the Zeebe orchestration flow.
    publishPaymentEvent(success, userId, tier, amount, transactionId, traceid);

    Map<String, Object> result = new HashMap<>();
    result.put("paymentSuccess", success);
    result.put("paymentAmount", amount);
    result.put("transactionId", transactionId);
    return result;
  }

  private void publishPaymentEvent(boolean success, String userId, String tier,
                                   long amount, String transactionId, String traceid) {
    Map<String, Object> payload = new HashMap<>();
    payload.put("userId", userId);
    payload.put("tier", tier);
    payload.put("paymentAmount", amount);
    payload.put("transactionId", transactionId);

    String eventType = success ? "PaymentReceivedEvent" : "PaymentFailedEvent";

    try {
      messageSender.send(new Message<>(eventType, traceid, payload));
      LOG.info("Published {} to Kafka (traceid={}, tier={})", eventType, traceid, tier);
    } catch (Exception e) {
      // Non-blocking: Kafka publish failure must not break the Zeebe job.
      LOG.warn("Failed to publish {} to Kafka (traceid={}): {}", eventType, traceid, e.getMessage());
    }
  }

  private long getAmountForTier(String tier) {
    String normalizedTier = tier.toUpperCase(Locale.ROOT);
    switch (normalizedTier) {
      case "PRO":
        return 1900;
      case "ENTERPRISE":
        return 9900;
      default:
        return 0;
    }
  }

  private String getString(Map<String, Object> vars, String key) {
    Object value = vars.get(key);
    return value == null ? "" : value.toString();
  }
}
