package ch.unisg.worldpulse.signup.workers;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;

import io.camunda.zeebe.client.api.response.ActivatedJob;
import io.camunda.zeebe.spring.client.annotation.JobWorker;
import io.camunda.zeebe.spring.common.exception.ZeebeBpmnError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ApplyTierUpgradeWorker {

  private static final Logger LOG = LoggerFactory.getLogger(ApplyTierUpgradeWorker.class);

  @JobWorker(type = "apply-tier-upgrade")
  public Map<String, Object> handle(ActivatedJob job) {
    Map<String, Object> vars = job.getVariablesAsMap();
    String userId = getString(vars, "userId");
    String currentTier = getString(vars, "currentTier").toUpperCase(Locale.ROOT);
    String targetTier = getString(vars, "targetTier").toUpperCase(Locale.ROOT);
    String traceid = getString(vars, "traceid");

    if (!isSupportedPaidTier(targetTier)) {
      throw new ZeebeBpmnError("UPGRADE_ERROR", "Unsupported target tier", Map.of("targetTier", targetTier));
    }

    String upgradedAt = Instant.now().toString();
    LOG.info("Applied tier upgrade (traceid={}, userId={}, {} -> {})", traceid, userId, currentTier, targetTier);

    return Map.of(
        "upgradeApplied", true,
        "upgradedAt", upgradedAt,
        "tier", targetTier
    );
  }

  private boolean isSupportedPaidTier(String tier) {
    return "PRO".equals(tier) || "ENTERPRISE".equals(tier);
  }

  private String getString(Map<String, Object> vars, String key) {
    Object value = vars.get(key);
    return value == null ? "" : value.toString();
  }
}
