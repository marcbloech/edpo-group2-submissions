package ch.unisg.worldpulse.payment.workers;

import java.util.HashMap;
import java.util.Map;

import ch.unisg.worldpulse.payment.application.PaymentService;
import ch.unisg.worldpulse.payment.messages.Message;
import ch.unisg.worldpulse.payment.messages.MessageSender;
import io.camunda.zeebe.client.api.response.ActivatedJob;
import io.camunda.zeebe.spring.client.annotation.JobWorker;
import io.camunda.zeebe.spring.common.exception.ZeebeBpmnError;
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
    String email = getString(vars, "email");
    String name = getString(vars, "name");
    String traceid = getString(vars, "traceid");
    boolean forcePaymentFailure = Boolean.TRUE.equals(vars.get("forcePaymentFailure"));
    boolean forcePaymentError = Boolean.TRUE.equals(vars.get("forcePaymentError"));

    if (forcePaymentError) {
      LOG.info("Payment: FORCED ERROR for {} tier (user: {}, traceid: {})", tier, userId, traceid);
      throw new ZeebeBpmnError("PAYMENT_ERROR", "Forced payment processing error for demo", Map.of());
    }

    PaymentService.PaymentResult payment;
    if (forcePaymentFailure) {
      long amount = "ENTERPRISE".equalsIgnoreCase(tier) ? 9900 : "PRO".equalsIgnoreCase(tier) ? 1900 : 0;
      payment = new PaymentService.PaymentResult(false, amount, null);
      LOG.info("Payment: FORCED FAILURE for {} tier (user: {}, traceid: {})", tier, userId, traceid);
    } else {
      payment = paymentService.processPayment(userId, tier);
    }

    publishPaymentEvent(payment, userId, tier, email, name, traceid);

    Map<String, Object> result = new HashMap<>();
    result.put("paymentSuccess", payment.success());
    result.put("paymentAmount", payment.amount());
    result.put("transactionId", payment.transactionId());
    return result;
  }

  private void publishPaymentEvent(PaymentService.PaymentResult payment, String userId,
                                   String tier, String email, String name, String traceid) {
    Map<String, Object> payload = new HashMap<>();
    payload.put("userId", userId);
    payload.put("email", email);
    payload.put("name", name);
    payload.put("tier", tier);
    payload.put("paymentAmount", payment.amount());
    payload.put("transactionId", payment.transactionId());

    String eventType = payment.success() ? "PaymentReceivedEvent" : "PaymentFailedEvent";

    try {
      messageSender.send(new Message<>(eventType, traceid, payload));
      LOG.info("Published {} to Kafka (traceid={}, tier={})", eventType, traceid, tier);
    } catch (Exception e) {
      // Non-blocking: Kafka publish failure must not break the Zeebe job.
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
}
