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

  public String getUserId() { return userId; }
  public void setUserId(String userId) { this.userId = userId; }
  public String getEmail() { return email; }
  public void setEmail(String email) { this.email = email; }
  public String getName() { return name; }
  public void setName(String name) { this.name = name; }
  public String getTier() { return tier; }
  public void setTier(String tier) { this.tier = tier; }

  @Override
  public String toString() {
    return "SignupRequest [name=" + name + ", email=" + email + ", tier=" + tier + "]";
  }
}
