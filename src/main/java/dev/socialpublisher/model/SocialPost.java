package dev.socialpublisher.model;

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
