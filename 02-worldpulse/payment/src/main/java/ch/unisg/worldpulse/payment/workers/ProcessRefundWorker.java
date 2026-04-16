package ch.unisg.worldpulse.payment.workers;

import java.util.HashMap;
import java.util.Map;

import ch.unisg.worldpulse.payment.application.PaymentService;
import ch.unisg.worldpulse.payment.messages.Message;
import ch.unisg.worldpulse.payment.messages.MessageSender;
import io.camunda.zeebe.client.api.response.ActivatedJob;
import io.camunda.zeebe.spring.client.annotation.JobWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ProcessRefundWorker {

  private static final Logger LOG = LoggerFactory.getLogger(ProcessRefundWorker.class);

  private final PaymentService paymentService;
  private final MessageSender messageSender;

  public ProcessRefundWorker(PaymentService paymentService, MessageSender messageSender) {
    this.paymentService = paymentService;
    this.messageSender = messageSender;
  }

  @JobWorker(type = "refund-payment")
  public Map<String, Object> handle(ActivatedJob job) {
    Map<String, Object> vars = job.getVariablesAsMap();

    String userId = getString(vars, "userId");
    String tier = getString(vars, "tier");
    String traceid = getString(vars, "traceid");
    String email = getString(vars, "email");
    String name = getString(vars, "name");
    String transactionId = getString(vars, "transactionId");
    long paymentAmount = getLong(vars, "paymentAmount");

    PaymentService.RefundResult result =
        paymentService.refundPayment(userId, tier, paymentAmount, transactionId);

    publishRefundEvent(result, userId, tier, traceid, email, name, transactionId, paymentAmount);

    Map<String, Object> output = new HashMap<>();
    output.put("refundSuccess", result.success());
    output.put("refundSkipped", result.skipped());
    output.put("refundTransactionId", result.refundTransactionId());
    return output;
  }

  private void publishRefundEvent(PaymentService.RefundResult result,
                                  String userId,
                                  String tier,
                                  String traceid,
                                  String email,
                                  String name,
                                  String transactionId,
                                  long paymentAmount) {
    if (result.skipped()) {
      LOG.info("Refund skipped, no compensation event published (traceid={}, tier={})", traceid, tier);
      return;
    }

    String eventType = result.success() ? "PaymentRefundedEvent" : "PaymentRefundFailedEvent";

    Map<String, Object> payload = new HashMap<>();
    payload.put("userId", userId);
    payload.put("email", email);
    payload.put("name", name);
    payload.put("tier", tier);
    payload.put("paymentAmount", paymentAmount);
    payload.put("transactionId", transactionId);
    payload.put("refundTransactionId", result.refundTransactionId());
    payload.put("refundSuccess", result.success());

    try {
      messageSender.send(new Message<>(eventType, traceid, payload));
      LOG.info("Published {} to Kafka (traceid={}, tier={})", eventType, traceid, tier);
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

  private long getLong(Map<String, Object> vars, String key) {
    Object value = vars.get(key);
    if (value instanceof Number number) {
      return number.longValue();
    }
    if (value == null) {
      return 0L;
    }
    try {
      return Long.parseLong(value.toString());
    } catch (NumberFormatException ignored) {
      return 0L;
    }
  }
}
