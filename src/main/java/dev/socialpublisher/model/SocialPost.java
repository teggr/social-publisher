package dev.socialpublisher.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.EnumMap;
import java.util.Map;

public class SocialPost {
  private String id;
  private String text;
  private String createdAt;
  private String updatedAt;
  private boolean approved;
  private String approvedBy;
  private String approvedAt;
  private ArticleMetadata article;
  private GenerationInfo generation;
  private Map<Platform, PlatformDraft> platformDrafts = new EnumMap<>(Platform.class);
  private Map<Platform, PublishingResult> publishingResults = new EnumMap<>(Platform.class);

  public SocialPost() {}

  public SocialPost(String id, String text, String createdAt) {
    this.id = id;
    this.text = text;
    this.createdAt = createdAt;
    this.updatedAt = createdAt;
  }

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getText() {
    return text;
  }

  public void setText(String text) {
    this.text = text;
  }

  public String getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(String createdAt) {
    this.createdAt = createdAt;
  }

  public String getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(String updatedAt) {
    this.updatedAt = updatedAt;
  }

  public boolean isApproved() {
    return approved;
  }

  public void setApproved(boolean approved) {
    this.approved = approved;
  }

  public String getApprovedBy() {
    return approvedBy;
  }

  public void setApprovedBy(String approvedBy) {
    this.approvedBy = approvedBy;
  }

  public String getApprovedAt() {
    return approvedAt;
  }

  public void setApprovedAt(String approvedAt) {
    this.approvedAt = approvedAt;
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public ArticleMetadata getArticle() {
    return article;
  }

  public void setArticle(ArticleMetadata article) {
    this.article = article;
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public GenerationInfo getGeneration() {
    return generation;
  }

  public void setGeneration(GenerationInfo generation) {
    this.generation = generation;
  }

  @JsonInclude(JsonInclude.Include.NON_EMPTY)
  public Map<Platform, PlatformDraft> getPlatformDrafts() {
    return platformDrafts;
  }

  public void setPlatformDrafts(Map<Platform, PlatformDraft> platformDrafts) {
    this.platformDrafts = new EnumMap<>(Platform.class);
    if (platformDrafts != null) {
      this.platformDrafts.putAll(platformDrafts);
    }
  }

  /** The text to publish for a platform: its own draft when present, else the shared text. */
  public String textFor(Platform platform) {
    PlatformDraft draft = platformDrafts.get(platform);
    return draft != null ? draft.getText() : text;
  }

  public Map<Platform, PublishingResult> getPublishingResults() {
    return publishingResults;
  }

  public void setPublishingResults(Map<Platform, PublishingResult> publishingResults) {
    this.publishingResults = new EnumMap<>(Platform.class);
    if (publishingResults != null) {
      this.publishingResults.putAll(publishingResults);
    }
  }
}
