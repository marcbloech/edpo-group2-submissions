package ch.unisg.worldpulse.signup.workers;

import java.util.Locale;
import java.util.Map;

import io.camunda.zeebe.client.api.response.ActivatedJob;
import io.camunda.zeebe.spring.client.annotation.JobWorker;
import org.springframework.stereotype.Component;

@Component
public class AssessPaymentEligibilityWorker {

  @JobWorker(type = "assess-payment-eligibility")
  public Map<String, Object> handle(ActivatedJob job) {
    String tier = getString(job.getVariablesAsMap(), "tier").toUpperCase(Locale.ROOT);
    boolean paymentRequired = "PRO".equals(tier) || "ENTERPRISE".equals(tier);
    return Map.of("paymentRequired", paymentRequired);
  }

  private String getString(Map<String, Object> vars, String key) {
    Object value = vars.get(key);
    return value == null ? "" : value.toString();
  }
}
