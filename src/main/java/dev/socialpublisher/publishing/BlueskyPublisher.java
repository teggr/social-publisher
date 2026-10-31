package dev.socialpublisher.publishing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.socialpublisher.config.AppConfig;
import dev.socialpublisher.model.Platform;
import dev.socialpublisher.model.SocialPost;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.function.Function;

public class BlueskyPublisher implements PlatformPublisher {
  private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
  private final AppConfig config;
  private final HttpClient httpClient;
  private final Function<String, String> environment;

  public BlueskyPublisher(AppConfig config) {
    this(config, HttpClient.newHttpClient(), System::getenv);
  }

  public BlueskyPublisher(
      AppConfig config, HttpClient httpClient, Map<String, String> environment) {
    this(config, httpClient, environment::get);
  }

  private BlueskyPublisher(
      AppConfig config, HttpClient httpClient, Function<String, String> environment) {
    this.config = config;
    this.httpClient = httpClient;
    this.environment = environment;
  }

  @Override
  public Platform platform() {
    return Platform.BLUESKY;
  }

  @Override
  public PublishReceipt publish(SocialPost post) throws PublishException {
    String identifier = environment.apply(config.getBlueskyIdentifierEnv());
    String password = environment.apply(config.getBlueskyAppPasswordEnv());
    if (isBlank(identifier) || isBlank(password)) {
      throw new PublishException(
          "Bluesky credentials are missing from the configured environment variables", true);
    }

    String service = config.getBlueskyService().replaceAll("/+$", "");
    JsonNode session =
        sendBeforePublishing(
            service + "/xrpc/com.atproto.server.createSession",
            Map.of("identifier", identifier, "password", password));
    String accessJwt = requiredField(session, "accessJwt", "Bluesky session response");
    String did = requiredField(session, "did", "Bluesky session response");

    Map<String, Object> record =
        Map.of(
            "$type", "app.bsky.feed.post",
            "text", post.getText(),
            "createdAt", Instant.now().toString());
    try {
      JsonNode response =
          send(
              service + "/xrpc/com.atproto.repo.createRecord",
              Map.of("repo", did, "collection", "app.bsky.feed.post", "record", record),
              accessJwt);
      String uri = requiredField(response, "uri", "Bluesky post response");
      String url = postUrl(uri, identifier);
      return new PublishReceipt(uri, url);
    } catch (PublishException e) {
      throw e;
    } catch (Exception e) {
      throw new PublishException("Bluesky publication outcome is unknown", false, e);
    }
  }

  private JsonNode sendBeforePublishing(String url, Map<String, ?> body) throws PublishException {
    try {
      return send(url, body, null);
    } catch (PublishException e) {
      throw new PublishException(e.getMessage(), true, e);
    }
  }

  private JsonNode send(String url, Map<String, ?> body, String accessJwt) throws PublishException {
    try {
      HttpRequest.Builder request =
          HttpRequest.newBuilder(URI.create(url))
              .timeout(Duration.ofSeconds(30))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(JSON_MAPPER.writeValueAsString(body)));
      if (accessJwt != null) {
        request.header("Authorization", "Bearer " + accessJwt);
      }
      HttpResponse<String> response =
          httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        boolean retrySafe = response.statusCode() < 500;
        throw new PublishException("Bluesky returned HTTP " + response.statusCode(), retrySafe);
      }
      return JSON_MAPPER.readTree(response.body());
    } catch (PublishException e) {
      throw e;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new PublishException("Bluesky request was interrupted", accessJwt == null, e);
    } catch (IOException | IllegalArgumentException e) {
      throw new PublishException("Could not complete Bluesky request", accessJwt == null, e);
    }
  }

  private String requiredField(JsonNode node, String field, String description)
      throws PublishException {
    JsonNode value = node == null ? null : node.get(field);
    if (value == null || value.isNull() || value.asText().isBlank()) {
      throw new PublishException("Invalid " + description, false);
    }
    return value.asText();
  }

  private String postUrl(String uri, String identifier) {
    String[] parts = uri.split("/");
    if (parts.length < 2) {
      return null;
    }
    String rkey = parts[parts.length - 1];
    String encodedIdentifier = URLEncoder.encode(identifier, StandardCharsets.UTF_8);
    return "https://bsky.app/profile/" + encodedIdentifier + "/post/" + rkey;
  }

  private boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
