package ch.unisg.worldpulse.signup.domain;

public class UpgradeRequest {

  private String userId;
  private String email;
  private String name;
  private String currentTier;
  private String targetTier;

  public String getUserId() { return userId; }
  public void setUserId(String userId) { this.userId = userId; }
  public String getEmail() { return email; }
  public void setEmail(String email) { this.email = email; }
  public String getName() { return name; }
  public void setName(String name) { this.name = name; }
  public String getCurrentTier() { return currentTier; }
  public void setCurrentTier(String currentTier) { this.currentTier = currentTier; }
  public String getTargetTier() { return targetTier; }
  public void setTargetTier(String targetTier) { this.targetTier = targetTier; }

  @Override
  public String toString() {
    return "UpgradeRequest [name=" + name + ", email=" + email + ", currentTier=" + currentTier
        + ", targetTier=" + targetTier + "]";
  }
}
