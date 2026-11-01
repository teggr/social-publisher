package dev.socialpublisher.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Provenance of generated content. Never contains credentials. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GenerationInfo {
  private String provider;
  private String model;
  private String profile;
  private String generatedAt;

  public String getProvider() {
    return provider;
  }

  public void setProvider(String provider) {
    this.provider = provider;
  }

  public String getModel() {
    return model;
  }

  public void setModel(String model) {
    this.model = model;
  }

  public String getProfile() {
    return profile;
  }

  public void setProfile(String profile) {
    this.profile = profile;
  }

  public String getGeneratedAt() {
    return generatedAt;
  }

  public void setGeneratedAt(String generatedAt) {
    this.generatedAt = generatedAt;
  }
}
