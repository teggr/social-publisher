package dev.socialpublisher.generation;

public class TemplatePostGenerator implements PostGenerator {
  @Override
  public String generate(String prompt) {
    if (prompt == null || prompt.isBlank()) {
      throw new IllegalArgumentException("Generation topic must not be empty");
    }
    return "A thought on "
        + prompt.trim()
        + ": What have you learned about it recently?";
  }
}
