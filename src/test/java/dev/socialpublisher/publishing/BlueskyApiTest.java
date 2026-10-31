package dev.socialpublisher.publishing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.socialpublisher.config.AppConfig;
import dev.socialpublisher.model.SocialPost;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BlueskyApiTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private HttpServer server;
  private final AtomicInteger sessionStatus = new AtomicInteger(200);
  private final AtomicInteger recordStatus = new AtomicInteger(200);
  private final AtomicInteger recordCalls = new AtomicInteger();
  private final AtomicReference<String> recordBody = new AtomicReference<>();
  private final AtomicReference<String> recordError = new AtomicReference<>("{}");
  private final AtomicReference<String> retryAfter = new AtomicReference<>();
  private final AtomicReference<String> sessionBody =
      new AtomicReference<>(
          "{\"accessJwt\":\"jwt\",\"did\":\"did:plc:example\",\"handle\":\"me.example.com\"}");

  @BeforeEach
  void startServer() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/xrpc/com.atproto.server.createSession",
        exchange -> respond(exchange, sessionStatus.get(), sessionBody.get()));
    server.createContext(
        "/xrpc/com.atproto.repo.createRecord",
        exchange -> {
          recordCalls.incrementAndGet();
          recordBody.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          if (retryAfter.get() != null) {
            exchange.getResponseHeaders().set("Retry-After", retryAfter.get());
          }
          respond(
              exchange,
              recordStatus.get(),
              recordStatus.get() == 200
                  ? "{\"uri\":\"at://did:plc:example/app.bsky.feed.post/rkey1\"}"
                  : recordError.get());
        });
    server.start();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  @Test
  void sendsLinkAndHashtagFacetsWithUtf8ByteOffsetsAndUsesSessionHandleInUrl() throws Exception {
    String text = "Héllo 🙂 see https://example.com/a?b=1. #java";

    PublishReceipt receipt = publisher().publish(post(text));

    assertEquals("https://bsky.app/profile/me.example.com/post/rkey1", receipt.url());
    JsonNode record = JSON.readTree(recordBody.get()).get("record");
    assertEquals(text, record.get("text").asText());
    JsonNode link = record.get("facets").get(0);
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    int start = link.get("index").get("byteStart").asInt();
    int end = link.get("index").get("byteEnd").asInt();
    assertEquals(
        "https://example.com/a?b=1", new String(bytes, start, end - start, StandardCharsets.UTF_8));
    assertEquals("https://example.com/a?b=1", link.get("features").get(0).get("uri").asText());
    JsonNode tag = record.get("facets").get(1);
    assertEquals("java", tag.get("features").get(0).get("tag").asText());
    start = tag.get("index").get("byteStart").asInt();
    end = tag.get("index").get("byteEnd").asInt();
    assertEquals("#java", new String(bytes, start, end - start, StandardCharsets.UTF_8));
  }

  @Test
  void omitsFacetsForPlainTextAndIgnoresFragmentsInsideLinks() {
    assertTrue(BlueskyPublisher.facets("just text, 100% plain & #123").isEmpty());
    assertEquals(1, BlueskyPublisher.facets("https://example.com/page#section").size());
  }

  @Test
  void enforcesThreeHundredGraphemeLimitBeforeAnyNetworkCall() throws Exception {
    assertEquals(0, recordCalls.get());
    publisher().publish(post("🙂".repeat(300)));
    assertEquals(1, recordCalls.get());

    PublishException tooLong =
        assertThrows(PublishException.class, () -> publisher().publish(post("a".repeat(301))));
    assertTrue(tooLong.isRetrySafe());
    assertEquals(1, recordCalls.get());
  }

  @Test
  void countsCombinedEmojiAsOneGrapheme() throws Exception {
    String family = "👨‍👩‍👧‍👦";
    publisher().publish(post(family.repeat(100)));
    assertEquals(1, recordCalls.get());
  }

  @Test
  void rateLimitIsRetrySafeAndReportsRetryHint() {
    recordStatus.set(429);
    retryAfter.set("42");
    recordError.set("{\"error\":\"RateLimitExceeded\",\"message\":\"Slow down\"}");

    PublishException failure =
        assertThrows(PublishException.class, () -> publisher().publish(post("hi")));

    assertTrue(failure.isRetrySafe());
    assertTrue(failure.getMessage().contains("HTTP 429"));
    assertTrue(failure.getMessage().contains("RateLimitExceeded"));
    assertTrue(failure.getMessage().contains("retry after 42s"));
  }

  @Test
  void rejectedRequestsAreRetrySafeButServerErrorsAndTimeoutsAreNot() {
    for (int status : List.of(400, 401, 403)) {
      recordStatus.set(status);
      assertTrue(
          assertThrows(PublishException.class, () -> publisher().publish(post("hi"))).isRetrySafe(),
          "status " + status);
    }
    for (int status : List.of(408, 500, 502, 503)) {
      recordStatus.set(status);
      assertFalse(
          assertThrows(PublishException.class, () -> publisher().publish(post("hi"))).isRetrySafe(),
          "status " + status);
    }
  }

  @Test
  void authenticationFailureIsRetrySafeAndNeverCreatesARecord() {
    sessionStatus.set(401);

    PublishException failure =
        assertThrows(PublishException.class, () -> publisher().publish(post("hi")));

    assertTrue(failure.isRetrySafe());
    assertEquals(0, recordCalls.get());
  }

  @Test
  void sessionServerErrorBeforePublishingIsRetrySafe() {
    sessionStatus.set(503);

    assertTrue(
        assertThrows(PublishException.class, () -> publisher().publish(post("hi"))).isRetrySafe());
    assertEquals(0, recordCalls.get());
  }

  @Test
  void malformedSessionResponseIsRetrySafeButMalformedSuccessIsNot() {
    sessionBody.set("{\"did\":\"did:plc:example\"}");
    assertTrue(
        assertThrows(PublishException.class, () -> publisher().publish(post("hi"))).isRetrySafe());
    assertEquals(0, recordCalls.get());
  }

  @Test
  void errorMessagesNeverEchoCredentials() {
    recordStatus.set(400);
    recordError.set("{\"error\":\"InvalidRequest\"}");

    PublishException failure =
        assertThrows(PublishException.class, () -> publisher().publish(post("hi")));

    assertFalse(failure.getMessage().contains("app-password"));
    assertFalse(failure.getMessage().contains("jwt"));
  }

  @Test
  void missingCredentialsFailBeforeAnyRequest() {
    BlueskyPublisher publisher =
        new BlueskyPublisher(config(), HttpClient.newHttpClient(), Map.of());

    assertTrue(
        assertThrows(PublishException.class, () -> publisher.publish(post("hi"))).isRetrySafe());
    assertEquals(0, recordCalls.get());
  }

  private BlueskyPublisher publisher() {
    return new BlueskyPublisher(
        config(),
        HttpClient.newHttpClient(),
        Map.of("BLUESKY_IDENTIFIER", "user.example", "BLUESKY_APP_PASSWORD", "app-password"));
  }

  private AppConfig config() {
    AppConfig config = new AppConfig();
    config.setBlueskyService("http://127.0.0.1:" + server.getAddress().getPort());
    return config;
  }

  private static SocialPost post(String text) {
    return new SocialPost(UUID.randomUUID().toString(), text, Instant.now().toString());
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }
}
