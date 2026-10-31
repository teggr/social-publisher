package dev.socialpublisher.publishing;

public class PublishException extends Exception {
  private final boolean retrySafe;

  public PublishException(String message, boolean retrySafe) {
    super(message);
    this.retrySafe = retrySafe;
  }

  public PublishException(String message, boolean retrySafe, Throwable cause) {
    super(message, cause);
    this.retrySafe = retrySafe;
  }

  public boolean isRetrySafe() {
    return retrySafe;
  }
}
