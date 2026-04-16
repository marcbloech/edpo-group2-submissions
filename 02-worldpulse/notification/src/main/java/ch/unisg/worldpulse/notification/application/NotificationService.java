// Simulates sending emails by logging formatted notifications.
// In a real system these methods would call an email API (e.g. SendGrid, AWS SES).

package ch.unisg.worldpulse.notification.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

@Component
public class NotificationService {

  private static final Logger LOG = LoggerFactory.getLogger(NotificationService.class);

  public void notifySignup(String traceid, JsonNode data) {
    String name = data.get("name").asText();
    String email = data.get("email").asText();
    String tier = data.get("tier").asText();

    LOG.info("[EMAIL] Welcome to WorldPulse! | To: {} | Dear {}, your {} signup is being processed. | Trace: {}",
        email, name, tier, traceid);
  }

  public void notifyPaymentReceived(String traceid, JsonNode data) {
    String name = data.get("name").asText();
    String email = data.get("email").asText();
    String tier = data.get("tier").asText();

    LOG.info("[EMAIL] Payment Confirmed! | To: {} | Dear {}, your {} subscription is now active. | Trace: {}",
        email, name, tier, traceid);
  }

  public void notifyPaymentFailed(String traceid, JsonNode data) {
    String name = data.get("name").asText();
    String email = data.get("email").asText();
    String tier = data.get("tier").asText();

    LOG.info("[EMAIL] Payment Failed | To: {} | Dear {}, we could not process payment for {} tier. Please try again or contact support. | Trace: {}",
        email, name, tier, traceid);
  }

  public void notifyTechnicalError(String traceid, JsonNode data) {
    String name = data.get("name").asText();
    String email = data.get("email").asText();
    String tier = data.get("tier").asText();

    LOG.info("[EMAIL] Temporary Technical Issue | To: {} | Dear {}, we hit a technical issue processing your {} signup. Our team has been notified. | Trace: {}",
        email, name, tier, traceid);
  }

  public void notifySignupValidationFailed(String traceid, JsonNode data) {
    String name = data.get("name").asText();
    String email = data.get("email").asText();
    String tier = data.get("tier").asText();
    String reason = data.has("validationError") ? data.get("validationError").asText("unknown") : "unknown";

    LOG.info("[EMAIL] Signup Validation Failed | To: {} | Dear {}, we could not validate your {} signup. Reason: {} | Trace: {}",
        email, name, tier, reason, traceid);
  }

  public void notifyAccountActivationFailed(String traceid, JsonNode data) {
    String name = data.get("name").asText();
    String email = data.get("email").asText();
    String tier = data.get("tier").asText();
    boolean refundSkipped = data.has("refundSkipped") && data.get("refundSkipped").asBoolean(false);
    boolean refundSuccess = data.has("refundSuccess") && data.get("refundSuccess").asBoolean(false);
    String refundTx = data.has("refundTransactionId") ? data.get("refundTransactionId").asText("") : "";

    String refundDetail;
    if (refundSkipped) {
      refundDetail = "No charge was captured, so no refund was needed.";
    } else if (refundSuccess) {
      refundDetail = "Your payment was refunded successfully. Refund Transaction: " + refundTx;
    } else {
      refundDetail = "Refund is pending manual support follow-up.";
    }

    LOG.info("[EMAIL] Account Activation Failed | To: {} | Dear {}, we could not activate your {} account. {} | Trace: {}",
        email, name, tier, refundDetail, traceid);
  }

  public void notifyPaymentRefunded(String traceid, JsonNode data) {
    String email = data.has("email") ? data.get("email").asText("unknown") : "unknown";
    String tier = data.has("tier") ? data.get("tier").asText("unknown") : "unknown";
    String refundTx = data.has("refundTransactionId") ? data.get("refundTransactionId").asText("") : "";

    LOG.info("[EMAIL] Refund Completed | To: {} | Your {} payment was refunded. Refund Transaction: {} | Trace: {}",
        email, tier, refundTx, traceid);
  }

  public void notifyPaymentRefundFailed(String traceid, JsonNode data) {
    String email = data.has("email") ? data.get("email").asText("unknown") : "unknown";
    String tier = data.has("tier") ? data.get("tier").asText("unknown") : "unknown";

    LOG.info("[EMAIL] Refund Pending Manual Follow-up | To: {} | Automatic refund for your {} payment failed. Support will follow up manually. | Trace: {}",
        email, tier, traceid);
  }

