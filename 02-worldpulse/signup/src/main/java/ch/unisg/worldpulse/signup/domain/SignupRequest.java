package ch.unisg.worldpulse.signup.domain;

/**
 * Data object representing a signup request
 * -> becomes the "data" field inside CloudEvents Message when published to Kafka
 *
 * Example JSON from a curl request:
 *   {"name":"Marc", "email":"marc@gmail.com", "tier":"PRO"}
 */
public class SignupRequest {

  private String userId;     // auto-generated UUID if not provided
  private String email;
  private String name;
  private String tier;       // subscription tier: FREE, PRO, or ENTERPRISE
  private boolean forceActivationFailure; // test switch for saga compensation path
  private boolean forcePaymentFailure;   // test switch for payment failure path
  private boolean forcePaymentError;     // test switch for payment processing error (boundary event)

  public String getUserId() { return userId; }
  public void setUserId(String userId) { this.userId = userId; }
  public String getEmail() { return email; }
  public void setEmail(String email) { this.email = email; }
  public String getName() { return name; }
  public void setName(String name) { this.name = name; }
  public String getTier() { return tier; }
  public void setTier(String tier) { this.tier = tier; }
  public boolean isForceActivationFailure() { return forceActivationFailure; }
  public void setForceActivationFailure(boolean forceActivationFailure) { this.forceActivationFailure = forceActivationFailure; }
  public boolean isForcePaymentFailure() { return forcePaymentFailure; }
  public void setForcePaymentFailure(boolean forcePaymentFailure) { this.forcePaymentFailure = forcePaymentFailure; }
  public boolean isForcePaymentError() { return forcePaymentError; }
  public void setForcePaymentError(boolean forcePaymentError) { this.forcePaymentError = forcePaymentError; }

  @Override
  public String toString() {
    return "SignupRequest [name=" + name + ", email=" + email + ", tier=" + tier
        + ", forceActivationFailure=" + forceActivationFailure
        + ", forcePaymentFailure=" + forcePaymentFailure
        + ", forcePaymentError=" + forcePaymentError + "]";
  }
}
