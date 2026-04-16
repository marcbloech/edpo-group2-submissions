package ch.unisg.worldpulse.signup.domain;

public class DeactivationRequest {

  private String userId;
  private String email;
  private String name;
  private String tier;
  private String reason;

  public String getUserId() { return userId; }
  public void setUserId(String userId) { this.userId = userId; }
  public String getEmail() { return email; }
  public void setEmail(String email) { this.email = email; }
  public String getName() { return name; }
  public void setName(String name) { this.name = name; }
  public String getTier() { return tier; }
  public void setTier(String tier) { this.tier = tier; }
  public String getReason() { return reason; }
  public void setReason(String reason) { this.reason = reason; }

  @Override
  public String toString() {
    return "DeactivationRequest [name=" + name + ", email=" + email + ", tier=" + tier
        + ", reason=" + reason + "]";
  }
}
