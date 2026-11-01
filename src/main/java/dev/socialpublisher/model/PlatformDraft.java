package dev.socialpublisher.model;

public class PlatformDraft {
  private String text;
  private String updatedAt;

  public PlatformDraft() {}

  public PlatformDraft(String text, String updatedAt) {
    this.text = text;
    this.updatedAt = updatedAt;
  }

  public String getText() {
    return text;
  }

  public void setText(String text) {
    this.text = text;
  }

  public String getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(String updatedAt) {
    this.updatedAt = updatedAt;
  }
}
