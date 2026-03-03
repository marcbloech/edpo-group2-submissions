// Adapted from Lab 4: kafka/java/choreography-alternative/payment/application/PaymentService.java

package ch.unisg.worldpulse.payment.application;

import org.springframework.stereotype.Component;

/**
 * Business logic for payment processing.
 *
 * In a real app this would call Stripe/PayPal/etc
 * --> demonstrate PaymentReceivedEvent and PaymentFailedEvent in the logs
 */
@Component
public class PaymentService {

  /**
   * Simulates processing a payment for the given user and tier.
   * @return true if payment succeeded, false if failed
   */
  public boolean processPayment(String userId, String tier) {
    long amount = getAmountForTier(tier);

    if (amount == 0) {
      System.out.println("Payment: FREE tier for user " + userId + " — no charge needed");
      return true;
    }

    boolean success = Math.random() < 0.8; // Simulate 80% success rate for paid tiers

    if (success) {
      System.out.println("Payment: Charged " + amount + " cents for " + tier + " tier (user: " + userId + ")");
    } else {
      System.out.println("Payment: FAILED for " + tier + " tier, amount " + amount + " cents (user: " + userId + ")");
    }

    return success;
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
