// Simulates sending emails by logging formatted notifications to console.
// In a real system these methods would call an email API (e.g. SendGrid, AWS SES).
// For now exercise, just print to the console

package ch.unisg.worldpulse.notification.application;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

@Component
public class NotificationService {

  /**
   * called when a new user signs up (SignupRequestedEvent).
   * this is the first notification the user would receive (a welcome message)
   */
  public void notifySignup(String traceid, JsonNode data) {
    String name = data.get("name").asText();
    String email = data.get("email").asText();
    String tier = data.get("tier").asText();

    System.out.println("========================================");
    System.out.println("[EMAIL] Welcome to WorldPulse!");
    System.out.println("  To: " + email);
    System.out.println("  Dear " + name + ",");
    System.out.println("  Your " + tier + " signup is being processed.");
    System.out.println("  Trace: " + traceid);
    System.out.println("========================================");
  }

  /**
   * called when payment succeeds (PaymentReceivedEvent)
   * the subscription is now active
   */
  public void notifyPaymentReceived(String traceid, JsonNode data) {
    String name = data.get("name").asText();
    String email = data.get("email").asText();
    String tier = data.get("tier").asText();

    System.out.println("========================================");
    System.out.println("[EMAIL] Payment Confirmed!");
    System.out.println("  To: " + email);
    System.out.println("  Dear " + name + ",");
    System.out.println("  Your " + tier + " subscription is now active.");
    System.out.println("  Trace: " + traceid);
    System.out.println("========================================");
  }

  /**
   * called when payment fails (PaymentFailedEvent)
   * ~20% of the time for paid tiers (simulated in PaymentService)
   */
  public void notifyPaymentFailed(String traceid, JsonNode data) {
    String name = data.get("name").asText();
    String email = data.get("email").asText();
    String tier = data.get("tier").asText();

    System.out.println("========================================");
    System.out.println("[EMAIL] Payment Failed");
    System.out.println("  To: " + email);
    System.out.println("  Dear " + name + ",");
    System.out.println("  We could not process payment for " + tier + " tier.");
    System.out.println("  Please try again or contact support.");
    System.out.println("  Trace: " + traceid);
    System.out.println("========================================");
  }
}
