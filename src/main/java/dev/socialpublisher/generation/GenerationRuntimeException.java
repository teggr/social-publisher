package dev.socialpublisher.generation;

/** Unchecked failure reported by a {@link PostGenerator} provider; messages never hold secrets. */
public class GenerationRuntimeException extends RuntimeException {
  public GenerationRuntimeException(String message) {
    super(message);
  }

  public GenerationRuntimeException(String message, Throwable cause) {
    super(message, cause);
  }
}
