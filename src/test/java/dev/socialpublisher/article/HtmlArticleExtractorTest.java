package dev.socialpublisher.article;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HtmlArticleExtractorTest {
  private static final String PARAGRAPH =
      "Virtual threads make blocking code cheap again, and this paragraph is long enough to count"
          + " as real article content for the extractor. ";
  private static final String FULL =
      """
      <html><head><title>Fallback title</title>
      <link rel="canonical" href="https://example.com/2026/10/29/post">
      <meta property="og:title" content="Virtual threads in practice">
      <meta property="og:description" content="What changed for blocking code">
      <meta property="og:image" content="/img/cover.png">
      <meta property="article:published_time" content="2026-10-29T08:00:00Z">
      <script>alert('do not include')</script></head>
      <body><nav>Home About</nav><article><h1>Virtual threads in practice</h1>
      <p>%s</p><p>%s</p><pre>Thread.ofVirtual().start(task);</pre></article>
      <footer>Copyright</footer></body></html>
      """
          .formatted(PARAGRAPH, PARAGRAPH);

  private final HtmlArticleExtractor extractor =
      new HtmlArticleExtractor(
          new SafeHttpFetcher(
              UrlSafetyPolicy.allowingNonPublicAddresses(),
              Duration.ofSeconds(2),
              Duration.ofSeconds(2)));
  private HttpServer server;

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private String serve(String path, int status, String contentType, byte[] body) {
    server.createContext(
        path,
        exchange -> {
          exchange.getResponseHeaders().add("Content-Type", contentType);
          exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
          if (body.length > 0) {
            exchange.getResponseBody().write(body);
          }
          exchange.close();
        });
    return "http://127.0.0.1:" + server.getAddress().getPort() + path;
  }

  @Test
  void extractsMetadataAndMainContent() throws Exception {
    URI base = URI.create("https://example.com/2026/10/29/post");
    ExtractedArticle article =
        extractor.parse(
            FULL.getBytes(StandardCharsets.UTF_8), "UTF-8", base, "https://example.com/post?x=1");
    var meta = article.metadata();
    assertEquals("https://example.com/post?x=1", meta.getSourceUrl());
    assertEquals("https://example.com/2026/10/29/post", meta.getCanonicalUrl());
    assertEquals("Virtual threads in practice", meta.getTitle());
    assertEquals("What changed for blocking code", meta.getDescription());
    assertEquals("2026-10-29T08:00:00Z", meta.getPublishedAt());
    assertEquals("https://example.com/img/cover.png", meta.getImageUrl());
    assertTrue(article.content().contains("Virtual threads make blocking code cheap"));
    assertTrue(article.content().contains("Thread.ofVirtual()"));
    assertFalse(article.content().contains("alert"));
    assertFalse(article.content().contains("Copyright"));
    assertFalse(article.content().contains("<p>"));
  }

  @Test
  void toleratesMissingMetadata() throws Exception {
    String html = "<html><body><p>" + PARAGRAPH + "</p></body></html>";
    ExtractedArticle article =
        extractor.parse(
            html.getBytes(StandardCharsets.UTF_8),
            null,
            URI.create("https://example.com/a#frag"),
            "https://example.com/a#frag");
    assertEquals("https://example.com/a", article.metadata().getCanonicalUrl());
    assertEquals("example.com", article.metadata().getTitle());
    assertNull(article.metadata().getDescription());
    assertNull(article.metadata().getPublishedAt());
    assertNull(article.metadata().getImageUrl());
    assertFalse(article.content().isBlank());
  }

  @Test
  void usesJsonLdDateAndIgnoresCrossSiteCanonical() throws Exception {
    String html =
        "<html><head><link rel=canonical href='https://evil.example/x'>"
            + "<script type='application/ld+json'>{\"@graph\":[{\"datePublished\":\"2026-01-02\"}]}"
            + "</script></head><body><main><p>"
            + PARAGRAPH
            + "</p></main></body></html>";
    ExtractedArticle article =
        extractor.parse(
            html.getBytes(StandardCharsets.UTF_8),
            "UTF-8",
            URI.create("https://example.com/a"),
            "https://example.com/a");
    assertEquals("2026-01-02", article.metadata().getPublishedAt());
    assertEquals("https://example.com/a", article.metadata().getCanonicalUrl());
  }

  @Test
  void rejectsPagesWithoutContent() {
    assertThrows(
        ArticleExtractionException.class,
        () ->
            extractor.parse(
                "<html><body></body></html>".getBytes(StandardCharsets.UTF_8),
                null,
                URI.create("https://example.com/"),
                "https://example.com/"));
  }

  @Test
  void fetchesOverHttp() throws Exception {
    String url =
        serve("/post", 200, "text/html; charset=utf-8", FULL.getBytes(StandardCharsets.UTF_8));
    ExtractedArticle article = extractor.extract(url);
    assertEquals("Virtual threads in practice", article.metadata().getTitle());
  }

  @Test
  void rejectsNonHtmlErrorsAndOversizedResponses() {
    String json = serve("/json", 200, "application/json", "{}".getBytes(StandardCharsets.UTF_8));
    String missing = serve("/missing", 404, "text/html", "no".getBytes(StandardCharsets.UTF_8));
    String large = serve("/big", 200, "text/html", new byte[SafeHttpFetcher.MAX_BYTES + 1]);
    assertThrows(ArticleExtractionException.class, () -> extractor.extract(json));
    assertThrows(ArticleExtractionException.class, () -> extractor.extract(missing));
    assertThrows(ArticleExtractionException.class, () -> extractor.extract(large));
  }

  @Test
  void validatesEveryRedirectHop() {
    server.createContext(
        "/redirect",
        exchange -> {
          exchange.getResponseHeaders().add("Location", "http://localhost:9/secret");
          exchange.sendResponseHeaders(302, -1);
          exchange.close();
        });
    String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/redirect";
    ArticleExtractionException e =
        assertThrows(ArticleExtractionException.class, () -> extractor.extract(url));
    assertTrue(e.getMessage().contains("local address"));
  }

  @Test
  void rejectsUnsafeUrls() throws Exception {
    UrlSafetyPolicy policy =
        new UrlSafetyPolicy(
            host -> {
              switch (host) {
                case "public.example":
                  return new InetAddress[] {InetAddress.getByName("93.184.216.34")};
                case "rebind.example":
                  return new InetAddress[] {
                    InetAddress.getByName("93.184.216.34"), InetAddress.getByName("10.0.0.5")
                  };
                default:
                  throw new UnknownHostException(host);
              }
            });
    for (String bad :
        new String[] {
          "",
          "not a url",
          "file:///etc/passwd",
          "ftp://public.example/x",
          "javascript:alert(1)",
          "http://localhost/x",
          "http://foo.localhost/x",
          "http://127.0.0.1/x",
          "http://10.1.2.3/x",
          "http://172.16.0.1/x",
          "http://192.168.1.1/x",
          "http://169.254.169.254/latest/meta-data",
          "http://100.64.0.1/x",
          "http://0.0.0.0/x",
          "http://[::1]/x",
          "http://[fd00::1]/x",
          "http://[fe80::1]/x",
          "http://[::ffff:127.0.0.1]/x",
          "http://[64:ff9b::7f00:1]/x",
          "http://user:pw@public.example/x",
          "http://rebind.example/x",
          "http://unknown.example/x"
        }) {
      assertThrows(ArticleExtractionException.class, () -> policy.validate(bad), bad);
    }
    assertEquals(
        "https://public.example/a", policy.validate("https://public.example/a").toString());
  }

  @Test
  void classifiesIpv6Addresses() throws Exception {
    assertTrue(UrlSafetyPolicy.isPublic(InetAddress.getByName("2606:4700:4700::1111")));
    assertFalse(UrlSafetyPolicy.isPublic(InetAddress.getByName("2001:db8::1")));
  }
}
