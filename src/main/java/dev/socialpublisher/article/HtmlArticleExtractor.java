package dev.socialpublisher.article;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.socialpublisher.model.ArticleMetadata;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * Extracts article metadata and main text from static HTML using jsoup. Nothing in the page is
 * executed; only the HTML itself is parsed.
 */
public class HtmlArticleExtractor implements ArticleExtractor {
  static final int MAX_CONTENT_CHARS = 20_000;
  private static final int MIN_CANDIDATE_CHARS = 200;
  private static final ObjectMapper JSON = new ObjectMapper();

  private final SafeHttpFetcher fetcher;

  public HtmlArticleExtractor() {
    this(new SafeHttpFetcher());
  }

  HtmlArticleExtractor(SafeHttpFetcher fetcher) {
    this.fetcher = fetcher;
  }

  @Override
  public ExtractedArticle extract(String url) throws ArticleExtractionException {
    SafeHttpFetcher.Page page = fetcher.fetch(url);
    return parse(page.body(), page.charset(), page.finalUri(), url.trim());
  }

  ExtractedArticle parse(byte[] html, String charset, URI finalUri, String sourceUrl)
      throws ArticleExtractionException {
    Document document;
    try {
      document = Jsoup.parse(new ByteArrayInputStream(html), charset, finalUri.toString());
    } catch (IOException e) {
      throw new ArticleExtractionException("Could not parse article HTML", e);
    }
    ArticleMetadata metadata = new ArticleMetadata();
    metadata.setSourceUrl(sourceUrl);
    metadata.setCanonicalUrl(canonicalUrl(document, finalUri));
    metadata.setTitle(title(document, finalUri));
    metadata.setDescription(
        firstNonBlank(
            meta(document, "property", "og:description"),
            meta(document, "name", "description"),
            meta(document, "name", "twitter:description")));
    metadata.setPublishedAt(publishedAt(document));
    metadata.setImageUrl(
        httpUrl(
            firstNonBlank(
                meta(document, "property", "og:image"), meta(document, "name", "twitter:image")),
            finalUri));

    String content = mainContent(document);
    if (content.isBlank()) {
      throw new ArticleExtractionException("No article content could be extracted from the page");
    }
    return new ExtractedArticle(metadata, content);
  }

  private static String canonicalUrl(Document document, URI finalUri) {
    String fetched = stripFragment(finalUri.toString());
    Element link = document.selectFirst("link[rel~=(?i)^canonical$][href]");
    String declared = link != null ? link.absUrl("href") : meta(document, "property", "og:url");
    String candidate = httpUrl(declared, finalUri);
    if (candidate == null) {
      return fetched;
    }
    // The page is untrusted: a canonical link to a different site is not followed.
    String candidateHost = host(candidate);
    if (candidateHost == null || !candidateHost.equals(host(fetched))) {
      return fetched;
    }
    return stripFragment(candidate);
  }

  private static String title(Document document, URI finalUri) {
    String title =
        firstNonBlank(
            meta(document, "property", "og:title"),
            meta(document, "name", "twitter:title"),
            clean(document.title()),
            textOf(document.selectFirst("h1")));
    return title != null ? title : finalUri.getHost();
  }

  private static String publishedAt(Document document) {
    String fromMeta =
        firstNonBlank(
            meta(document, "property", "article:published_time"),
            meta(document, "itemprop", "datePublished"),
            meta(document, "name", "date"),
            meta(document, "name", "pubdate"));
    if (fromMeta != null) {
      return fromMeta;
    }
    for (Element script : document.select("script[type=application/ld+json]")) {
      try {
        String found = findDatePublished(JSON.readTree(script.data()));
        if (found != null) {
          return found;
        }
      } catch (IOException ignored) {
        // malformed structured data is not fatal
      }
    }
    Element time = document.selectFirst("article time[datetime], time[datetime][pubdate]");
    return time == null ? null : clean(time.attr("datetime"));
  }

  private static String findDatePublished(JsonNode node) {
    if (node == null) {
      return null;
    }
    if (node.isObject()) {
      JsonNode date = node.get("datePublished");
      if (date != null && date.isTextual() && !date.asText().isBlank()) {
        return date.asText().trim();
      }
    }
    if (node.isContainerNode()) {
      for (JsonNode child : node) {
        String found = findDatePublished(child);
        if (found != null) {
          return found;
        }
      }
    }
    return null;
  }

  private static String mainContent(Document document) {
    // Structured data is read before this point; now drop everything that is not prose.
    document
        .select(
            "script, style, noscript, template, iframe, svg, form, nav, header, footer, aside,"
                + " [aria-hidden=true], [hidden]")
        .remove();
    Element root = null;
    for (String selector : List.of("[itemprop=articleBody]", "article", "main", "[role=main]")) {
      for (Element candidate : document.select(selector)) {
        if (candidate.text().length() >= MIN_CANDIDATE_CHARS
            && (root == null || candidate.text().length() > root.text().length())) {
          root = candidate;
        }
      }
      if (root != null) {
        break;
      }
    }
    if (root == null) {
      root = document.body();
    }
    if (root == null) {
      return "";
    }
    List<String> blocks = new ArrayList<>();
    for (Element element : root.select("h1, h2, h3, h4, h5, h6, p, li, pre, blockquote")) {
      boolean container =
          element.tagName().equals("blockquote") && !element.select("p, li, pre").isEmpty();
      if (container) {
        continue; // nested paragraphs are emitted individually
      }
      String text =
          element.tagName().equals("pre") ? element.wholeText().strip() : clean(element.text());
      if (text != null && !text.isBlank()) {
        blocks.add(element.tagName().startsWith("h") ? "## " + text : text);
      }
    }
    String joined = String.join("\n\n", blocks);
    if (joined.isBlank()) {
      String text = clean(root.text());
      joined = text == null ? "" : text;
    }
    return joined.length() > MAX_CONTENT_CHARS ? joined.substring(0, MAX_CONTENT_CHARS) : joined;
  }

  private static String meta(Document document, String attribute, String name) {
    for (Element element : document.select("meta[" + attribute + "]")) {
      if (element.attr(attribute).equalsIgnoreCase(name)) {
        String value = clean(element.attr("content"));
        if (value != null) {
          return value;
        }
      }
    }
    return null;
  }

  private static String httpUrl(String value, URI base) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      URI resolved = base.resolve(value.trim());
      String scheme =
          resolved.getScheme() == null ? "" : resolved.getScheme().toLowerCase(Locale.ROOT);
      return (scheme.equals("http") || scheme.equals("https")) && resolved.getHost() != null
          ? resolved.toString()
          : null;
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static String host(String url) {
    try {
      String host = new URI(url).getHost();
      if (host == null) {
        return null;
      }
      host = host.toLowerCase(Locale.ROOT);
      return host.startsWith("www.") ? host.substring(4) : host;
    } catch (java.net.URISyntaxException e) {
      return null;
    }
  }

  private static String stripFragment(String url) {
    int hash = url.indexOf('#');
    return hash < 0 ? url : url.substring(0, hash);
  }

  private static String textOf(Element element) {
    return element == null ? null : clean(element.text());
  }

  private static String clean(String value) {
    if (value == null) {
      return null;
    }
    String cleaned = value.replaceAll("\\s+", " ").trim();
    return cleaned.isEmpty() ? null : cleaned;
  }

  private static String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }
}
