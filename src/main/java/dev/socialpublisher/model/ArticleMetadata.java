package dev.socialpublisher.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Metadata about the article a draft was generated from. The article body is never persisted. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ArticleMetadata {
  private String sourceUrl;
  private String canonicalUrl;
  private String title;
  private String description;
  private String publishedAt;
  private String imageUrl;

  public String getSourceUrl() {
    return sourceUrl;
  }

  public void setSourceUrl(String sourceUrl) {
    this.sourceUrl = sourceUrl;
  }

  public String getCanonicalUrl() {
    return canonicalUrl;
  }

  public void setCanonicalUrl(String canonicalUrl) {
    this.canonicalUrl = canonicalUrl;
  }

  public String getTitle() {
    return title;
  }

  public void setTitle(String title) {
    this.title = title;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public String getPublishedAt() {
    return publishedAt;
  }

  public void setPublishedAt(String publishedAt) {
    this.publishedAt = publishedAt;
  }

  public String getImageUrl() {
    return imageUrl;
  }

  public void setImageUrl(String imageUrl) {
    this.imageUrl = imageUrl;
  }
}
