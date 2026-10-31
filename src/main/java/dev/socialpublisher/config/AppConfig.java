package dev.socialpublisher.config;

public class AppConfig {
  private String dataDirectory = ".social-publisher";
  private String blueskyService = "https://bsky.social";
  private String blueskyIdentifierEnv = "BLUESKY_IDENTIFIER";
  private String blueskyAppPasswordEnv = "BLUESKY_APP_PASSWORD";

  public String getDataDirectory() {
    return dataDirectory;
  }

  public void setDataDirectory(String dataDirectory) {
    this.dataDirectory = dataDirectory;
  }

  public String getBlueskyService() {
    return blueskyService;
  }

  public void setBlueskyService(String blueskyService) {
    this.blueskyService = blueskyService;
  }

  public String getBlueskyIdentifierEnv() {
    return blueskyIdentifierEnv;
  }

  public void setBlueskyIdentifierEnv(String blueskyIdentifierEnv) {
    this.blueskyIdentifierEnv = blueskyIdentifierEnv;
  }

  public String getBlueskyAppPasswordEnv() {
    return blueskyAppPasswordEnv;
  }

  public void setBlueskyAppPasswordEnv(String blueskyAppPasswordEnv) {
    this.blueskyAppPasswordEnv = blueskyAppPasswordEnv;
  }
}
