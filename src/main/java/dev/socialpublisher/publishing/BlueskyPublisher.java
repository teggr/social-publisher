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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BlueskyPublisher implements PlatformPublisher {
  private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
  private static final Pattern POST_URI =
      Pattern.compile("^at://[^/]+/app\\.bsky\\.feed\\.post/([^/]+)$");
  private static final int MAX_GRAPHEMES = 300;
  private static final int MAX_UTF8_BYTES = 3000;
  private static final Pattern GRAPHEME = Pattern.compile("\\X");
  private static final Pattern LINK = Pattern.compile("https?://[^\\s<>]+");
  private static final Pattern HASHTAG =
      Pattern.compile("(?<![\\p{L}\\p{N}_&/#])#([\\p{L}\\p{N}_]*[\\p{L}_][\\p{L}\\p{N}_]*)");
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
    String text = post.getText();
    validateText(text);
    String service = serviceBase();
    String identifier = environment.apply(config.getBlueskyIdentifierEnv());
    String password = environment.apply(config.getBlueskyAppPasswordEnv());
    if (isBlank(identifier) || isBlank(password)) {
      throw new PublishException(
          "Bluesky credentials are missing from the configured environment variables", true);
    }

    JsonNode session =
        sendBeforePublishing(
            service + "/xrpc/com.atproto.server.createSession",
            Map.of("identifier", identifier, "password", password));
    String accessJwt = requiredField(session, "accessJwt", "Bluesky session response", true);
    String did = requiredField(session, "did", "Bluesky session response", true);

    Map<String, Object> record = new java.util.LinkedHashMap<>();
    record.put("$type", "app.bsky.feed.post");
    record.put("text", text);
    record.put("createdAt", Instant.now().toString());
    List<Map<String, Object>> facets = facets(text);
    if (!facets.isEmpty()) {
      record.put("facets", facets);
    }
    try {
      JsonNode response =
          send(
              service + "/xrpc/com.atproto.repo.createRecord",
              Map.of("repo", did, "collection", "app.bsky.feed.post", "record", record),
              accessJwt);
      String uri = requiredField(response, "uri", "Bluesky post response");
      String url = postUrl(uri, profileName(session, identifier, did));
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
        throw new PublishException(
            describeError(response), isDefinitelyRejected(response.statusCode()));
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

  // 4xx responses (other than a request timeout) mean the server rejected the request unprocessed.
  private static boolean isDefinitelyRejected(int status) {
    return status >= 400 && status < 500 && status != 408;
  }

  private static String describeError(HttpResponse<String> response) {
    int status = response.statusCode();
    StringBuilder message = new StringBuilder("Bluesky returned HTTP " + status);
    try {
      JsonNode body = JSON_MAPPER.readTree(response.body());
      if (body != null && body.hasNonNull("error")) {
        message.append(" (").append(sanitise(body.get("error").asText(), 60)).append(")");
      }
      if (body != null && body.hasNonNull("message")) {
        message.append(": ").append(sanitise(body.get("message").asText(), 200));
      }
    } catch (IOException | RuntimeException e) {
      // Non-JSON error body; the status code is enough.
    }
    if (status == 429) {
      message.append("; rate limited");
      response
          .headers()
          .firstValue("Retry-After")
          .map(value -> "retry after " + sanitise(value, 40) + "s")
          .or(
              () ->
                  response
                      .headers()
                      .firstValue("ratelimit-reset")
                      .map(value -> "limit resets at epoch " + sanitise(value, 40)))
          .ifPresent(hint -> message.append(", ").append(hint));
    }
    return message.toString();
  }

  private static String sanitise(String value, int max) {
    String clean = value.replaceAll("[\\p{Cntrl}]", " ").trim();
    return clean.length() > max ? clean.substring(0, max) + "..." : clean;
  }

  static void validateText(String text) throws PublishException {
    if (text == null || text.isBlank()) {
      throw new PublishException("Bluesky post text must not be empty", true);
    }
    int graphemes = (int) GRAPHEME.matcher(text).results().count();
    if (graphemes > MAX_GRAPHEMES) {
      throw new PublishException(
          "Post has " + graphemes + " characters; Bluesky allows " + MAX_GRAPHEMES, true);
    }
    if (text.getBytes(StandardCharsets.UTF_8).length > MAX_UTF8_BYTES) {
      throw new PublishException("Post exceeds Bluesky's " + MAX_UTF8_BYTES + " byte limit", true);
    }
  }

  /** Link and hashtag facets; offsets are UTF-8 byte positions as required by AT Protocol. */
  static List<Map<String, Object>> facets(String text) {
    List<Map<String, Object>> facets = new ArrayList<>();
    Matcher links = LINK.matcher(text);
    while (links.find()) {
      int end = links.end();
      while (end > links.start() && ".,;:!?)]}'\"".indexOf(text.charAt(end - 1)) >= 0) {
        end--;
      }
      String uri = text.substring(links.start(), end);
      if (uri.length() > "https://".length()) {
        facets.add(
            facet(
                text,
                links.start(),
                end,
                Map.of("$type", "app.bsky.richtext.facet#link", "uri", uri)));
      }
    }
    Matcher tags = HASHTAG.matcher(text);
    while (tags.find()) {
      boolean insideLink = facets.stream().anyMatch(f -> overlaps(text, f, tags.start()));
      if (!insideLink && tags.group(1).length() <= 64) {
        facets.add(
            facet(
                text,
                tags.start(),
                tags.end(),
                Map.of("$type", "app.bsky.richtext.facet#tag", "tag", tags.group(1))));
      }
    }
    return facets;
  }

  @SuppressWarnings("unchecked")
  private static boolean overlaps(String text, Map<String, Object> facet, int charIndex) {
    Map<String, Object> index = (Map<String, Object>) facet.get("index");
    int byteIndex = text.substring(0, charIndex).getBytes(StandardCharsets.UTF_8).length;
    return byteIndex >= (int) index.get("byteStart") && byteIndex < (int) index.get("byteEnd");
  }

  private static Map<String, Object> facet(
      String text, int start, int end, Map<String, Object> feature) {
    int byteStart = text.substring(0, start).getBytes(StandardCharsets.UTF_8).length;
    int byteEnd = byteStart + text.substring(start, end).getBytes(StandardCharsets.UTF_8).length;
    return Map.of(
        "index", Map.of("byteStart", byteStart, "byteEnd", byteEnd), "features", List.of(feature));
  }

  private static String profileName(JsonNode session, String identifier, String did) {
    JsonNode handle = session.get("handle");
    if (handle != null && !handle.asText().isBlank()) {
      return handle.asText();
    }
    return identifier.contains("@") ? did : identifier;
  }

  private String requiredField(JsonNode node, String field, String description)
      throws PublishException {
    return requiredField(node, field, description, false);
  }

  private String requiredField(JsonNode node, String field, String description, boolean retrySafe)
      throws PublishException {
    JsonNode value = node == null ? null : node.get(field);
    if (value == null || value.isNull() || value.asText().isBlank()) {
      throw new PublishException("Invalid " + description, retrySafe);
    }
    return value.asText();
  }

  private String postUrl(String uri, String identifier) throws PublishException {
    Matcher match = POST_URI.matcher(uri);
    if (!match.matches()) {
      throw new PublishException("Invalid Bluesky post URI", false);
    }
    String encodedIdentifier =
        URLEncoder.encode(identifier, StandardCharsets.UTF_8).replace("%3A", ":");
    return "https://bsky.app/profile/" + encodedIdentifier + "/post/" + match.group(1);
  }

  private boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private String serviceBase() throws PublishException {
    try {
      URI uri = URI.create(config.getBlueskyService());
      String scheme = uri.getScheme();
      String host = uri.getHost();
      boolean localHttp =
          "http".equalsIgnoreCase(scheme)
              && ("localhost".equalsIgnoreCase(host)
                  || "127.0.0.1".equals(host)
                  || "::1".equals(host));
      if ((!"https".equalsIgnoreCase(scheme) && !localHttp)
          || host == null
          || uri.getRawUserInfo() != null
          || uri.getRawQuery() != null
          || uri.getRawFragment() != null) {
        throw new PublishException(
            "Bluesky service must use HTTPS (HTTP is allowed only for loopback testing)", true);
      }
      return uri.toString().replaceAll("/+$", "");
    } catch (IllegalArgumentException e) {
      throw new PublishException("Invalid Bluesky service URL", true, e);
    }
  }
}