  public void notifyUpgradeRequested(String traceid, JsonNode data) {
    String name = data.has("name") ? data.get("name").asText("user") : "user";
    String email = data.has("email") ? data.get("email").asText("unknown") : "unknown";
    String currentTier = data.has("currentTier") ? data.get("currentTier").asText("UNKNOWN") : "UNKNOWN";
    String targetTier = data.has("targetTier") ? data.get("targetTier").asText("UNKNOWN") : "UNKNOWN";

    LOG.info("[EMAIL] Upgrade Requested | To: {} | Dear {}, upgrade request {} -> {} is being processed. | Trace: {}",
        email, name, currentTier, targetTier, traceid);
  }

  public void notifyUpgradeCompleted(String traceid, JsonNode data) {
    String name = data.has("name") ? data.get("name").asText("user") : "user";
    String email = data.has("email") ? data.get("email").asText("unknown") : "unknown";
    String currentTier = data.has("currentTier") ? data.get("currentTier").asText("UNKNOWN") : "UNKNOWN";
    String targetTier = data.has("targetTier") ? data.get("targetTier").asText("UNKNOWN") : "UNKNOWN";

    LOG.info("[EMAIL] Upgrade Completed | To: {} | Dear {}, your plan was upgraded from {} to {}. | Trace: {}",
        email, name, currentTier, targetTier, traceid);
  }

  public void notifyUpgradePaymentFailed(String traceid, JsonNode data) {
    String name = data.has("name") ? data.get("name").asText("user") : "user";
    String email = data.has("email") ? data.get("email").asText("unknown") : "unknown";
    String targetTier = data.has("targetTier") ? data.get("targetTier").asText("UNKNOWN") : "UNKNOWN";

    LOG.info("[EMAIL] Upgrade Payment Failed | To: {} | Dear {}, we could not process payment for {} upgrade. | Trace: {}",
        email, name, targetTier, traceid);
  }

  public void notifyAccountDeactivationRequested(String traceid, JsonNode data) {
    String name = data.has("name") ? data.get("name").asText("user") : "user";
    String email = data.has("email") ? data.get("email").asText("unknown") : "unknown";
    String reason = data.has("reason") ? data.get("reason").asText("not-provided") : "not-provided";

    LOG.info("[EMAIL] Deactivation Requested | To: {} | Dear {}, your deactivation request was received. Reason: {} | Trace: {}",
        email, name, reason, traceid);
  }

  public void notifyAccountDeactivated(String traceid, JsonNode data) {
    String name = data.has("name") ? data.get("name").asText("user") : "user";
    String email = data.has("email") ? data.get("email").asText("unknown") : "unknown";
    String deactivatedAt = data.has("deactivatedAt") ? data.get("deactivatedAt").asText("unknown-time") : "unknown-time";

    LOG.info("[EMAIL] Account Deactivated | To: {} | Dear {}, your account has been deactivated at {}. | Trace: {}",
        email, name, deactivatedAt, traceid);
  }

  /**
   * Called when the Market Scanner detects a significant price movement (MarketAlertEvent).
   */
  public void notifyMarketAlert(String traceid, JsonNode data) {
    String symbol = data.get("symbol").asText();
    String alertType = data.get("alertType").asText();
    double changePercent = data.get("changePercent").asDouble();
    String description = data.get("description").asText();

    LOG.info("[ALERT] Market Alert: {} | Symbol: {} | Change: {}% | {} | Trace: {}",
        alertType, symbol, changePercent, description, traceid);
  }

  /**
   * Called when the BlueSky Scanner detects a trending topic or sentiment spike (SocialTrendEvent).
   */
  public void notifySocialTrend(String traceid, JsonNode data) {
    String topic = data.get("topic").asText();
    String alertType = data.get("alertType").asText();
    int postCount = data.get("postCount").asInt();
    String sentiment = data.get("sentiment").asText();
    String description = data.get("description").asText();

    LOG.info("[ALERT] Social Trend: {} | Topic: {} | Posts: {} | Sentiment: {} | {} | Trace: {}",
        alertType, topic, postCount, sentiment, description, traceid);
  }
}
