package dev.socialpublisher.article;

public class ArticleExtractionException extends Exception {
  public ArticleExtractionException(String message) {
    super(message);
  }

  public ArticleExtractionException(String message, Throwable cause) {
    super(message, cause);
  }
}
