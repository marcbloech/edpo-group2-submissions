package ch.unisg.worldpulse.signup.workers;

import java.time.Instant;
import java.util.Map;

import io.camunda.zeebe.client.api.response.ActivatedJob;
import io.camunda.zeebe.spring.client.annotation.JobWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class DeactivateAccountWorker {

  private static final Logger LOG = LoggerFactory.getLogger(DeactivateAccountWorker.class);

  @JobWorker(type = "deactivate-account")
  public Map<String, Object> handle(ActivatedJob job) {
    Map<String, Object> vars = job.getVariablesAsMap();

    String userId = getString(vars, "userId");
    String reason = getString(vars, "reason");
    String traceid = getString(vars, "traceid");
    String deactivatedAt = Instant.now().toString();

    LOG.info("Deactivated account (traceid={}, userId={}, reason={})", traceid, userId, reason);

    return Map.of(
        "accountActive", false,
        "deactivatedAt", deactivatedAt,
        "deactivationReason", reason
    );
  }

  private String getString(Map<String, Object> vars, String key) {
    Object value = vars.get(key);
    return value == null ? "" : value.toString();
  }
}
