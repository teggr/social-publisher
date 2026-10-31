package dev.socialpublisher.publishing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import dev.socialpublisher.config.AppConfig;
import dev.socialpublisher.model.SocialPost;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BlueskyPublisherTest {
  private HttpServer server;
  private int recordStatus;
  private final AtomicReference<String> authorization = new AtomicReference<>();

  @BeforeEach
  void startServer() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/xrpc/com.atproto.server.createSession",
        exchange -> {
          byte[] response =
              "{\"accessJwt\":\"test-session-token\",\"did\":\"did:plc:example\"}"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    server.createContext(
        "/xrpc/com.atproto.repo.createRecord",
        exchange -> {
          authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
          byte[] response =
              "{\"uri\":\"at://did:plc:example/app.bsky.feed.post/abc123\"}"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(recordStatus, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  @Test
  void createsBlueskyRecordUsingEnvironmentCredentials() throws Exception {
    recordStatus = 200;
    AppConfig config = new AppConfig();
    config.setBlueskyService("http://127.0.0.1:" + server.getAddress().getPort());
    BlueskyPublisher publisher =
        new BlueskyPublisher(
            config,
            java.net.http.HttpClient.newHttpClient(),
            Map.of("BLUESKY_IDENTIFIER", "user.example", "BLUESKY_APP_PASSWORD", "app-password"));
    SocialPost post =
        new SocialPost(UUID.randomUUID().toString(), "Hello Bluesky", Instant.now().toString());

    PublishReceipt receipt = publisher.publish(post);

    assertEquals("at://did:plc:example/app.bsky.feed.post/abc123", receipt.remoteId());
    assertEquals("https://bsky.app/profile/user.example/post/abc123", receipt.url());
    assertTrue(authorization.get().startsWith("Bearer "));
  }

  @Test
  void marksServerErrorOutcomeAsUnsafeToRetry() {
    recordStatus = 503;
    AppConfig config = new AppConfig();
    config.setBlueskyService("http://127.0.0.1:" + server.getAddress().getPort());
    BlueskyPublisher publisher =
        new BlueskyPublisher(
            config,
            java.net.http.HttpClient.newHttpClient(),
            Map.of("BLUESKY_IDENTIFIER", "user.example", "BLUESKY_APP_PASSWORD", "app-password"));
    SocialPost post =
        new SocialPost(UUID.randomUUID().toString(), "Hello", Instant.now().toString());

    PublishException failure = assertThrows(PublishException.class, () -> publisher.publish(post));

    assertFalse(failure.isRetrySafe());
  }
}
