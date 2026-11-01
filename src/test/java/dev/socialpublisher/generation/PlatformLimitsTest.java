package dev.socialpublisher.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.socialpublisher.model.Platform;
import org.junit.jupiter.api.Test;

class PlatformLimitsTest {
  @Test
  void blueskyCountsGraphemesAndBytes() {
    assertTrue(PlatformLimits.validate(Platform.BLUESKY, "a".repeat(300), 500).isEmpty());
    assertFalse(PlatformLimits.validate(Platform.BLUESKY, "a".repeat(301), 500).isEmpty());
    // 300 family emoji are 300 graphemes but far more than 3000 bytes.
    String family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67";
    var problems = PlatformLimits.validate(Platform.BLUESKY, family.repeat(300), 500);
    assertEquals(1, problems.size());
    assertTrue(problems.get(0).contains("bytes"));
  }

  @Test
  void urlsCountAsTwentyThreeOnXAndMastodon() {
    String url = "https://example.com/" + "x".repeat(100);
    assertEquals(23 + 6, PlatformLimits.xWeight("Hello " + url));
    assertTrue(PlatformLimits.validate(Platform.X, "a".repeat(200) + " " + url, 500).isEmpty());
    assertFalse(PlatformLimits.validate(Platform.X, "a".repeat(260) + " " + url, 500).isEmpty());
    assertTrue(
        PlatformLimits.validate(Platform.MASTODON, "a".repeat(470) + " " + url, 500).isEmpty());
    assertFalse(
        PlatformLimits.validate(Platform.MASTODON, "a".repeat(480) + " " + url, 500).isEmpty());
    assertFalse(PlatformLimits.validate(Platform.MASTODON, "a".repeat(100), 50).isEmpty());
  }

  @Test
  void xWeightsWideCharactersDouble() {
    assertEquals(4, PlatformLimits.xWeight("\u65E5\u672C"));
    assertFalse(PlatformLimits.validate(Platform.X, "\u65E5".repeat(141), 500).isEmpty());
  }

  @Test
  void rejectsEmptyText() {
    assertFalse(PlatformLimits.validate(Platform.LINKEDIN, "  ", 500).isEmpty());
  }
}
