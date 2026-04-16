// Adapted from Lab 4: kafka/java/choreography-alternative/payment/application/PaymentService.java

package ch.unisg.worldpulse.payment.application;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Business logic for payment processing.
 *
 * In a real app this would call Stripe/PayPal/etc
 * --> demonstrate PaymentReceivedEvent and PaymentFailedEvent in the logs
 */
@Component
public class PaymentService {

  private static final Logger LOG = LoggerFactory.getLogger(PaymentService.class);

  public record PaymentResult(boolean success, long amount, String transactionId) {
  }

  public record RefundResult(boolean success, boolean skipped, String refundTransactionId) {
  }

  /**
   * Simulates processing a payment for the given user and tier.
   */
  public PaymentResult processPayment(String userId, String tier) {
    long amount = getAmountForTier(tier);

    if (amount == 0) {
      LOG.info("Payment: FREE tier for user {} — no charge needed", userId);
      return new PaymentResult(true, 0, null);
    }

    boolean success = Math.random() < 0.8; // Simulate 80% success rate for paid tiers
    String transactionId = success ? UUID.randomUUID().toString() : null;

    if (success) {
      LOG.info("Payment: Charged {} cents for {} tier (user: {})", amount, tier, userId);
    } else {
      LOG.info("Payment: FAILED for {} tier, amount {} cents (user: {})", tier, amount, userId);
    }

    return new PaymentResult(success, amount, transactionId);
  }

  /**
   * Simulates compensating a previous successful payment.
   */
  public RefundResult refundPayment(String userId, String tier, long paymentAmount, String transactionId) {
    if (paymentAmount <= 0 || transactionId == null || transactionId.isBlank()) {
      LOG.info("Refund: skipped for {} tier (user: {}) - no captured payment", tier, userId);
      return new RefundResult(true, true, "");
    }

    boolean success = Math.random() < 0.9; // simulate occasional refund provider failures
    if (success) {
      String refundTransactionId = UUID.randomUUID().toString();
      LOG.info("Refund: refunded {} cents for {} tier (user: {}, refundTx: {})", paymentAmount, tier, userId, refundTransactionId);
      return new RefundResult(true, false, refundTransactionId);
    }

    LOG.info("Refund: FAILED for {} tier (user: {}, originalTx: {})", tier, userId, transactionId);
    return new RefundResult(false, false, "");
  }

  // Maps tier name to price in cents (e.g. PRO = CHF 19.00 = 1900 cents)
  private long getAmountForTier(String tier) {
    return switch (tier.toUpperCase()) {
      case "PRO" -> 1900;
      case "ENTERPRISE" -> 9900;
      default -> 0;               // FREE tier
    };
  }
}
