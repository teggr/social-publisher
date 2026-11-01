package dev.socialpublisher.config;

public class AppConfig {
  private String dataDirectory = ".social-publisher";
  private String blueskyService = "https://bsky.social";
  private String blueskyIdentifierEnv = "BLUESKY_IDENTIFIER";
  private String blueskyAppPasswordEnv = "BLUESKY_APP_PASSWORD";
  private String openAiApiKeyEnv = "OPENAI_API_KEY";
  private String openAiBaseUrl = "https://api.openai.com";
  private String openAiModel = "gpt-4o-mini";
  private int openAiTimeoutSeconds = 60;
  private int mastodonCharacterLimit = 500;
  private String profilesDirectory = "profiles";
  private String defaultProfile;

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

  public String getOpenAiApiKeyEnv() {
    return openAiApiKeyEnv;
  }

  public void setOpenAiApiKeyEnv(String openAiApiKeyEnv) {
    this.openAiApiKeyEnv = openAiApiKeyEnv;
  }

  public String getOpenAiBaseUrl() {
    return openAiBaseUrl;
  }

  public void setOpenAiBaseUrl(String openAiBaseUrl) {
    this.openAiBaseUrl = openAiBaseUrl;
  }

  public String getOpenAiModel() {
    return openAiModel;
  }

  public void setOpenAiModel(String openAiModel) {
    this.openAiModel = openAiModel;
  }

  public int getOpenAiTimeoutSeconds() {
    return openAiTimeoutSeconds;
  }

  public void setOpenAiTimeoutSeconds(int openAiTimeoutSeconds) {
    this.openAiTimeoutSeconds = openAiTimeoutSeconds;
  }

  public int getMastodonCharacterLimit() {
    return mastodonCharacterLimit;
  }

  public void setMastodonCharacterLimit(int mastodonCharacterLimit) {
    this.mastodonCharacterLimit = mastodonCharacterLimit;
  }

  public String getProfilesDirectory() {
    return profilesDirectory;
  }

  public void setProfilesDirectory(String profilesDirectory) {
    this.profilesDirectory = profilesDirectory;
  }

  public String getDefaultProfile() {
    return defaultProfile;
  }

  public void setDefaultProfile(String defaultProfile) {
    this.defaultProfile = defaultProfile;
  }
}
