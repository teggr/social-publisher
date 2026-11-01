package dev.socialpublisher.generation;

import dev.socialpublisher.model.Platform;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Length rules for generated posts. URLs count as 23 characters on X and Mastodon. */
public final class PlatformLimits {
  public static final int BLUESKY_GRAPHEMES = 300;
  public static final int BLUESKY_BYTES = 3000;
  public static final int X_WEIGHTED_CHARACTERS = 280;
  public static final int LINKEDIN_CHARACTERS = 3000;
  public static final int LINK_WEIGHT = 23;
  private static final Pattern GRAPHEME = Pattern.compile("\\X");
  private static final Pattern URL = Pattern.compile("https?://\\S+");

  private PlatformLimits() {}

  /** The limit shown to the model and users for a platform. */
  public static int limit(Platform platform, int mastodonLimit) {
    return switch (platform) {
      case BLUESKY -> BLUESKY_GRAPHEMES;
      case X -> X_WEIGHTED_CHARACTERS;
      case LINKEDIN -> LINKEDIN_CHARACTERS;
      case MASTODON -> mastodonLimit;
    };
  }

  /** Returns human-readable problems; empty when the text is within the limits. */
  public static List<String> validate(Platform platform, String text, int mastodonLimit) {
    List<String> problems = new ArrayList<>();
    if (text == null || text.isBlank()) {
      problems.add("text is empty");
      return problems;
    }
    switch (platform) {
      case BLUESKY -> {
        int graphemes = (int) GRAPHEME.matcher(text).results().count();
        if (graphemes > BLUESKY_GRAPHEMES) {
          problems.add(
              "has " + graphemes + " graphemes; the Bluesky limit is " + BLUESKY_GRAPHEMES);
        }
        int bytes = text.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > BLUESKY_BYTES) {
          problems.add("has " + bytes + " bytes; the Bluesky limit is " + BLUESKY_BYTES);
        }
      }
      case X -> {
        int weight = xWeight(text);
        if (weight > X_WEIGHTED_CHARACTERS) {
          problems.add("weighs " + weight + "; the X limit is " + X_WEIGHTED_CHARACTERS);
        }
      }
      case MASTODON -> {
        int length = linkAdjustedLength(text);
        if (length > mastodonLimit) {
          problems.add("has " + length + " characters; the Mastodon limit is " + mastodonLimit);
        }
      }
      case LINKEDIN -> {
        int length = text.codePointCount(0, text.length());
        if (length > LINKEDIN_CHARACTERS) {
          problems.add(
              "has " + length + " characters; the LinkedIn limit is " + LINKEDIN_CHARACTERS);
        }
      }
    }
    return problems;
  }

  /** Characters counted with each URL as 23, as Mastodon does. */
  static int linkAdjustedLength(String text) {
    String withoutLinks = URL.matcher(text).replaceAll("");
    int links = (int) URL.matcher(text).results().count();
    return withoutLinks.codePointCount(0, withoutLinks.length()) + links * LINK_WEIGHT;
  }

  /** X weighted length: URLs are 23, Latin-range text 1, other scripts and emoji 2. */
  static int xWeight(String text) {
    String withoutLinks = URL.matcher(text).replaceAll("");
    int weight = (int) URL.matcher(text).results().count() * LINK_WEIGHT;
    for (int i = 0; i < withoutLinks.length(); ) {
      int cp = withoutLinks.codePointAt(i);
      i += Character.charCount(cp);
      boolean light =
          cp <= 4351
              || (cp >= 8192 && cp <= 8205)
              || (cp >= 8208 && cp <= 8223)
              || (cp >= 8242 && cp <= 8247);
      weight += light ? 1 : 2;
    }
    return weight;
  }
}
