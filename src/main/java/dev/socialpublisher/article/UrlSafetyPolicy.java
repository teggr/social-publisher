package dev.socialpublisher.article;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

/** SSRF guard: only public HTTP(S) hosts are allowed, checked on resolved addresses. */
public class UrlSafetyPolicy {
  /** Resolves a host name to all of its addresses. */
  @FunctionalInterface
  public interface HostResolver {
    InetAddress[] resolve(String host) throws UnknownHostException;
  }

  private final HostResolver resolver;
  private final boolean allowNonPublicAddresses;

  public UrlSafetyPolicy() {
    this(InetAddress::getAllByName, false);
  }

  public UrlSafetyPolicy(HostResolver resolver) {
    this(resolver, false);
  }

  UrlSafetyPolicy(HostResolver resolver, boolean allowNonPublicAddresses) {
    this.resolver = resolver;
    this.allowNonPublicAddresses = allowNonPublicAddresses;
  }

  /** Test-only policy that skips the address check so a loopback test server can be used. */
  static UrlSafetyPolicy allowingNonPublicAddresses() {
    return new UrlSafetyPolicy(InetAddress::getAllByName, true);
  }

  /** Parses and validates a URL, throwing if it is not a safe public HTTP(S) URL. */
  public URI validate(String url) throws ArticleExtractionException {
    if (url == null || url.isBlank()) {
      throw new ArticleExtractionException("Article URL must not be empty");
    }
    URI uri;
    try {
      uri = new URI(url.trim());
    } catch (java.net.URISyntaxException e) {
      throw new ArticleExtractionException("Invalid article URL");
    }
    return validate(uri);
  }

  public URI validate(URI uri) throws ArticleExtractionException {
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    if (!scheme.equals("http") && !scheme.equals("https")) {
      throw new ArticleExtractionException("Only http and https article URLs are allowed");
    }
    if (uri.getRawUserInfo() != null) {
      throw new ArticleExtractionException("Article URLs must not contain credentials");
    }
    String host = uri.getHost();
    if (host == null || host.isBlank()) {
      throw new ArticleExtractionException("Article URL has no host");
    }
    String lower = host.toLowerCase(Locale.ROOT);
    if (lower.equals("localhost") || lower.endsWith(".localhost")) {
      throw new ArticleExtractionException("Refusing to fetch a local address: " + host);
    }
    if (allowNonPublicAddresses) {
      return uri;
    }
    InetAddress[] addresses;
    try {
      addresses =
          resolver.resolve(host.startsWith("[") ? host.substring(1, host.length() - 1) : host);
    } catch (UnknownHostException e) {
      throw new ArticleExtractionException("Could not resolve host: " + host);
    }
    if (addresses == null || addresses.length == 0) {
      throw new ArticleExtractionException("Could not resolve host: " + host);
    }
    for (InetAddress address : addresses) {
      if (!isPublic(address)) {
        throw new ArticleExtractionException(
            "Refusing to fetch " + host + ": it resolves to a private or reserved address");
      }
    }
    return uri;
  }

  static boolean isPublic(InetAddress address) {
    if (address.isAnyLocalAddress()
        || address.isLoopbackAddress()
        || address.isLinkLocalAddress()
        || address.isSiteLocalAddress()
        || address.isMulticastAddress()) {
      return false;
    }
    byte[] b = address.getAddress();
    if (b.length == 4) {
      return isPublicV4(b[0] & 0xff, b[1] & 0xff, b[2] & 0xff);
    }
    int b0 = b[0] & 0xff;
    int b1 = b[1] & 0xff;
    if ((b0 & 0xfe) == 0xfc) { // fc00::/7 unique local
      return false;
    }
    if (b0 == 0xfe && (b1 & 0xc0) == 0x80 || b0 == 0xfe && (b1 & 0xc0) == 0xc0) {
      return false; // fe80::/10 link-local, fec0::/10 deprecated site-local
    }
    if (b0 == 0x20 && b1 == 0x01 && b[2] == 0x0d && (b[3] & 0xff) == 0xb8) {
      return false; // 2001:db8::/32 documentation
    }
    if (b0 == 0x01 && b1 == 0x00 && allZero(b, 2, 8)) {
      return false; // 100::/64 discard-only
    }
    if (b0 == 0x20 && b1 == 0x02) { // 2002::/16 6to4 embeds an IPv4 address
      return isPublicV4(b[2] & 0xff, b[3] & 0xff, b[4] & 0xff);
    }
    if (b0 == 0x00
        && b1 == 0x64
        && (b[2] & 0xff) == 0xff
        && (b[3] & 0xff) == 0x9b
        && allZero(b, 4, 12)) { // 64:ff9b::/96 NAT64 embeds an IPv4 address
      return isPublicV4(b[12] & 0xff, b[13] & 0xff, b[14] & 0xff);
    }
    if (allZero(b, 0, 12)) {
      return false; // IPv4-compatible ::a.b.c.d
    }
    return true;
  }

  private static boolean allZero(byte[] b, int from, int to) {
    for (int i = from; i < to; i++) {
      if (b[i] != 0) {
        return false;
      }
    }
    return true;
  }

  private static boolean isPublicV4(int a, int b, int c) {
    if (a == 0 || a == 10 || a == 127 || a >= 224) {
      return false;
    }
    if (a == 100 && b >= 64 && b <= 127) { // carrier-grade NAT
      return false;
    }
    if (a == 169 && b == 254) {
      return false;
    }
    if (a == 172 && b >= 16 && b <= 31) {
      return false;
    }
    if (a == 192 && b == 168) {
      return false;
    }
    if (a == 192 && b == 0 && (c == 0 || c == 2)) {
      return false;
    }
    if (a == 192 && b == 88 && c == 99) {
      return false;
    }
    if (a == 198 && (b == 18 || b == 19)) {
      return false;
    }
    if (a == 198 && b == 51 && c == 100) {
      return false;
    }
    return !(a == 203 && b == 0 && c == 113);
  }
}
