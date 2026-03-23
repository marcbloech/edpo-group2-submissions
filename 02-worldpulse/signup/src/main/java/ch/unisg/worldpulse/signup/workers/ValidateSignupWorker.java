package ch.unisg.worldpulse.signup.workers;

import java.util.Map;
import java.util.UUID;

import io.camunda.zeebe.client.api.response.ActivatedJob;
import io.camunda.zeebe.spring.client.annotation.JobWorker;
import org.springframework.stereotype.Component;

@Component
public class ValidateSignupWorker {

  @JobWorker(type = "validate-signup")
  public Map<String, Object> handle(ActivatedJob job) {
    String traceid = getString(job.getVariablesAsMap(), "traceid");
    if (traceid.isBlank()) {
      traceid = UUID.randomUUID().toString();
    }

    return Map.of(
        "userId", UUID.randomUUID().toString(),
        "traceid", traceid,
        "source", "WorldPulse-Signup"
    );
  }

  private String getString(Map<String, Object> vars, String key) {
    Object value = vars.get(key);
    return value == null ? "" : value.toString();
  }
}
