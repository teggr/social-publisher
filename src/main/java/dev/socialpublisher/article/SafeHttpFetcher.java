package dev.socialpublisher.article;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;

/**
 * Fetches an HTML page with bounded size and time. Redirects are followed manually so that every
 * hop is validated by the {@link UrlSafetyPolicy}. Only the page itself is downloaded; scripts and
 * sub-resources are never fetched or executed.
 *
 * <p>Known limitation: the host is resolved and checked immediately before connecting, but the HTTP
 * client resolves it again, so a DNS-rebinding attacker with a very short TTL could in theory race
 * the check.
 */
public class SafeHttpFetcher {
  static final int MAX_BYTES = 2 * 1024 * 1024;
  static final int MAX_REDIRECTS = 5;

  /** A fetched page. */
  public record Page(URI finalUri, byte[] body, String charset) {}

  private final UrlSafetyPolicy policy;
  private final HttpClient client;
  private final Duration requestTimeout;

  public SafeHttpFetcher() {
    this(new UrlSafetyPolicy(), Duration.ofSeconds(10), Duration.ofSeconds(20));
  }

  SafeHttpFetcher(UrlSafetyPolicy policy, Duration connectTimeout, Duration requestTimeout) {
    this.policy = policy;
    this.requestTimeout = requestTimeout;
    this.client =
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(connectTimeout)
            .build();
  }

  public UrlSafetyPolicy policy() {
    return policy;
  }

  public Page fetch(String url) throws ArticleExtractionException {
    URI current = policy.validate(url);
    for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
      HttpResponse<InputStream> response = send(current);
      int status = response.statusCode();
      if (status >= 300 && status < 400) {
        closeQuietly(response.body());
        String location = response.headers().firstValue("Location").orElse(null);
        if (location == null || location.isBlank()) {
          throw new ArticleExtractionException("Redirect without a Location header");
        }
        try {
          current = policy.validate(current.resolve(location.trim()));
        } catch (IllegalArgumentException e) {
          throw new ArticleExtractionException("Redirect to an invalid URL");
        }
        continue;
      }
      if (status < 200 || status >= 300) {
        closeQuietly(response.body());
        throw new ArticleExtractionException("Article request failed with HTTP status " + status);
      }
      return readPage(current, response);
    }
    throw new ArticleExtractionException("Too many redirects");
  }

  private HttpResponse<InputStream> send(URI uri) throws ArticleExtractionException {
    HttpRequest request =
        HttpRequest.newBuilder(uri)
            .timeout(requestTimeout)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("User-Agent", "social-publisher/0.1 (+article-extractor)")
            .GET()
            .build();
    try {
      return client.send(request, HttpResponse.BodyHandlers.ofInputStream());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ArticleExtractionException("Article request was interrupted", e);
    } catch (IOException e) {
      throw new ArticleExtractionException(
          "Could not fetch article: " + e.getClass().getSimpleName(), e);
    }
  }

  private Page readPage(URI uri, HttpResponse<InputStream> response)
      throws ArticleExtractionException {
    String contentType = response.headers().firstValue("Content-Type").orElse("");
    String lower = contentType.toLowerCase(Locale.ROOT);
    if (!lower.isEmpty() && !lower.contains("text/html") && !lower.contains("xhtml")) {
      closeQuietly(response.body());
      throw new ArticleExtractionException("URL did not return an HTML page: " + contentType);
    }
    long declared = response.headers().firstValueAsLong("Content-Length").orElse(-1);
    if (declared > MAX_BYTES) {
      closeQuietly(response.body());
      throw new ArticleExtractionException("Article is larger than " + MAX_BYTES + " bytes");
    }
    long deadline = System.nanoTime() + requestTimeout.toNanos();
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    try (InputStream in = response.body()) {
      int read;
      while ((read = in.read(buffer)) != -1) {
        if (System.nanoTime() > deadline) {
          throw new ArticleExtractionException("Timed out reading the article");
        }
        if (bytes.size() + read > MAX_BYTES) {
          throw new ArticleExtractionException("Article is larger than " + MAX_BYTES + " bytes");
        }
        bytes.write(buffer, 0, read);
      }
    } catch (IOException e) {
      throw new ArticleExtractionException("Could not read article: " + e.getMessage(), e);
    }
    return new Page(uri, bytes.toByteArray(), charsetOf(contentType));
  }

  private static String charsetOf(String contentType) {
    for (String part : contentType.split(";")) {
      String trimmed = part.trim();
      if (trimmed.toLowerCase(Locale.ROOT).startsWith("charset=")) {
        return trimmed.substring("charset=".length()).replace("\"", "").trim();
      }
    }
    return null;
  }

  private static void closeQuietly(InputStream in) {
    try {
      in.close();
    } catch (IOException ignored) {
      // nothing useful to do
    }
  }
}
