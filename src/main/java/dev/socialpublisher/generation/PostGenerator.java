package dev.socialpublisher.generation;

public interface PostGenerator {
  String generate(String prompt);

  /**
   * Generates a JSON answer for the prompt. Providers that support schema-constrained output should
   * override this; the default simply concatenates the prompt parts.
   */
  default String generateStructured(StructuredPrompt prompt) {
    return generate(prompt.system() + "\n\n" + prompt.user());
  }

  default String provider() {
    return "template";
  }

  default String model() {
    return "none";
  }
}
