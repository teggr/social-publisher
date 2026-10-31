package dev.socialpublisher.publishing;

import dev.socialpublisher.model.PublishingResult;

public class PublishingFailedException extends RuntimeException {
  private final PublishingResult result;

  public PublishingFailedException(String message, PublishingResult result, Throwable cause) {
    super(message, cause);
    this.result = result;
  }

  public PublishingResult getResult() {
    return result;
  }
}
