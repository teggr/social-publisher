package dev.socialpublisher.generation;

import dev.socialpublisher.model.Platform;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Per-site/project editorial settings, loaded from YAML independently of any AI provider. */
public class EditorialProfile {
  private String name;
  private String siteName;
  private String websiteUrl;
  private String tone;
  private String audience;
  private String writingStyle;
  private List<String> avoid = new ArrayList<>();
  private Map<Platform, String> platformInstructions = new EnumMap<>(Platform.class);

  public static EditorialProfile defaultProfile() {
    EditorialProfile profile = new EditorialProfile();
    profile.name = "default";
    profile.tone = "clear, friendly and informative";
    profile.audience = "readers interested in the article's topic";
    profile.writingStyle = "plain, specific language";
    return profile;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getSiteName() {
    return siteName;
  }

  public void setSiteName(String siteName) {
    this.siteName = siteName;
  }

  public String getWebsiteUrl() {
    return websiteUrl;
  }

  public void setWebsiteUrl(String websiteUrl) {
    this.websiteUrl = websiteUrl;
  }

  public String getTone() {
    return tone;
  }

  public void setTone(String tone) {
    this.tone = tone;
  }

  public String getAudience() {
    return audience;
  }

  public void setAudience(String audience) {
    this.audience = audience;
  }

  public String getWritingStyle() {
    return writingStyle;
  }

  public void setWritingStyle(String writingStyle) {
    this.writingStyle = writingStyle;
  }

  public List<String> getAvoid() {
    return avoid;
  }

  public void setAvoid(List<String> avoid) {
    this.avoid = avoid == null ? new ArrayList<>() : new ArrayList<>(avoid);
  }

  public Map<Platform, String> getPlatformInstructions() {
    return platformInstructions;
  }

  public void setPlatformInstructions(Map<Platform, String> platformInstructions) {
    this.platformInstructions = new EnumMap<>(Platform.class);
    if (platformInstructions != null) {
      this.platformInstructions.putAll(platformInstructions);
    }
  }
}
