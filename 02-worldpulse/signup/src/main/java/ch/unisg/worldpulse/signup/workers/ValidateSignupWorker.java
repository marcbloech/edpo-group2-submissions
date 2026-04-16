package ch.unisg.worldpulse.signup.workers;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import io.camunda.zeebe.client.api.response.ActivatedJob;
import io.camunda.zeebe.spring.client.annotation.JobWorker;
import io.camunda.zeebe.spring.common.exception.ZeebeBpmnError;
import org.springframework.stereotype.Component;

@Component
public class ValidateSignupWorker {

  @JobWorker(type = "validate-signup")
  public Map<String, Object> handle(ActivatedJob job) {
    Map<String, Object> vars = job.getVariablesAsMap();

    String name = getString(vars, "name");
    String email = getString(vars, "email");
    String tier = getString(vars, "tier").toUpperCase(Locale.ROOT);
    String traceid = getString(vars, "traceid");
    if (traceid.isBlank()) {
      traceid = UUID.randomUUID().toString();
    }

    if (name.isBlank()) {
      throw validationError("name-missing", traceid);
    }
    if (!email.contains("@") || email.startsWith("@") || email.endsWith("@")) {
      throw validationError("email-invalid", traceid);
    }
    if (!isSupportedTier(tier)) {
      throw validationError("tier-unsupported", traceid);
    }

    return Map.of(
        "userId", UUID.randomUUID().toString(),
        "traceid", traceid,
        "source", "WorldPulse-Signup",
        "tier", tier
    );
  }

  private ZeebeBpmnError validationError(String reason, String traceid) {
    return new ZeebeBpmnError(
        "SIGNUP_VALIDATION_ERROR",
        "Signup validation failed: " + reason,
        Map.of("validationError", reason, "traceid", traceid));
  }

  private boolean isSupportedTier(String tier) {
    return "FREE".equals(tier) || "PRO".equals(tier) || "ENTERPRISE".equals(tier);
  }

  private String getString(Map<String, Object> vars, String key) {
    Object value = vars.get(key);
    return value == null ? "" : value.toString();
  }
}
