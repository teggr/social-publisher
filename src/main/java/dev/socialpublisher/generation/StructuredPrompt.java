package dev.socialpublisher.generation;

/** A prompt whose answer must be a JSON document matching {@code jsonSchema}. */
public record StructuredPrompt(String system, String user, String schemaName, String jsonSchema) {}
