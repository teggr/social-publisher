package dev.socialpublisher.model;

public class PublishingResult {
  private PublishingStatus status = PublishingStatus.NOT_PUBLISHED;
  private String remoteId;
  private String url;
  private String attemptedAt;
  private String publishedAt;
  private String error;
  private boolean retrySafe;
  private int attempts;

  public PublishingStatus getStatus() {
    return status;
  }

  public void setStatus(PublishingStatus status) {
    this.status = status;
  }

  public String getRemoteId() {
    return remoteId;
  }

  public void setRemoteId(String remoteId) {
    this.remoteId = remoteId;
  }

  public String getUrl() {
    return url;
  }

  public void setUrl(String url) {
    this.url = url;
  }

  public String getAttemptedAt() {
    return attemptedAt;
  }

  public void setAttemptedAt(String attemptedAt) {
    this.attemptedAt = attemptedAt;
  }

  public String getPublishedAt() {
    return publishedAt;
  }

  public void setPublishedAt(String publishedAt) {
    this.publishedAt = publishedAt;
  }

  public String getError() {
    return error;
  }

  public void setError(String error) {
    this.error = error;
  }

  public boolean isRetrySafe() {
    return retrySafe;
  }

  public void setRetrySafe(boolean retrySafe) {
    this.retrySafe = retrySafe;
  }

  public int getAttempts() {
    return attempts;
  }

  public void setAttempts(int attempts) {
    this.attempts = attempts;
  }
}
