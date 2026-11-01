package dev.socialpublisher.generation.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.socialpublisher.config.AppConfig;
import dev.socialpublisher.generation.ArticleDraftGenerator;
import dev.socialpublisher.generation.GenerationRuntimeException;
import dev.socialpublisher.generation.StructuredPrompt;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OpenAiPostGeneratorTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private HttpServer server;
  private final List<String> bodies = new CopyOnWriteArrayList<>();
  private final List<String> auth = new CopyOnWriteArrayList<>();
  private volatile int status = 200;
  private volatile String responseBody;

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          auth.add(exchange.getRequestHeaders().getFirst("Authorization"));
          byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(status, out.length);
          exchange.getResponseBody().write(out);
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private OpenAiPostGenerator generator() {
    return new OpenAiPostGenerator(
        "sk-test-secret",
        "http://127.0.0.1:" + server.getAddress().getPort(),
        "gpt-test",
        Duration.ofSeconds(5));
  }

  private static String completion(String content) throws Exception {
    return JSON.writeValueAsString(
        Map.of(
            "id", "chatcmpl-1",
            "object", "chat.completion",
            "created", 1,
            "model", "gpt-test",
            "choices",
                List.of(
                    Map.of(
                        "index",
                        0,
                        "finish_reason",
                        "stop",
                        "message",
                        Map.of("role", "assistant", "content", content)))));
  }

  private static final StructuredPrompt PROMPT =
      new StructuredPrompt(
          "system rules", "user request", "social_post", ArticleDraftGenerator.SCHEMA);

  @Test
  void sendsStructuredRequestAndReturnsContent() throws Exception {
    responseBody = completion("{\"text\":\"hello https://example.com\"}");
    OpenAiPostGenerator generator = generator();

    String result = generator.generateStructured(PROMPT);

    assertEquals("{\"text\":\"hello https://example.com\"}", result);
    assertEquals("openai", generator.provider());
    assertEquals("gpt-test", generator.model());
    assertEquals("Bearer sk-test-secret", auth.get(0));
    JsonNode request = JSON.readTree(bodies.get(0));
    assertEquals("gpt-test", request.get("model").asText());
    assertEquals("json_schema", request.get("response_format").get("type").asText());
    assertEquals(
        "social_post", request.get("response_format").get("json_schema").get("name").asText());
    assertTrue(request.get("response_format").get("json_schema").get("strict").asBoolean());
    assertEquals("system", request.get("messages").get(0).get("role").asText());
    assertEquals("user request", request.get("messages").get(1).get("content").asText());
  }

  @Test
  void reportsHttpErrorsWithoutLeakingTheKey() {
    status = 401;
    responseBody = "{\"error\":{\"message\":\"Incorrect API key provided: sk-test-secret\"}}";
    GenerationRuntimeException e =
        assertThrows(
            GenerationRuntimeException.class, () -> generator().generateStructured(PROMPT));
    assertTrue(e.getMessage().contains("OpenAI request failed"));
    assertTrue(e.getMessage().contains("401"));
    assertFalse(e.getMessage().contains("sk-test-secret"));
  }

  @Test
  void reportsEmptyContent() throws Exception {
    responseBody = completion("");
    assertThrows(GenerationRuntimeException.class, () -> generator().generateStructured(PROMPT));
  }

  @Test
  void keyAndModelComeFromConfigurationAndEnvironment() {
    AppConfig config = new AppConfig();
    config.setOpenAiBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
    config.setOpenAiApiKeyEnv("MY_KEY");
    config.setOpenAiModel("configured-model");
    assertEquals(
        "configured-model",
        OpenAiPostGenerator.fromConfig(config, Map.of("MY_KEY", "k"), null).model());
    assertEquals(
        "override",
        OpenAiPostGenerator.fromConfig(config, Map.of("MY_KEY", "k"), "override").model());
    GenerationRuntimeException e =
        assertThrows(
            GenerationRuntimeException.class,
            () -> OpenAiPostGenerator.fromConfig(config, Map.of(), null));
    assertTrue(e.getMessage().contains("MY_KEY"));
  }
}
